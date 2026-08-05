package org.locationtech.proj4j.datum;

import org.junit.Test;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ProjCoordinate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class DatumShiftTest {

  private final CRSFactory crsFactory = new CRSFactory();
  private static final CoordinateTransformFactory transformerFactory = new CoordinateTransformFactory();

  /**
   * Gauss-Krueger zone 3 to a datum-less UTM 32N, by two routes.
   *
   * <h2>What this test used to assert, and why it was an accident</h2>
   *
   * <p>It computed both routes and asserted only that they agreed to within 1 mm. They do not, and
   * <b>PROJ 9.8.1 says they should not</b>: the target CRS declares neither {@code +datum} nor
   * {@code +towgs84}, so its datum is unknown, and a datum shift into an unknown datum is not a
   * thing either library will perform. The direct route therefore applies <em>no</em> Potsdam shift,
   * while the explicit route applies it on the way through EPSG:4326 and then arrives at the same
   * datum-less target. The two answers differ by <b>74.496 m easting and 127.513 m northing</b>, and
   * that is the correct behaviour of both libraries.
   *
   * <p>The 1 mm assertion passed only while Proj4J nested its unknown-datum check <em>inside</em> an
   * ellipsoid-equality guard, so the direct route silently shifted too. PROJ checks it
   * unconditionally and first ({@code 5.2.0:src/pj_transform.c:835}). Fixing the nesting exposed the
   * test, which had been asserting the bug.
   *
   * <h2>What it asserts now</h2>
   *
   * <p>Every leg against {@code cs2cs} 9.8.1 instead of against the other leg, so the disagreement
   * is pinned as a <em>measured, referenced</em> fact rather than assumed away. Both routes are
   * reproduced to better than 5e-8 m.
   *
   * <p>One footnote on the reference: PROJ 9.8.1 <b>rejects</b> the capitalised {@code +pm=Greenwich}
   * in the target definition below with {@code "tmerc: Invalid value for pm"} (Proj4J's
   * {@code PrimeMeridian.forName} silently falls back to Greenwich, which is a separate and much
   * smaller divergence). The definition is left byte-identical to the one this test has always used;
   * the {@code cs2cs} references were taken with {@code +pm=greenwich}, which is the same meridian
   * and the same zero offset.
   */
  @Test
  public void gk3ToUtm32Zn() {

    String epsg31467Parameters = "+proj=tmerc +lat_0=0.0 +lon_0=9.0 +k_0=1.0 +x_0=3500000.0 +y_0=0.0 +datum=potsdam +a=6377397.155 +rf=299.1528128 +towgs84=598.1,73.7,418.2,0.202,0.045,-2.455,6.7 +pm=greenwich +units=m +no_defs";
    String utm32znParameters = "+proj=tmerc +lat_0=0.0 +lon_0=9.0 +k_0=0.9996 +x_0=3.25E7 +y_0=0.0 +a=6378137.0 +rf=298.257222 +pm=Greenwich +units=m +no_defs";

    CoordinateReferenceSystem etrsCrs = crsFactory.createFromName("EPSG:4258");
    CoordinateReferenceSystem wgs84Crs = crsFactory.createFromName("EPSG:4326");
    CoordinateReferenceSystem dhdnCrs = crsFactory.createFromName("EPSG:4314");
    CoordinateReferenceSystem gk3Crs = crsFactory.createFromParameters("Anon", epsg31467Parameters);
    CoordinateReferenceSystem utm32znCrs = crsFactory.createFromParameters("Anon", utm32znParameters);

    ProjCoordinate coordinate = new ProjCoordinate(9.0, 50.0);

    // cs2cs 9.8.1: 3500000.000000000  5540279.541956067 -- bit-identical.
    ProjCoordinate dhdnToGk3Coordinate = transform(dhdnCrs, gk3Crs, coordinate);
    assertEquals(3500000.000000000, dhdnToGk3Coordinate.x, 1.0e-8);
    assertEquals(5540279.541956067, dhdnToGk3Coordinate.y, 1.0e-8);

    // Route A, direct. No Potsdam shift, because the target datum is unknown.
    // cs2cs 9.8.1: 32500000.000000000  5538630.702735838
    ProjCoordinate gk3ToUtm32znCoordinate = transform(gk3Crs, utm32znCrs, dhdnToGk3Coordinate);
    assertEquals(32500000.000000000, gk3ToUtm32znCoordinate.x, 1.0e-6);
    assertEquals(5538630.702735838, gk3ToUtm32znCoordinate.y, 1.0e-6);

    // Route B, explicitly through EPSG:4326. The Potsdam shift IS applied, on the DHDN -> WGS84 leg.
    ProjCoordinate gk3ToDhdnCoordinate = transform(gk3Crs, dhdnCrs, dhdnToGk3Coordinate);
    assertEquals(9.0, gk3ToDhdnCoordinate.x, 1.0e-11);
    assertEquals(50.0, gk3ToDhdnCoordinate.y, 1.0e-11);

    // cs2cs 9.8.1: 8.998960545476  49.998853136854
    ProjCoordinate dhdnToWgs84Coordinate = transform(dhdnCrs, wgs84Crs, gk3ToDhdnCoordinate);
    assertEquals(8.998960545476, dhdnToWgs84Coordinate.x, 1.0e-11);
    assertEquals(49.998853136854, dhdnToWgs84Coordinate.y, 1.0e-11);

    ProjCoordinate wgs84ToEtrsCoordinate = transform(wgs84Crs, etrsCrs, dhdnToWgs84Coordinate);
    ProjCoordinate etrsToUtm32znCoordinate = transform(etrsCrs, utm32znCrs, wgs84ToEtrsCoordinate);

    // cs2cs 9.8.1, WGS84 lon/lat -> the same datum-less target: 32499925.503562238  5538503.189777711
    assertEquals(32499925.503562238, etrsToUtm32znCoordinate.x, 1.0e-6);
    assertEquals(5538503.189777711, etrsToUtm32znCoordinate.y, 1.0e-6);

    // And the divergence itself, pinned rather than asserted away. It is the Potsdam -> WGS84 shift,
    // present on one route and absent on the other because the target declares no datum.
    double dx = Math.abs(gk3ToUtm32znCoordinate.x - etrsToUtm32znCoordinate.x);
    double dy = Math.abs(gk3ToUtm32znCoordinate.y - etrsToUtm32znCoordinate.y);
    assertEquals("the two routes must still differ by the unapplied Potsdam shift",
        74.496437762, dx, 1.0e-5);
    assertEquals("the two routes must still differ by the unapplied Potsdam shift",
        127.512958127, dy, 1.0e-5);
    assertTrue("a datum-less target must not silently acquire a datum shift", dx > 1.0);
  }

  // Deleted: debug(). It ran the same two transforms as route A of gk3ToUtm32Zn above -- the only
  // difference was +rf=298.257222101 in the target where gk3ToUtm32Zn writes +rf=298.257222 -- then
  // discarded both results and ended `assertTrue(true)`. It asserted nothing, and the behaviour it
  // exercised is already pinned against cs2cs 9.8.1 above, so there was nothing to preserve: the
  // rf difference is a typo in the older definition, not a second case worth its own references.

  private ProjCoordinate transform(
      CoordinateReferenceSystem sourceCrs,
      CoordinateReferenceSystem targetCrs,
      ProjCoordinate coordinate) {
    ProjCoordinate result = new ProjCoordinate();
    transformerFactory.createTransform(sourceCrs, targetCrs).transform(coordinate, result);
    return result;
  }

}
