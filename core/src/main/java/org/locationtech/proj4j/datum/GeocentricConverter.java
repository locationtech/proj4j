/*******************************************************************************
 * Copyright 2009, 2017 Martin Davis
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.locationtech.proj4j.datum;

import org.locationtech.proj4j.ConvergenceFailureException;
import org.locationtech.proj4j.CrsTransformException;
import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.util.ProjectionMath;

/**
 * Provides conversions between Geodetic coordinates
 * (latitude, longitude in radians and height in meters)
 * and Geocentric coordinates
 * (X, Y, Z) in meters.
 * <p>
 * Provenance: Ported from GEOCENTRIC by the U.S. Army Topographic Engineering Center via PROJ.4
 *
 * @author Martin Davis
 */
public class GeocentricConverter implements java.io.Serializable {

  private static final long serialVersionUID = -7800493495528008240L;

  /*
   * 
   * REFERENCES
   *    
   *    An Improved Algorithm for Geocentric to Geodetic Coordinate Conversion,
   *    Ralph Toms, February 1996  UCRL-JC-123138.
   *    
   *    Further information on GEOCENTRIC can be found in the Reuse Manual.
   *
   *    GEOCENTRIC originated from : U.S. Army Topographic Engineering Center
   *                                 Geospatial Information Division
   *                                 7701 Telegraph Road
   *                                 Alexandria, VA  22310-3864
   *
   * LICENSES
   *
   *    None apply to this component.
   *
   * RESTRICTIONS
   *
   *    GEOCENTRIC has no restrictions.
   */

    double a;
    double b;
    double a2;
    double b2;
    double e2;
    double ep2;

    public GeocentricConverter(Ellipsoid ellipsoid) {
        // Preserve the ellipsoid value precisions
        this(ellipsoid.getA(), ellipsoid.getB(), ellipsoid.getEccentricitySquared());
    }

    public GeocentricConverter(double a, double b, double e2) {
        this.a = a;
        this.b = b;
        a2 = a * a;
        b2 = b * b;
        this.e2 = e2;
        ep2 = (a2 - b2) / b2;
    }

    /**
     * Re-points this converter at the WGS 84 ellipsoid, for the side of a datum transform that sits
     * against a horizontal grid shift.
     *
     * <h4>What this is for, because it reads like a relic and is not</h4>
     *
     * <p><b>The output of a horizontal grid shift is WGS 84 geodetic latitude and longitude by
     * construction.</b> An NTv2, NADCON or CTABLE grid is defined as the correction from the local
     * datum's lon/lat to WGS 84's; it says nothing about, and does nothing to, the ellipsoid. So
     * once {@link Datum#shift} has run on the source the coordinate is on WGS 84 whatever the
     * source datum's own ellipsoid was, and symmetrically the target's
     * {@link Datum#inverseShift} expects to be handed WGS 84. The geocentric leg between them must
     * therefore run on WGS 84 on that side, not on the datum's declared ellipsoid.
     *
     * <p>Ported from {@code 5.2.0:src/pj_transform.c:874-889}, which assigns
     * {@code src_a}/{@code src_es} <em>after</em> applying the source shift and
     * {@code dst_a}/{@code dst_es} <em>before</em> the destination's inverse shift:
     * <pre>
     * if( src-&gt;datum_type == PJD_GRIDSHIFT ) {
     *     pj_apply_gridshift_2( src, 0, point_count, point_offset, x, y, z );
     *     src_a  = SRS_WGS84_SEMIMAJOR;
     *     src_es = SRS_WGS84_ESQUARED;
     * }
     * if( dst-&gt;datum_type == PJD_GRIDSHIFT ) {
     *     dst_a  = SRS_WGS84_SEMIMAJOR;
     *     dst_es = SRS_WGS84_ESQUARED;
     * }
     * </pre>
     *
     * <p><b>It is not superseded at 9.8.1.</b> The pipeline upstream builds for a
     * {@code +nadgrids=} CRS into a {@code +towgs84} one is
     * {@code hgridshift} &rarr; {@code +proj=cart +ellps=WGS84} &rarr; {@code helmert} &rarr;
     * {@code +inv +proj=cart +ellps=<the other datum>}: the gridded datum's own ellipsoid never
     * appears on its side of the {@code cart} pair. Same invariant, expressed in the pipeline layer
     * instead of in {@code pj_datum_transform}.
     *
     * <p>Nor does {@code +nadgrids=@null} exempt anything. {@code 9.8.1:src/grids.cpp:2661}
     * short-circuits {@code HorizontalShiftGridSet::open} on {@code filename == "null"} to a
     * synthetic global {@code NullHorizontalShiftGrid} returning {@code (0.0f, 0.0f)} — <b>a grid
     * that shifts by zero, not a suppression of the datum change</b>. {@code EPSG:3857} is
     * {@code +nadgrids=@null} on an {@code a=b=6378137} sphere, so this method is what keeps
     * {@code EPSG:4326 -> EPSG:3857} from round-tripping WGS 84 latitudes through a sphere:
     * disabling it costs <b>25,380 m</b> of northing there, and 305 m to 30 km on ten other
     * measured pairs. Pinned, with its {@code cs2cs} 9.8.1 references, by
     * {@code datum/NadgridsWgs84OverrideTest}.
     *
     * <p>All six derived fields are recomputed, not just {@code a} and {@code e2}. Only those two
     * are read by either conversion below, so this is a behavioural no-op — but leaving {@code b},
     * {@code a2}, {@code b2} and {@code ep2} describing the <em>previous</em> ellipsoid left an
     * internally inconsistent object one dereference away from being wrong, and that was already
     * on the defect register.
     */
    public void overrideWithWGS84Params() {
        this.a = Ellipsoid.WGS84.getA();
        this.e2 = Ellipsoid.WGS84.getEccentricitySquared();
        this.b = Ellipsoid.WGS84.getB();
        this.a2 = this.a * this.a;
        this.b2 = this.b * this.b;
        this.ep2 = (this.a2 - this.b2) / this.b2;
    }

    public boolean isEqual(GeocentricConverter gc) {
        // Check if geocentricly equal
        // https://github.com/OSGeo/PROJ/blob/5.2.0/src/pj_transform.c#L892
        return this.a == gc.a && this.e2 == gc.e2;
    }

    /**
     * Converts geodetic coordinates
     * (latitude, longitude, and height) to geocentric coordinates (X, Y, Z),
     * according to the current ellipsoid parameters.
     * <p>
     * Latitude  : Geodetic latitude in radians                     (input)
     * Longitude : Geodetic longitude in radians                    (input)
     * Height    : Geodetic height, in meters                       (input)
     * <p>
     * X         : Calculated Geocentric X coordinate, in meters    (output)
     * Y         : Calculated Geocentric Y coordinate, in meters    (output)
     * Z         : Calculated Geocentric Z coordinate, in meters    (output)
     */
    public void convertGeodeticToGeocentric(ProjCoordinate p) {
        double Longitude = p.x;
        double Latitude = p.y;
        double Height = p.hasValidZOrdinate() ? p.z : 0;   //Z value not always supplied
        double X;  // output
        double Y;
        double Z;

        double Rn;            /*  Earth radius at location  */
        double Sin_Lat;       /*  Math.sin(Latitude)  */
        double Sin2_Lat;      /*  Square of Math.sin(Latitude)  */
        double Cos_Lat;       /*  Math.cos(Latitude)  */

    /*
    ** Don't blow up if Latitude is just a little out of the value
    ** range as it may just be a rounding issue.  Also removed longitude
    ** test, it should be wrapped by Math.cos() and Math.sin().  NFW for PROJ.4, Sep/2001.
    */
        if (Latitude < -ProjectionMath.HALFPI && Latitude > -1.001 * ProjectionMath.HALFPI) {
            Latitude = -ProjectionMath.HALFPI;
        } else if (Latitude > ProjectionMath.HALFPI && Latitude < 1.001 * ProjectionMath.HALFPI) {
            Latitude = ProjectionMath.HALFPI;
        } else if ((Latitude < -ProjectionMath.HALFPI) || (Latitude > ProjectionMath.HALFPI)) {
      /* Latitude out of range */
            // Was IllegalStateException, which is unchecked and NOT a Proj4jException -- so it
            // escaped every `catch (Proj4jException)` in the library and in every caller. The
            // golden-master sweep found 23 ordinary CRS pairs reaching this line, so it is not a
            // degenerate-input-only path.
            throw new CrsTransformException(ErrorCause.INVALID_COORDINATE,
                    "geodetic latitude " + Latitude + " rad (" + (Latitude * ProjectionMath.RTD)
                            + " deg) is outside +/-90 deg by more than the 0.1% rounding "
                            + "allowance, so it cannot be converted to geocentric coordinates");
        }

        if (Longitude > ProjectionMath.PI) Longitude -= (2 * ProjectionMath.PI);
        Sin_Lat = Math.sin(Latitude);
        Cos_Lat = Math.cos(Latitude);
        Sin2_Lat = Sin_Lat * Sin_Lat;
        Rn = a / (Math.sqrt(1.0e0 - e2 * Sin2_Lat));
        X = (Rn + Height) * Cos_Lat * Math.cos(Longitude);
        Y = (Rn + Height) * Cos_Lat * Math.sin(Longitude);
        Z = ((Rn * (1 - e2)) + Height) * Sin_Lat;

        p.x = X;
        p.y = Y;
        p.z = Z;
    }

    public void convertGeocentricToGeodetic(ProjCoordinate p) {
        convertGeocentricToGeodeticIter(p);
    }

    public void convertGeocentricToGeodeticIter(ProjCoordinate p) {
      /* local defintions and variables */
  	/* end-criterium of loop, accuracy of sin(Latitude) */
        double genau = 1.E-12;
        double genau2 = (genau * genau);
        int maxiter = 30;

        double P;        /* distance between semi-minor axis and location */
        double RR;       /* distance between center and location */
        double CT;       /* sin of geocentric latitude */
        double ST;       /* cos of geocentric latitude */
        double RX;
        double RK;
        double RN;       /* Earth radius at location */
        double CPHI0;    /* cos of start or old geodetic latitude in iterations */
        double SPHI0;    /* sin of start or old geodetic latitude in iterations */
        double CPHI;     /* cos of searched geodetic latitude */
        double SPHI;     /* sin of searched geodetic latitude */
        double SDPHI;    /* end-criterium: addition-theorem of sin(Latitude(iter)-Latitude(iter-1)) */
        int iter;        /* # of continous iteration, max. 30 is always enough (s.a.) */

        double X = p.x;
        double Y = p.y;
        double Z = p.hasValidZOrdinate() ? p.z : 0;   //Z value not always supplied
        double Longitude;
        double Latitude;
        double Height;

        P = Math.sqrt(X * X + Y * Y);
        RR = Math.sqrt(X * X + Y * Y + Z * Z);

        // The centre of mass, and only the centre of mass. Below, CT = Z / RR and ST = P / RR,
        // so RR == 0 is exactly the condition under which this algorithm divides zero by zero
        // and can compute nothing at all; it is not a tolerance.
        //
        // 1.4.3 answered the centre of mass with Longitude = 0, Latitude = +90 deg,
        // Height = -b. That is a fully formed fiction: three finite, in-range, entirely
        // plausible ordinates for a point that has no geodetic latitude or longitude at
        // all -- every meridian and every parallel passes through it. A caller cannot
        // tell it from a real polar coordinate at depth b. Upstream keeps the fiction
        // (9.8.1:src/conversions/cart.cpp reaches x_phi <= 0 and clamps to +90 deg; the row
        // `accept 0 0 0 / expect 0 90 -6356752.314140356` in more_builtins.gie pins it), so this
        // is a knowing divergence: the no-sentinels rule outranks parity on one degenerate row.
        //
        // The guard used to be `RR / a < genau`, i.e. any point within 6.4 micrometres of the
        // centre, which rejected (0, 0, +/-1e-6) -- a point whose latitude (+/-90 deg) and
        // height (1e-6 - b) are both perfectly well determined -- and rejected it with a message
        // that called it "the centre of mass", which it is not. Upstream's expected values are
        // pinned in more_builtins.gie as `accept 0 0 1e-6 / expect 0 90 -6356752.314139356` and
        // `accept 0 0 -1e-6 / expect 0 -90 -6356752.314139356`. Those two rows are on
        // `+proj=cart`, which Registry does not map yet, so today they are a reference for the
        // right answer rather than rows this change turns green; they become live when `cart` is
        // registered. The path IS reachable today through `+proj=geocent`, which is registered
        // and whose inverse goes through this method: measured, `+proj=geocent +ellps=GRS80`
        // inverse of (0, 0, 1e-6) went from an exception to (0 deg, 90 deg, -6356752.31) here.
        if (RR == 0.0) {
            throw new CrsTransformException(ErrorCause.INVALID_COORDINATE,
                    "geocentric (0, 0, 0) is the centre of mass of the ellipsoid and has no "
                            + "geodetic latitude or longitude: every meridian and every parallel "
                            + "passes through it, so there is no coordinate to return");
        }

  	/*  ellipsoidal (geodetic) longitude
  	 *  interval: -PI < Longitude <= +PI
  	 *
  	 * Unconditional, exactly as 9.8.1:src/conversions/cart.cpp:224 does it. There used to be a
  	 * special case here -- `if (P / a < genau) { Longitude = 0.0; }`, guarded on P being within
  	 * 6.4 micrometres of the Z axis -- and it was a fabricated meridian. Measured on
  	 * Clarke 1866: the round trip of (lon = 17 deg, lat = 90 deg, h = 0) came back as
  	 * (lon = 0 deg, lat = 90 deg, h = 0), and (X, Y, Z) = (1e-9, 2e-9, b), whose longitude is
  	 * 63.43 deg, came back as 0 deg. Latitude and height were right in both; only the longitude
  	 * was invented, and it was invented as Greenwich -- a specific, entirely plausible meridian
  	 * that no finiteness or range check can distinguish from a real answer.
  	 *
  	 * atan2 needs no special case. It is well defined for every (X, Y) this line can now see,
  	 * and for a point bit-exactly on the axis IEEE 754 gives atan2(+/-0, +0) = +/-0 -- the
  	 * conventional pole longitude, and the value more_builtins.gie pins for
  	 * `accept 0 0 6356752.314140347 / expect 0 90 0`. (0, 0, 0), where atan2 would have had to
  	 * choose arbitrarily, has already been refused above. */
        Longitude = Math.atan2(Y, X);

  	/* --------------------------------------------------------------
  	 * Following iterative algorithm was developped by
  	 * "Institut fur Erdmessung", University of Hannover, July 1988.
  	 * Internet: www.ife.uni-hannover.de
  	 * Iterative computation of CPHI,SPHI and Height.
  	 * Iteration of CPHI and SPHI to 10**-12 radian resp.
  	 * 2*10**-7 arcsec.
  	 * --------------------------------------------------------------
  	 */
        CT = Z / RR;
        ST = P / RR;
        RX = 1.0 / Math.sqrt(1.0 - this.e2 * (2.0 - this.e2) * ST * ST);
        CPHI0 = ST * (1.0 - this.e2) * RX;
        SPHI0 = CT * RX;
        iter = 0;

  	/* loop to find sin(Latitude) resp. Latitude
  	 * until |sin(Latitude(iter)-Latitude(iter-1))| < genau */
        do {
            iter++;
            RN = this.a / Math.sqrt(1.0 - this.e2 * SPHI0 * SPHI0);

  		/*  ellipsoidal (geodetic) height */
            Height = P * CPHI0 + Z * SPHI0 - RN * (1.0 - this.e2 * SPHI0 * SPHI0);

            RK = this.e2 * RN / (RN + Height);
            RX = 1.0 / Math.sqrt(1.0 - RK * (2.0 - RK) * ST * ST);
            CPHI = ST * (1.0 - RK) * RX;
            SPHI = CT * RX;
            SDPHI = SPHI * CPHI0 - CPHI * SPHI0;
            CPHI0 = CPHI;
            SPHI0 = SPHI;
        }
        while (SDPHI * SDPHI > genau2 && iter < maxiter);

        // The loop has two exits and 1.4.3 treated them identically: converged, and "ran out of
        // iterations". Only the first one licenses the latitude below. The Hannover algorithm's
        // own comment says "max. 30 is always enough", and for a well-formed ellipsoid it is --
        // which is exactly why reaching 30 means the inputs are not well formed, and why
        // continuing to compute a latitude from them produces a plausible wrong answer.
        if (SDPHI * SDPHI > genau2) {
            throw new ConvergenceFailureException(
                    "geocentric to geodetic did not converge for (" + X + ", " + Y + ", " + Z
                            + "): |sin(dphi)| = " + Math.abs(SDPHI) + " still exceeds " + genau
                            + " after " + maxiter + " iterations on a = " + this.a + ", e2 = "
                            + this.e2);
        }

  	/*      ellipsoidal (geodetic) latitude */
        Latitude = Math.atan(SPHI / Math.abs(CPHI));

        p.x = Longitude;
        p.y = Latitude;
        p.z = Height;
    }

    //TODO: port non-iterative algorithm????
}
