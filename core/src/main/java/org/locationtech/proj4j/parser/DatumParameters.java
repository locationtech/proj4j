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
package org.locationtech.proj4j.parser;

import static org.locationtech.proj4j.util.ProjectionMath.isIdentity;

import java.util.List;

import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.InvalidValueException;
import org.locationtech.proj4j.datum.Datum;
import org.locationtech.proj4j.datum.Ellipsoid;
import org.locationtech.proj4j.datum.Grid;

/**
 * Contains the parsed/computed parameter values
 * which are used to create
 * the datum and ellipsoid for a {@link CoordinateReferenceSystem}.
 * <p>
 * The derivation and validation rules implemented here follow PROJ 9.8.1
 * {@code src/ell_set.cpp} ({@code ellps_size}, {@code ellps_shape},
 * {@code ellps_spherification} and {@code pj_calc_ellipsoid_params}):
 * <ul>
 * <li>{@code +R} declares a sphere and short-circuits every other size,
 *     shape and spherification parameter.
 * <li>Exactly <i>one</i> shape parameter takes effect, chosen by the fixed
 *     order {@code rf, f, es, e, b} — first present wins. Selection is done by
 *     the caller ({@link Proj4Parser}); this class holds the formula and the
 *     range check for each.
 * <li>{@code rf} implies {@code f = 1/rf} then {@code es = 2f - f*f};
 *     {@code f} implies {@code es = 2f - f*f}; {@code b} is a two-step
 *     derivation via {@code f = (a - b)/a}.
 * <li>Ranges: {@code a > 0}, {@code rf > 0}, {@code f >= 0}, {@code es} and
 *     {@code e} in {@code [0,1)}, {@code b > 0}, and finally
 *     {@code f} in {@code [0,1)} with a NaN-safe {@code !(es >= 0)} guard.
 * <li>Spherification sets {@code es = e = f = 0} and {@code b = a}.
 * </ul>
 *
 * @author Martin Davis
 */
public class DatumParameters {
    // TODO: check for inconsistent datum and ellipsoid (some PROJ4 cs specify both - not sure why)

    private final static double SIXTH = .1666666666666666667; /* 1/6 */
    private final static double RA4 = .04722222222222222222; /* 17/360 */
    private final static double RA6 = .02215608465608465608; /* 67/3024 */
    private final static double RV4 = .06944444444444444444; /* 5/72 */
    private final static double RV6 = .04243827160493827160; /* 55/1296 */

    private static final double HALF_PI = Math.PI / 2.0;

    /**
     * PROJ's implicit ellipsoid, as a {@link Datum}.
     * <p>
     * {@code init.cpp:317-362} appends {@code pj_mkparam("ellps=GRS80")} to the end of
     * the parameter list unless {@code +no_defs} is given, {@code +proj} is missing or
     * is {@code pipeline}, or any of {@code +datum +ellps +a +b +rf +f +e +es} is
     * present. <b>GRS80, not WGS84.</b> Proj4J returned {@code Ellipsoid.WGS84}, and the
     * two differ only in the inverse flattening — 298.257222101 against 298.257223563 —
     * so the divergence hides at every ordinary tolerance and surfaces at
     * sub-micrometre ones: it is the sole reason {@code builtins.gie:7767}
     * ({@code +proj=utm +zone=32}, tolerance 0.001 mm) missed by 124 &micro;m of
     * northing at latitude 56, where an explicit {@code +ellps=GRS80} lands 3.3 nm out.
     * <p>
     * The <i>datum</i> is deliberately left equivalent to what it was. This constant's
     * transform is null and its ellipsoid is GRS80, so {@link Datum#getTransformType()}
     * returns {@code TYPE_WGS84} exactly as {@code Datum.WGS84} does, and
     * {@link Datum#isEqual} still finds the two equal — GRS80 and WGS84 differ in
     * {@code es} by 3.3e-11, inside the 5e-11
     * {@link Datum#ELLIPSOID_E2_TOLERANCE}. So no datum shift appears or disappears
     * because of this; only the ellipsoid the projection formula runs on changes.
     * <p>
     * A {@code static final} rather than a fresh instance per parse, so that the
     * identity of the default datum stays stable across parses the way
     * {@code Datum.WGS84}'s did.
     * <p>
     * <b>Not implemented here:</b> PROJ <i>errors</i> ("Must specify ellipsoid or
     * sphere") when the implicit append is suppressed and nothing else supplies an
     * ellipsoid — {@code +proj=merc +no_defs}. Proj4J still defaults instead, which is
     * the same shape of divergence it had before, just with the right ellipsoid.
     */
    private static final Datum DEFAULT_DATUM = new Datum("GRS80", (double[]) null, null,
            Ellipsoid.GRS80, "Unknown datum based upon the GRS 1980 ellipsoid");

    private Datum datum = null;
    private double[] datumTransform = null;
    private List<Grid> grids = null;

    private Ellipsoid ellipsoid;
    private double a = Double.NaN;
    private double es = Double.NaN;

    /**
     * The flattening exactly as it was declared (or as it was derived from
     * {@code +rf}/{@code +b}), kept separately from {@code es} because
     * {@code es = 2f - f*f} is not injective: {@code +f=2} and {@code +f=0}
     * both give {@code es = 0}, yet PROJ rejects the former in
     * {@code pj_calc_ellipsoid_params}. NaN when no flattening was declared.
     */
    private double f = Double.NaN;

    /**
     * True once the shape has been forced to a sphere, either by {@code +R} or
     * by a spherification parameter. Lets {@link Proj4Parser} give the declared
     * sphere to the projection rather than a named datum's ellipsoid.
     */
    private boolean sphere = false;

    public DatumParameters() {
        // Default datum is WGS84
//    setDatum(Datum.WGS84);
    }

    public Datum getDatum() {
        if (datum != null) {
            // Never mutate the shared, well-known Datum singletons (Datum.NAD27
            // and friends).  When grids were given alongside +datum=, derive a
            // new Datum instead.  PROJ gives +nadgrids precedence over
            // +towgs84 (datum_set.cpp), so the parameter transform is dropped.
            if (grids == null)
                return datum;
            return new Datum(datum.getCode(), null, grids, datum.getEllipsoid(), datum.getName());
        }

        if (grids == null) {
            // Nothing was declared at all: PROJ appends "ellps=GRS80" here, not WGS84.
            // See DEFAULT_DATUM.
            if (ellipsoid == null && !isDefinedExplicitly()) {
                return DEFAULT_DATUM;
            }
            // Check for WGS84 datum parameters
            if (Ellipsoid.WGS84.equals(ellipsoid) && (datumTransform == null || isIdentity(datumTransform)))
                return Datum.WGS84;
        }

        // otherwise, return a custom datum with the specified ellipsoid.
        // The transform array is copied because Datum's constructor rescales it
        // in place; sharing it would double-scale on a second call.
        return new Datum("User", copyOfTransform(), grids, getEllipsoid(), "User-defined");
    }

    private double[] copyOfTransform() {
        if (datumTransform == null)
            return null;
        double[] copy = new double[datumTransform.length];
        System.arraycopy(datumTransform, 0, copy, 0, datumTransform.length);
        return copy;
    }

    private boolean isDefinedExplicitly() {
        return !(Double.isNaN(a) || Double.isNaN(es));
    }

    public Ellipsoid getEllipsoid() {
        if (ellipsoid != null)
            return ellipsoid;
        if (Double.isNaN(a))
            // PROJ's implicit ellipsoid is GRS80; see DEFAULT_DATUM.
            return Ellipsoid.GRS80;
        return new Ellipsoid("user", a, Double.isNaN(es) ? 0.0 : es, "User-defined");
    }

    public void setDatumTransform(double[] datumTransform) {
        this.datumTransform = datumTransform;
        // force new Datum to be created
        datum = null;
    }

    public void setDatum(Datum datum) {
        this.datum = datum;
    }

    public void setEllipsoid(Ellipsoid ellipsoid) {
        this.ellipsoid = ellipsoid;
        es = ellipsoid.eccentricity2;
        a = ellipsoid.equatorRadius;
        f = Double.NaN;
        sphere = false;
    }

    public void setGrids(List<Grid> grids) {
        this.grids = grids;
    }

    /**
     * Sets the semi-major axis. PROJ {@code ellps_size}: {@code a <= 0} or
     * {@code a == HUGE_VAL} is an error.
     */
    public void setA(double a) {
        checkSize(a, "a");
        ellipsoid = null;  // force user-defined ellipsoid
        this.a = a;
    }

    /**
     * Declares a sphere of the given radius. Per {@code ell_set.cpp:92-100}
     * this overrules {@code +ellps} and makes every shape and spherification
     * parameter irrelevant, so the caller must stop parsing the ellipsoid here.
     */
    public void setR(double r) {
        checkSize(r, "R");
        ellipsoid = null;  // force user-defined ellipsoid
        this.a = r;
        this.es = 0.0;
        this.f = 0.0;
        this.sphere = true;
    }

    /**
     * Sets the semi-minor axis, deriving {@code es} the way PROJ does: through
     * the flattening, <i>not</i> as {@code 1 - b*b/(a*a)}.
     */
    public void setB(double b) {
        requireMajorAxis();
        if (!(b > 0) || Double.isInfinite(b))
            throw new InvalidValueException("Invalid value for b. Should be > 0");
        ellipsoid = null;  // force user-defined ellipsoid
        if (b == a) {
            this.f = 0.0;
            this.es = 0.0;
            return;
        }
        this.f = (a - b) / a;
        this.es = 2 * f - f * f;
    }

    public void setES(double es) {
        if (Double.isNaN(es) || es < 0 || es >= 1 || Double.isInfinite(es))
            throw new InvalidValueException("Invalid value for es. Should be in [0,1[ range");
        ellipsoid = null;  // force user-defined ellipsoid
        this.es = es;
        this.f = Double.NaN;
    }

    /**
     * Sets the eccentricity. Absent from PROJ4J before, present in PROJ as the
     * fourth shape parameter.
     */
    public void setE(double e) {
        if (Double.isNaN(e) || e < 0 || e >= 1 || Double.isInfinite(e))
            throw new InvalidValueException("Invalid value for e. Should be in [0,1[ range");
        ellipsoid = null;  // force user-defined ellipsoid
        this.es = e * e;
        this.f = Double.NaN;
    }

    /**
     * Sets the <i>inverse</i> flattening: {@code f = 1/rf}, then
     * {@code es = 2f - f*f}.
     */
    public void setRF(double rf) {
        if (Double.isNaN(rf) || rf <= 0 || Double.isInfinite(rf))
            throw new InvalidValueException("Invalid value for rf. Should be > 0");
        ellipsoid = null;  // force user-defined ellipsoid
        this.f = 1.0 / rf;
        this.es = 2 * f - f * f;
    }

    /**
     * Sets the flattening: {@code es = 2f - f*f}.
     */
    public void setF(double f) {
        if (Double.isNaN(f) || f < 0 || Double.isInfinite(f))
            throw new InvalidValueException("Invalid value for f. Should be >= 0");
        ellipsoid = null;  // force user-defined ellipsoid
        this.f = f;
        this.es = 2 * f - f * f;
    }

    /**
     * Seeds the size and shape from the named datum's ellipsoid, or from GRS80
     * when no datum was named, if no size parameter has been given.
     * <p>
     * This stands in for PROJ's implicit {@code +ellps=GRS80} append
     * ({@code init.cpp:317-360}), which is suppressed by {@code +a}, {@code +b},
     * {@code +rf}, {@code +f}, {@code +e}, {@code +es}, {@code +ellps} and
     * {@code +datum} but <i>not</i> by the spherification keys - so PROJ still
     * has an ellipsoid to spherify for e.g. a bare {@code +proj=merc +R_A}.
     */
    public void seedDefaultSize() {
        if (!Double.isNaN(a))
            return;
        Ellipsoid seed = datum != null ? datum.getEllipsoid() : Ellipsoid.GRS80;
        setEllipsoid(seed);
    }

    /**
     * Seeds the size and shape from the named datum's ellipsoid only.
     * <p>
     * {@code +datum=} makes PROJ append {@code ellps=<id>}
     * ({@code datum_set.cpp}), which supplies a semi-major axis before
     * {@code ellps_size} runs. A shape parameter on its own does <i>not</i>:
     * every shape key suppresses the implicit {@code +ellps=GRS80}, so
     * {@code +proj=merc +rf=297} really is "Major axis not given" in PROJ.
     */
    public void seedSizeFromDatum() {
        if (!Double.isNaN(a) || datum == null)
            return;
        setEllipsoid(datum.getEllipsoid());
    }

    /**
     * @throws InvalidValueException if no semi-major axis has been supplied, as
     *                               PROJ's {@code ellps_size} does before any
     *                               shape parameter is looked at
     */
    public void requireSize() {
        requireMajorAxis();
    }

    /**
     * Forces the ellipsoid to be a sphere with no shape at all when no shape
     * parameter was given, mirroring {@code ellps_shape}'s
     * "not giving a shape parameter means selecting a sphere" branch.
     */
    public void setSphericalShape() {
        requireMajorAxis();
        ellipsoid = null;  // force user-defined ellipsoid
        this.es = 0.0;
        this.f = 0.0;
    }

    /* ------------------------------------------------------------------ */
    /* Spherification (ell_set.cpp ellps_spherification)                  */
    /* ------------------------------------------------------------------ */

    /** {@code +R_A} — sphere with the same surface area as the ellipsoid. */
    public void setR_A() {
        requireMajorAxis();
        spherify(a * (1. - es * (SIXTH + es * (RA4 + es * RA6))));
    }

    /** {@code +R_V} — sphere with the same volume as the ellipsoid. */
    public void setR_V() {
        requireMajorAxis();
        spherify(a * (1. - es * (SIXTH + es * (RV4 + es * RV6))));
    }

    /** {@code +R_a} — sphere with R = (a + b)/2. */
    public void setR_a() {
        requireMajorAxis();
        spherify((a + getB()) / 2);
    }

    /** {@code +R_g} — sphere with R = sqrt(a*b). */
    public void setR_g() {
        requireMajorAxis();
        spherify(Math.sqrt(a * getB()));
    }

    /** {@code +R_h} — sphere with R = 2*a*b/(a + b). */
    public void setR_h() {
        requireMajorAxis();
        double b = getB();
        if (a + b == 0)
            throw new InvalidValueException("Invalid or missing major axis");
        spherify((2 * a * b) / (a + b));
    }

    /**
     * {@code +R_lat_a=phi} — sphere with R the arithmetic mean of the
     * ellipsoid's radii of curvature at the given latitude.
     *
     * @param phi latitude in radians
     */
    public void setR_lat_a(double phi) {
        requireMajorAxis();
        double t = latitudeFactor(phi, "lat_a");
        spherify(a * ((1. - es + t) / (2 * t * Math.sqrt(t))));
    }

    /**
     * {@code +R_lat_g=phi} — sphere with R the geometric mean of the
     * ellipsoid's radii of curvature at the given latitude.
     *
     * @param phi latitude in radians
     */
    public void setR_lat_g(double phi) {
        requireMajorAxis();
        double t = latitudeFactor(phi, "lat_g");
        spherify(a * (Math.sqrt(1 - es) / t));
    }

    /**
     * {@code +R_C} — sphere with the radius of the conformal sphere at
     * {@code phi0}, per IOGP Publication 373-7-2. Added in PROJ 9.3.0.
     *
     * @param phi0 latitude of origin in radians
     */
    public void setR_C(double phi0) {
        requireMajorAxis();
        double t = Math.sin(phi0);
        t = 1 - es * t * t;
        if (t == 0.)
            throw new InvalidValueException("Invalid eccentricity");
        spherify(a * (Math.sqrt(1 - es) / t));
    }

    private double latitudeFactor(double phi, String name) {
        if (!(Math.abs(phi) <= HALF_PI))
            throw new InvalidValueException(
                    "Invalid value for " + name + ". |" + name + "| should be <= 90 degrees");
        double t = Math.sin(phi);
        t = 1 - es * t * t;
        if (t == 0.)
            throw new InvalidValueException("Invalid eccentricity");
        return t;
    }

    private void spherify(double newA) {
        if (!(newA > 0.) || Double.isInfinite(newA))
            throw new InvalidValueException("Invalid or missing major axis");
        ellipsoid = null;  // force user-defined ellipsoid
        a = newA;
        es = 0.;
        f = 0.;
        sphere = true;
    }

    /* ------------------------------------------------------------------ */

    /**
     * Mirrors the error branches of PROJ's {@code pj_calc_ellipsoid_params}:
     * the NaN-safe {@code !(es >= 0)} guard, the requirement that the
     * flattening lies in {@code [0,1)}, and {@code 1 - es != 0}.
     *
     * @throws InvalidValueException if the derived ellipsoid is not physically
     *                               realisable
     */
    public void validateEllipsoid() {
        if (Double.isNaN(a) && Double.isNaN(es)) {
            // Nothing was declared; the default datum applies.
            return;
        }
        if (Double.isNaN(es))
            return;
        // Written this way to catch NaN, as ell_set.cpp does
        if (!(es >= 0))
            throw new InvalidValueException("Invalid eccentricity");
        double flattening = Double.isNaN(f) ? 1 - Math.sqrt(1 - es) : f;
        if (!(flattening >= 0.0 && flattening < 1.0))
            throw new InvalidValueException("Invalid eccentricity");
        if (1. - es == 0.)
            throw new InvalidValueException("Invalid eccentricity");
    }

    private void requireMajorAxis() {
        if (Double.isNaN(a))
            throw new InvalidValueException("Major axis not given");
    }

    private void checkSize(double value, String key) {
        if (Double.isNaN(value) || value <= 0 || Double.isInfinite(value))
            throw new InvalidValueException("Invalid value for major axis (+" + key + ")");
    }

    public double getA() {
        return a;
    }

    public double getES() {
        return es;
    }

    /**
     * @return the semi-minor axis derived from the current size and shape
     */
    public double getB() {
        return a * Math.sqrt(1. - es);
    }

    /**
     * @return true when the shape has been forced to a sphere by {@code +R} or
     *         by a spherification parameter
     */
    public boolean isSphere() {
        return sphere;
    }

    /**
     * @return true when a size parameter ({@code +a}, {@code +R}) or
     *         {@code +ellps} has supplied a semi-major axis
     */
    public boolean isSizeGiven() {
        return !Double.isNaN(a);
    }

    public List<Grid> getGrids() {
        return grids;
    }
}
