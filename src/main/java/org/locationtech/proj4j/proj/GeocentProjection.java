package org.locationtech.proj4j.proj;

import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.datum.GeocentricConverter;

public class GeocentProjection extends Projection {

  @Override
  public ProjCoordinate projectRadians(ProjCoordinate src, ProjCoordinate dst) {
    GeocentricConverter geocentricConverter = new GeocentricConverter(this.ellipsoid);
    geocentricConverter.convertGeodeticToGeocentric(dst);
    return dst;
  }
}