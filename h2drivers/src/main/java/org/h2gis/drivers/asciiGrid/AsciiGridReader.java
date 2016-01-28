/**
 * H2GIS is a library that brings spatial support to the H2 Database Engine
 * <http://www.h2database.com>.
 *
 * H2GIS is distributed under GPL 3 license. It is produced by CNRS
 * <http://www.cnrs.fr/>.
 *
 * H2GIS is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * H2GIS is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * H2GIS. If not, see <http://www.gnu.org/licenses/>.
 *
 * For more information, please consult: <http://www.h2gis.org/>
 * or contact directly: info_at_h2gis.org
 */
package org.h2gis.drivers.asciiGrid;

import it.geosolutions.imageio.plugins.arcgrid.AsciiGridsImageReader;
import it.geosolutions.imageio.plugins.arcgrid.raster.AsciiGridRaster;
import it.geosolutions.imageio.plugins.arcgrid.spi.AsciiGridsImageReaderSpi;
import it.geosolutions.imageio.stream.input.FileImageInputStreamExtImpl;
import org.h2.api.GeoRaster;
import org.h2.util.GeoRasterRenderedImage;
import org.h2.util.RasterUtils;
import org.h2gis.drivers.utility.FileUtil;
import org.h2gis.drivers.utility.PRJUtil;
import org.h2gis.h2spatialapi.InputStreamProgressMonitor;
import org.h2gis.h2spatialapi.ProgressVisitor;
import org.h2gis.utilities.JDBCUtilities;
import org.h2gis.utilities.TableLocation;

import javax.imageio.stream.ImageInputStream;
import java.awt.image.RenderedImage;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;

/**
 * Class to read an Arc/Info ASCII Grid or GRASS ASCII Grid format using
 * imageio-ext.
 *
 * @author Erwan Bocher
 */
public class AsciiGridReader {

    

    private File imageFile;
    private String fileNameExtension;
    private int srid = 0;
    private AsciiGridRaster asciiGridRaster;
    private RenderedImage image;

    /**
     * Use {@link #fetch(Connection, File)}
     */
    private AsciiGridReader() {

    }

    /**
     * Create AsciiGridReader using asc or arx file on disk.
     *
     * @param connection Active connection, not closed by this method
     * @param imageFile Ascii file path
     * @return Instance of AsciiGridReader
     * @throws IOException
     * @throws SQLException
     */
    public static AsciiGridReader fetch(Connection connection, File imageFile) throws IOException, SQLException {
        AsciiGridReader asciiGridReader = new AsciiGridReader();
        asciiGridReader.imageFile = imageFile;
        String filePath = imageFile.getPath();
        final int dotIndex = filePath.lastIndexOf('.');
        asciiGridReader.fileNameExtension = filePath.substring(dotIndex + 1).toLowerCase();
        if (asciiGridReader.fileNameExtension.equals("asc") || asciiGridReader.fileNameExtension.equals("arx")) {            
            asciiGridReader.prepareImage();
            Map<String, File> matchExt = FileUtil.fetchFileByIgnoreCaseExt(imageFile.getParentFile(), FileUtil
                    .getBaseName(imageFile), "prj");
            asciiGridReader.srid = PRJUtil.getSRID(connection, matchExt.get("prj"));
            return asciiGridReader;
        } else {
            throw new IllegalArgumentException("Supported extensions are asc or arx.");
        }
    }

    /**
     * Copy the ascii grid file into a table
     *
     * @param tableReference
     * @param connection
     * @param progress
     * @throws SQLException
     * @throws IOException
     */
    public void read(String tableReference, Connection connection, ProgressVisitor progress) throws SQLException, IOException {
        final boolean isH2 = JDBCUtilities.isH2DataBase(connection.getMetaData());
        readImage(tableReference, isH2, connection, progress);
    }

    /**
     * @return MetaData of loaded ascii grid file
     */
    public RasterUtils.RasterMetaData getRasterMetaData() {
        double scaleX = asciiGridRaster.getCellSizeX();
        // WKB Raster is mirrored on Y plane
        double scaleY = - asciiGridRaster.getCellSizeY();
        double upperLeftX = asciiGridRaster.getXllCellCoordinate();
        // WKB Raster Y is Upper. Ascii Grid Y is Lower (ll for lower left)
        double upperLeftY = asciiGridRaster.getYllCellCoordinate() + (-scaleY * (asciiGridRaster.getNRows()));// + scaleY * asciiGridRaster.getNRows();
        RasterUtils.RasterBandMetaData rbmd = new RasterUtils.RasterBandMetaData(asciiGridRaster.getNoData(), RasterUtils.PixelType.PT_32BF, true, 0);
        return new RasterUtils.RasterMetaData(RasterUtils.LAST_WKB_VERSION, 1, scaleX, scaleY, upperLeftX, upperLeftY, 0, 0, srid,
                asciiGridRaster.getNCols(), asciiGridRaster.getNRows(),
                new RasterUtils.RasterBandMetaData[]{rbmd});
    }

    /**
     * Import the ascii grid file
     *
     * @param tableReference
     * @param isH2
     * @param connection
     * @param progressVisitor
     * @throws java.sql.SQLException
     */
    public void readImage(String tableReference, boolean isH2, Connection connection, ProgressVisitor progressVisitor) throws
            SQLException {
        TableLocation location = TableLocation.parse(tableReference, isH2);
        try {
            RasterUtils.RasterMetaData rmd = getRasterMetaData();
            StringBuilder sb = new StringBuilder();
            sb.append("create table ").append(location.toString()).append("(id serial, the_raster raster) as ");
            sb.append("select null, ");
            if (isH2) {
                sb.append("?;");
            } else {
                sb.append("ST_SetGeoReference(ST_FromGDALRaster(?,");
                sb.append(srid);
                sb.append("), ");
                sb.append(rmd.ipX).append(",");
                sb.append(rmd.ipY).append(",");
                sb.append(rmd.scaleX).append(",");
                sb.append(rmd.scaleY).append(",");
                sb.append(0).append(",");
                sb.append(0);
                sb.append("));");
            }
            PreparedStatement stmt = connection.prepareStatement(sb.toString());

            try {
                GeoRaster geoRaster = GeoRasterRenderedImage
                        .create(image, rmd);
                
                InputStream wkbStream = geoRaster.asWKBRaster();
                try {
                    stmt.setBinaryStream(1, new InputStreamProgressMonitor(progressVisitor, wkbStream, geoRaster
                            .getMetaData().getTotalLength()));
                    stmt.execute();
                } finally {
                    wkbStream.close();
                }
            } finally {
                stmt.close();
            }

        } catch (FileNotFoundException ex) {
            throw new SQLException(ex.getLocalizedMessage(), ex);
        } catch (IOException ex) {
            throw new SQLException(ex.getLocalizedMessage(), ex);
        }

    }
    
    /**
     * Prepare the image reader to get AsciiGridRaster metadata
     */
    private void prepareImage() throws IOException {
        AsciiGridsImageReader asciiGridsImageReader = new AsciiGridsImageReader(new AsciiGridsImageReaderSpi());
        ImageInputStream fileImageInputStreamExt = new FileImageInputStreamExtImpl(imageFile);
        asciiGridsImageReader.setInput(fileImageInputStreamExt);
        asciiGridRaster = asciiGridsImageReader.getRasterReader();
        image = asciiGridsImageReader.readAsRenderedImage(asciiGridsImageReader.getMinIndex(), asciiGridsImageReader
                .getDefaultReadParam());
    }

    /**
     * @return Projection srid
     */
    public int getSrid() {
        return srid;
    }

    /**
     * Get the AsciiGridRaster to obtain metadata.
     * 
     * @return 
     */
    public AsciiGridRaster getAsciiGridRaster() {
        return asciiGridRaster;
    }

    /**
     * Get an instance of the image
     * 
     * @return 
     */
    public RenderedImage getImage() {
        return image;
    }
    
    
}
