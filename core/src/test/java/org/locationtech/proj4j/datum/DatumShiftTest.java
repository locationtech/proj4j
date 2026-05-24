package org.locationtech.proj4j.datum;

import org.junit.Test;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ProjCoordinate;

import static org.junit.Assert.assertTrue;

public class DatumShiftTest {

  private final CRSFactory crsFactory = new CRSFactory();
  private static final CoordinateTransformFactory transformerFactory = new CoordinateTransformFactory();

  @Test
  public void gk3ToUtm32Zn() {

    String epsg31467Parameters = "+proj=tmerc +lat_0=0.0 +lon_0=9.0 +k_0=1.0 +x_0=3500000.0 +y_0=0.0 +datum=potsdam +a=6377397.155 +f=299.1528128 +towgs84=598.1,73.7,418.2,0.202,0.045,-2.455,6.7 +pm=greenwich +units=m +no_defs";
    String utm32znParameters = "+proj=tmerc +lat_0=0.0 +lon_0=9.0 +k_0=0.9996 +x_0=3.25E7 +y_0=0.0 +a=6378137.0 +f=298.257222 +pm=Greenwich +units=m +no_defs";

    CoordinateReferenceSystem etrsCrs = crsFactory.createFromName("EPSG:4258");
    CoordinateReferenceSystem wgs84Crs = crsFactory.createFromName("EPSG:4326");
    CoordinateReferenceSystem dhdnCrs = crsFactory.createFromName("EPSG:4314");
    CoordinateReferenceSystem gk3Crs = crsFactory.createFromParameters("Anon", epsg31467Parameters);
    CoordinateReferenceSystem utm32znCrs = crsFactory.createFromParameters("Anon", utm32znParameters);

    ProjCoordinate coordinate = new ProjCoordinate(9.0, 50.0);

    ProjCoordinate dhdnToGk3Coordinate = transform(dhdnCrs, gk3Crs, coordinate);
    ProjCoordinate gk3ToUtm32znCoordinate = transform(gk3Crs, utm32znCrs, dhdnToGk3Coordinate);

    ProjCoordinate gk3ToDhdnCoordinate = transform(gk3Crs, dhdnCrs, dhdnToGk3Coordinate);
    ProjCoordinate dhdnToWgs84Coordinate = transform(dhdnCrs, wgs84Crs, gk3ToDhdnCoordinate);
    ProjCoordinate wgs84ToEtrsCoordinate = transform(wgs84Crs, etrsCrs, dhdnToWgs84Coordinate);
    ProjCoordinate etrsToUtm32znCoordinate = transform(etrsCrs, utm32znCrs, wgs84ToEtrsCoordinate);

    double dx = Math.abs(gk3ToUtm32znCoordinate.x - etrsToUtm32znCoordinate.x);
    double dy = Math.abs(gk3ToUtm32znCoordinate.y - etrsToUtm32znCoordinate.y);
    double delta = Math.max(dx, dy);

     assertTrue(delta < 0.001);
  }

  @Test
  public void debug() {
    
    String epsg31467Parameters = "+proj=tmerc +lat_0=0.0 +lon_0=9.0 +k_0=1.0 +x_0=3500000.0 +y_0=0.0 +datum=potsdam +a=6377397.155 +f=299.1528128 +towgs84=598.1,73.7,418.2,0.202,0.045,-2.455,6.7 +pm=greenwich +units=m +no_defs";
    String utm32znParameters = "+proj=tmerc +lat_0=0.0 +lon_0=9.0 +k_0=0.9996 +x_0=3.25E7 +y_0=0.0 +a=6378137.0 +f=298.257222101 +pm=Greenwich +units=m +no_defs";
    
    CoordinateReferenceSystem dhdnCrs = crsFactory.createFromName("EPSG:4314");
    CoordinateReferenceSystem gk3Crs = crsFactory.createFromParameters("Anon", epsg31467Parameters);
    CoordinateReferenceSystem utm32znCrs = crsFactory.createFromParameters("Anon", utm32znParameters);
    
    ProjCoordinate coordinate = new ProjCoordinate(9.0, 50.0);
    
    ProjCoordinate dhdnToGk3Coordinate = transform(dhdnCrs, gk3Crs, coordinate);
    ProjCoordinate gk3ToUtm32znCoordinate = transform(gk3Crs, utm32znCrs, dhdnToGk3Coordinate);
    
    
    assertTrue(true);
  }

  private ProjCoordinate transform(
      CoordinateReferenceSystem sourceCrs,
      CoordinateReferenceSystem targetCrs,
      ProjCoordinate coordinate) {
    ProjCoordinate result = new ProjCoordinate();
    transformerFactory.createTransform(sourceCrs, targetCrs).transform(coordinate, result);
    return result;
  }

}
