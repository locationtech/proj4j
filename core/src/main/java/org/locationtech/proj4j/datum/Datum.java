/*******************************************************************************
 * Copyright 2006, 2017 Jerry Huxtable, Martin Davis
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

import static org.locationtech.proj4j.util.ProjectionMath.MILLION;
import static org.locationtech.proj4j.util.ProjectionMath.SECONDS_TO_RAD;
import static org.locationtech.proj4j.util.ProjectionMath.isIdentity;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.resource.ChainedResourceResolver;
import org.locationtech.proj4j.resource.ResourceResolvers;


/**
 * A class representing a geodetic datum.
 * <p>
 * A geodetic datum consists of a set of reference points on or in the Earth,
 * and a reference {@link Ellipsoid} giving an approximation
 * to the true shape of the geoid.
 * <p>
 * In order to transform between two geodetic points specified
 * on different datums, it is necessary to transform between the
 * two datums.  There are various ways in which this
 * datum conversion may be specified:
 * <ul>
 * <li>A 3-parameter conversion
 * <li>A 7-parameter conversion
 * <li>A grid-shift conversion
 * </ul>
 * In order to be able to transform between any two datums,
 * the parameter-based transforms are provided as a transform to
 * the common WGS84 datum.  The WGS transforms of two arbitrary datum transforms can
 * be concatenated to provide a transform between the two datums.
 * <p>
 * Notable datums in common use include {@link #NAD83} and {@link #WGS84}.
 */
// In proj.4 the datum information is a direct member of the PJ struct.
// The well-known datums are defined in pj_datums.c
public class Datum implements java.io.Serializable {

    /**
     * Pinned, not chosen: {@code -4123237894098833763L} is what
     * {@code ObjectStreamClass.lookup(Datum.class).getSerialVersionUID()} printed on the shipped
     * classpath immediately <em>before</em> {@link #nadgridsSpec} was added, and that field set
     * ({@code code}, {@code name}, {@code ellipsoid}, {@code transform}, {@code grids}) is
     * unchanged since 1.4.3.
     * <p>
     * The default UID is computed from every field that is not {@code private static} or
     * {@code private transient}, so adding one {@code private final String} would have changed it
     * and silently broken deserialisation across versions. {@link #resolvedGrids} is
     * {@code private transient} and so never participated.
     */
    private static final long serialVersionUID = -4123237894098833763L;

    public static final int TYPE_UNKNOWN = 0;
    public static final int TYPE_WGS84 = 1;
    public static final int TYPE_3PARAM = 2;
    public static final int TYPE_7PARAM = 3;
    public static final int TYPE_GRIDSHIFT = 4;

    private static final double[] DEFAULT_TRANSFORM = new double[]{0.0, 0.0, 0.0};

    public static final Datum WGS84 = new Datum("WGS84", 0, 0, 0, Ellipsoid.WGS84, "WGS84");
    public static final Datum GGRS87 = new Datum("GGRS87", -199.87, 74.79, 246.62, Ellipsoid.GRS80, "Greek_Geodetic_Reference_System_1987");
    public static final Datum NAD83 = new Datum("NAD83", 0, 0, 0, Ellipsoid.GRS80, "North_American_Datum_1983");
    /**
     * {@code 9.8.1:src/datums.cpp:44}:
     * {@code {"NAD27", "nadgrids=@conus,@alaska,@ntv2_0.gsb,@ntv1_can.dat", "clrk66", ...}}.
     * <p>
     * The grid list is <em>declared</em> here and <em>resolved</em> on demand; see
     * {@link #nadgridsSpec} for why the difference is load bearing. Every token carries PROJ's
     * {@code @} optional marker, so with none of the four files reachable this reports
     * {@link #TYPE_UNKNOWN} — NAD27 declares no Helmert fallback, unlike {@link #POTSDAM}.
     */
    public static final Datum NAD27;

    /**
     * {@code potsdam}, the only entry in PROJ's legacy table whose definition is
     * <em>two</em> strings, one of them commented out. Verbatim, 9.8.1
     * {@code src/datums.cpp:49-50}:
     * <pre>
     * {"potsdam", /*"towgs84=598.1,73.7,418.2,0.202,0.045,-2.455,6.7",*&#47;
     *  "nadgrids=@BETA2007.gsb", "bessel", "Potsdam Rauenberg 1950 DHDN"},
     * </pre>
     * Both are reproduced here — the grid as the declared shift, the Helmert as the
     * fallback — because that pair is what PROJ 9.8.1 <em>does</em>, measured rather than
     * inferred from the table. At (9, 50) into
     * {@code +proj=tmerc +lon_0=9 +x_0=3500000 +ellps=bessel}, from WGS84 lon/lat:
     * <table>
     * <caption>cs2cs 9.8.1</caption>
     * <tr><th>configuration</th><th>easting</th><th>northing</th></tr>
     * <tr><td>{@code +datum=potsdam}, {@code de_adv_BETA2007.tif} present</td>
     *     <td>3500074.920630</td><td>5540407.239515</td></tr>
     * <tr><td>{@code +datum=potsdam}, that grid hidden from {@code PROJ_DATA}</td>
     *     <td>3500074.525406</td><td>5540407.107230</td></tr>
     * <tr><td>{@code +ellps=bessel +towgs84=598.1,73.7,418.2,0.202,0.045,-2.455,6.7}</td>
     *     <td>3500074.525406</td><td>5540407.107230</td></tr>
     * <tr><td>{@code +ellps=bessel +nadgrids=@nosuch.gsb} (no shift at all)</td>
     *     <td>3500000.000000</td><td>5540279.541956</td></tr>
     * </table>
     * So {@code cs2cs +datum=potsdam} degrades grid &rarr; Helmert, <b>never</b> to
     * no-shift: PROJ's CRS layer resolves the name to EPSG datum DHDN and picks the best
     * available EPSG operation, which is <i>DHDN to WGS 84 (4)</i> = BETA2007 when the
     * grid is on hand and <i>DHDN to WGS 84 (2)</i> = exactly the commented-out Helmert
     * when it is not. Only a bare {@code +nadgrids=@...} with nothing behind it falls all
     * the way through to no shift (row 4 above; {@code @} is PROJ's optional marker,
     * {@code grids.cpp getListOfGridSets}).
     * <p>
     * Proj4J does not ship {@code BETA2007.gsb}, so this constant is {@link #TYPE_7PARAM}
     * today and reproduces row 3 — and therefore row 2 — to the last digit. It becomes
     * {@link #TYPE_GRIDSHIFT} and reproduces row 1 the moment the grid appears on
     * {@link Grid}'s search path, with no change here. Dropping the Helmert to follow the
     * uncommented string alone would have cost <b>74.921 m easting and 127.698 m
     * northing</b> at that point (row 4 against row 1), which is why it is kept.
     * <p>
     * <b>Which of the two takes effect is a function of the resolver chain and of nothing
     * else</b> — in particular not of when this class happened to load. See
     * {@link #nadgridsSpec}.
     */
    public static final Datum POTSDAM;

    public static final Datum CARTHAGE =
            // 9.8.1 src/datums.cpp:51-52:
            //   {"carthage", "towgs84=-263.0,6.0,431.0", "clrk80ign", "Carthage 1934 Tunisia"}
            // The ellipsoid is clrk80*ign* (a=6378249.2, rf=293.4660212936269), not clrk80
            // (a=6378249.145, rf=293.4663). Proj4J bound the latter, which is a different
            // ellipsoid by 55 mm of equatorial radius and shows up as 20 mm of northing at
            // Tunis -- see LegacyDatumTableTest.
            new Datum("carthage", -263.0, 6.0, 431.0, Ellipsoid.CLRK80IGN, "Carthage 1934 Tunisia");
    public static final Datum HERMANNSKOGEL = new Datum("hermannskogel", 577.326, 90.129, 463.919, 5.137, 1.474, 5.297, 2.4232, Ellipsoid.BESSEL, "Hermannskogel");
    public static final Datum IRE65 = new Datum("ire65", 482.530, -130.596, 564.557, -1.042, -0.214, -0.631, 8.15, Ellipsoid.MOD_AIRY, "Ireland 1965");
    public static final Datum NZGD49 = new Datum("nzgd49", 59.47, -5.04, 187.44, 0.47, -0.1, 1.024, -4.5993, Ellipsoid.INTERNATIONAL, "New Zealand Geodetic Datum 1949");
    /**
     * 9.8.1 {@code src/datums.cpp:59-60}:
     * <pre>
     * {"OSGB36", "towgs84=446.448,-125.157,542.060,0.1502,0.2470,0.8421,-20.4894",
     *  "airy", "Airy 1830"},
     * </pre>
     * Four of the seven were previously truncated here to {@code 542.06, 0.15, 0.247,
     * 0.842, -20.489} — which is EPSG:1314, <i>OSGB36 to WGS 84 (6)</i>, a real but
     * coarser transformation, so the values looked plausible. Restoring PROJ's moves the
     * answer by about 3 mm across Great Britain (largest observed 3.5 mm of easting at
     * 7.5&deg;W); it is not the 1.78 m one might read off a {@code cs2cs +datum=OSGB36}
     * comparison, because that promotes to the OSTN15 grid
     * ({@code uk_os_OSTN15_NTv2_OSGBtoETRS.tif}, <i>OSGB36 to WGS 84 (9)</i>) and the
     * 1.78 m is the grid-versus-Helmert residual. Measurements in
     * {@code datum/audit/LegacyDatumAreaOfUseTest}.
     */
    public static final Datum OSGB36 = new Datum("OSGB36", 446.448, -125.157, 542.060, 0.1502, 0.2470, 0.8421, -20.4894, Ellipsoid.AIRY, "Airy 1830");

    static {
        // No I/O here, deliberately. These two are the only entries in PROJ's legacy table
        // whose shift is a +nadgrids= list, and resolving it in a static initialiser is what
        // made getTransformType() depend on class-load order. See nadgridsSpec.
        NAD27 = declaring("NAD27", null,
                "@conus,@alaska,@ntv2_0.gsb,@ntv1_can.dat",
                Ellipsoid.CLARKE_1866, "North_American_Datum_1927");
        POTSDAM = declaring("potsdam",
                new double[]{598.1, 73.7, 418.2, 0.202, 0.045, -2.455, 6.7},
                "@BETA2007.gsb", Ellipsoid.BESSEL, "Potsdam Rauenberg 1950 DHDN");
    }

    private String code;
    private String name;
    private Ellipsoid ellipsoid;
    private double[] transform = DEFAULT_TRANSFORM;
    private List<Grid> grids = null;

    /**
     * The {@code +nadgrids=} list exactly as PROJ's table writes it, for the datums whose shift
     * <em>is</em> a grid list, or {@code null} for every datum built from an already-resolved
     * {@code List<Grid>}. Only {@link #NAD27} and {@link #POTSDAM} use it.
     * <p>
     * <b>The defect this exists to remove.</b> Both constants used to call
     * {@link Grid#fromNadGrids} from the static initialiser above, which snapshots whatever
     * {@link ResourceResolvers#resolver()} happened to return <em>at class-load time</em>. That
     * chain is mutable: {@link ResourceResolvers#addResolver} discards the memoised chain, so an
     * application that registers a {@link org.locationtech.proj4j.resource.DirectoryResourceResolver}
     * for its grid directory gets {@link #TYPE_GRIDSHIFT} if it does so before anything touches
     * {@code Datum}, and {@link #TYPE_7PARAM} if it does so after. Measured:
     * {@code DHDN_ETRS89.gie} scored <b>64/64</b> from a driver that built the grid bridge first
     * and <b>32/32</b> under surefire, where an unrelated earlier test had already loaded
     * {@code Datum} — same code, same grid file, same classpath, two different answers. A
     * transform type that varies with class-loading is worse than one that is merely wrong,
     * because it cannot be tested.
     * <p>
     * <b>What replaces it.</b> The list is resolved on demand by {@link #gridList()} and memoised
     * against the <em>identity of the resolver chain</em> it was resolved against. Every mutator
     * on {@link ResourceResolvers} nulls that memoised chain, so a fresh chain object is
     * precisely the signal that the configuration changed. The observable consequence:
     * <p>
     * <b>{@code getTransformType()} is a pure function of the current resolver chain.</b> Grid
     * reachable &rarr; {@link #TYPE_GRIDSHIFT}. Grid not reachable &rarr; the declared fallback,
     * which is {@link #TYPE_7PARAM} for {@code potsdam} (PROJ's measured grid&rarr;Helmert
     * degradation, see {@link #POTSDAM}) and {@link #TYPE_UNKNOWN} for {@code NAD27}, which
     * declares no Helmert. Class-load time does not appear in that function.
     * <p>
     * <b>This does not make the singletons mutable.</b> The memo is a cache of a derived value,
     * never a second source of truth: for a given chain it can only ever hold the one answer
     * {@link Grid#fromNadGrids} gives for {@link #nadgridsSpec}, so two threads racing to fill it
     * store equal contents and a lost update costs a repeated lookup rather than a different
     * coordinate. The declared state — code, name, ellipsoid, {@code towgs84}, spec — remains
     * final in effect and the constants remain {@code final}. {@link #setGrids} is refused
     * outright on a spec-backed datum, which closes the hole that let a parser bug call
     * {@code setGrids(null)} on {@link #NAD27} and destroy its grid list process-wide.
     */
    private final String nadgridsSpec;

    /**
     * Memo for {@link #nadgridsSpec}: {@code null} until first asked. {@code transient} because
     * a resolver chain is not serialisable and must not be — a deserialised {@link Datum} has to
     * re-derive against the chain of the JVM it lands in, not the one it was written in.
     */
    private transient volatile GridResolution resolvedGrids;

    /**
     * One resolver chain and what {@link #nadgridsSpec} resolved to against it. Immutable, so
     * publication through a {@code volatile} field is sufficient and no lock is held across grid
     * I/O.
     */
    private static final class GridResolution {
        private final ChainedResourceResolver chain;
        private final List<Grid> grids;

        GridResolution(ChainedResourceResolver chain, List<Grid> grids) {
            this.chain = chain;
            this.grids = grids;
        }
    }

    private Datum(String code, List<Grid> grids, Ellipsoid ellipsoid, String name) {
        this(code, (double[]) null, grids, ellipsoid, name);
    }

    /**
     * The declared-grid-list constructor, reached only through {@link #declaring} so that no
     * overload of the public constructor becomes ambiguous on a {@code null} third argument.
     */
    private Datum(String code, double[] transform, Ellipsoid ellipsoid, String name,
                  String nadgridsSpec) {
        this.code = code;
        this.name = name;
        this.ellipsoid = ellipsoid;
        this.grids = null;
        this.nadgridsSpec = nadgridsSpec;
        this.transform = scaleRotations(transform);
    }

    /**
     * Builds one of PROJ's two grid-shift table entries: a datum that carries its
     * {@code +nadgrids=} list as the string {@code datums.cpp} writes and resolves it on demand.
     *
     * @param code          PROJ's datum name
     * @param transform     the {@code towgs84} fallback, or {@code null} if the table declares none
     * @param nadgridsSpec  the {@code +nadgrids=} value, {@code @}-markers included
     * @param ellipsoid     the datum's ellipsoid
     * @param name          the human-readable name
     * @return the datum
     */
    private static Datum declaring(String code, double[] transform, String nadgridsSpec,
                                   Ellipsoid ellipsoid, String name) {
        return new Datum(code, transform, ellipsoid, name, nadgridsSpec);
    }

    public Datum(String code,
                 double deltaX, double deltaY, double deltaZ,
                 Ellipsoid ellipsoid,
                 String name) {
        this(code, new double[]{deltaX, deltaY, deltaZ}, null, ellipsoid, name);
    }

    public Datum(String code,
                 double deltaX, double deltaY, double deltaZ,
                 double rx, double ry, double rz, double mbf,
                 Ellipsoid ellipsoid,
                 String name) {
        this(code, new double[]{deltaX, deltaY, deltaZ, rx, ry, rz, mbf}, null, ellipsoid, name);
    }

    public Datum(String code,
                 double[] transform,
                 List<Grid> grids,
                 Ellipsoid ellipsoid,
                 String name) {
        this.code = code;
        this.name = name;
        this.ellipsoid = ellipsoid;
        this.grids = grids;
        this.nadgridsSpec = null;
        this.transform = scaleRotations(transform);
    }

    /**
     * Converts a 7-parameter {@code towgs84} in place from PROJ's on-the-wire units — arc
     * seconds for the rotations, ppm for the scale — to the radians and multiplier the arithmetic
     * in {@link #transformFromGeocentricToWgs84} expects. Extracted verbatim from the public
     * constructor when a second constructor appeared; the in-place write and the {@code length
     * &gt; 3} test (rather than {@code == 7}) are both preserved exactly as they were.
     *
     * @param transform the raw parameters, possibly {@code null}
     * @return the same array, rescaled
     */
    private static double[] scaleRotations(double[] transform) {
        if (transform != null && transform.length > 3) {
            transform[3] *= SECONDS_TO_RAD;
            transform[4] *= SECONDS_TO_RAD;
            transform[5] *= SECONDS_TO_RAD;
            transform[6] = transform[6] / MILLION + 1.;
        }
        return transform;
    }

    /**
     * The grid list this datum shifts through: the list it was constructed with, or — for the two
     * PROJ table entries whose shift is a {@code +nadgrids=} string — that string resolved against
     * the resolver chain in force <em>now</em>. Never {@code null} for a spec-backed datum; may be
     * {@code null} for any other, exactly as the {@code grids} field always could.
     * <p>
     * Read {@link #nadgridsSpec} for why this is not done once at class-load time. The short
     * version: it was, and the answer depended on load order.
     *
     * @return the effective grid list, possibly empty, possibly {@code null}
     */
    private List<Grid> gridList() {
        final String spec = nadgridsSpec;
        if (spec == null) {
            return grids;
        }
        // Identity, not equality: ResourceResolvers memoises exactly one chain object per
        // configuration and nulls it on every mutation, so "same object" is the cheapest correct
        // test for "same configuration". The chain also wraps each delegate in a
        // CachingResourceResolver, negative results included, so a given chain object's answer
        // for a given grid name cannot drift underneath the memo either.
        final ChainedResourceResolver chain = ResourceResolvers.resolver();
        final GridResolution memo = resolvedGrids;
        if (memo != null && memo.chain == chain) {
            return memo.grids;
        }
        List<Grid> resolved;
        try {
            resolved = Collections.unmodifiableList(Grid.fromNadGrids(spec));
        } catch (IOException e) {
            // Unreachable for both declared specs: Grid.fromNadGrids only rethrows for a token
            // *without* PROJ's @ optional marker, and every token in datums.cpp's two lists has
            // one. Memoised rather than retried all the same, because a value that depends on
            // which call happened to hit a transient I/O error is the very thing being removed.
            resolved = Collections.emptyList();
        }
        resolvedGrids = new GridResolution(chain, resolved);
        return resolved;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String toString() {
        return "[Datum-" + name + "]";
    }

    public Ellipsoid getEllipsoid() {
        return ellipsoid;
    }

    public double[] getTransformToWGS84() {
        return transform;
    }

    /**
     * This datum's shift mechanism, as one of the {@code TYPE_*} constants.
     * <p>
     * For {@link #NAD27} and {@link #POTSDAM} the answer depends on which of their declared grid
     * files are reachable, and is a pure function of the resolver chain in force when it is asked
     * — not of when this class loaded. That distinction is the whole of {@link #nadgridsSpec};
     * read it before assuming a stale answer can be cached across a resolver change.
     *
     * @return one of {@link #TYPE_UNKNOWN}, {@link #TYPE_WGS84}, {@link #TYPE_3PARAM},
     *         {@link #TYPE_7PARAM}, {@link #TYPE_GRIDSHIFT}
     */
    public int getTransformType() {
        List<Grid> grids = gridList();
        if (grids != null && grids.size() > 0) return TYPE_GRIDSHIFT;

       if (Ellipsoid.WGS84.isEqual(ellipsoid) || Ellipsoid.GRS80.isEqual(ellipsoid)) {
            if (transform == null) return TYPE_WGS84;
            if (isIdentity(transform)) return TYPE_WGS84;
        }

        if (transform == null) return TYPE_UNKNOWN;
        if (transform.length == 3) return TYPE_3PARAM;
        if (transform.length == 7) return TYPE_7PARAM;

        return TYPE_UNKNOWN;
    }

    public boolean hasTransformToWGS84() {
        int transformType = getTransformType();
        return transformType == TYPE_3PARAM || transformType == TYPE_7PARAM;
    }

    public static final double ELLIPSOID_E2_TOLERANCE = 0.000000000050;

    /**
     * Tests if this is equal to another {@link Datum}.
     * <p>
     * Datums are considered to be equal iff:
     * <ul>
     * <li>their transforms are equal
     * <li>OR their ellipsoids are (approximately) equal
     * </ul>
     *
     * @param datum
     * @return
     */
    public boolean isEqual(Datum datum) {
        // Reference identity first. The well-known datums are process-wide singletons, so
        // this is the common case, and on the TYPE_GRIDSHIFT branch below the fallback is
        // List.equals over Grid -- which descends into Arrays.equals over every node of a
        // loaded NTv2 table. Comparing a singleton with itself must not pay for that.
        // Behaviour is unchanged: every branch below already returns true for this == datum.
        if (this == datum) return true;

        // false if tranforms are not equal
        if (getTransformType() != datum.getTransformType()) {
            return false;
        }
        // false if ellipsoids are not (approximately) equal.
        // NOTE: the right-hand side used to read `ellipsoid.getEquatorRadius()` -- the *same*
        // ellipsoid -- so this condition was always false and the eccentricity check below was
        // dead code. Datums with differently sized ellipsoids therefore compared equal, which
        // short-circuits BasicCoordinateTransform.datumTransform into an input echo.
        if (ellipsoid.getEquatorRadius() != datum.ellipsoid.getEquatorRadius()) {
            if (Math.abs(ellipsoid.getEccentricitySquared()
                    - datum.ellipsoid.getEccentricitySquared()) > ELLIPSOID_E2_TOLERANCE)
                return false;
        }

        // false if transform parameters are not identical
        if (getTransformType() == TYPE_3PARAM || getTransformType() == TYPE_7PARAM) {
            for (int i = 0; i < transform.length; i++) {
                if (transform[i] != datum.transform[i])
                    return false;
            }
            return true;
        } else if (getTransformType() == TYPE_GRIDSHIFT) {
            List<Grid> mine = gridList();
            List<Grid> theirs = datum.gridList();
            // Second reference-identity short circuit, for the same reason as the first: List.equals
            // over Grid descends into Arrays.equals over every node of a loaded NTv2 table. Two
            // spec-backed datums resolved against the same chain hand back the same List instance,
            // and the Grid instances themselves come from GridCache, so this hits far more often
            // than it looks.
            if (mine == theirs) return true;
            return mine.equals(theirs);
        }
        return true; // datums are equal

    }

    public void transformFromGeocentricToWgs84(ProjCoordinate p) {
        if (transform.length == 3) {
            p.x += transform[0];
            p.y += transform[1];
            p.z += transform[2];

        } else if (transform.length == 7) {
            double Dx_BF = transform[0];
            double Dy_BF = transform[1];
            double Dz_BF = transform[2];
            double Rx_BF = transform[3];
            double Ry_BF = transform[4];
            double Rz_BF = transform[5];
            double M_BF = transform[6];

            double x_out = M_BF * (p.x - Rz_BF * p.y + Ry_BF * p.z) + Dx_BF;
            double y_out = M_BF * (Rz_BF * p.x + p.y - Rx_BF * p.z) + Dy_BF;
            double z_out = M_BF * (-Ry_BF * p.x + Rx_BF * p.y + p.z) + Dz_BF;

            p.x = x_out;
            p.y = y_out;
            p.z = z_out;
        }
    }

    public void transformToGeocentricFromWgs84(ProjCoordinate p) {
        if (transform.length == 3) {
            p.x -= transform[0];
            p.y -= transform[1];
            p.z -= transform[2];

        } else if (transform.length == 7) {
            double Dx_BF = transform[0];
            double Dy_BF = transform[1];
            double Dz_BF = transform[2];
            double Rx_BF = transform[3];
            double Ry_BF = transform[4];
            double Rz_BF = transform[5];
            double M_BF = transform[6];

            double x_tmp = (p.x - Dx_BF) / M_BF;
            double y_tmp = (p.y - Dy_BF) / M_BF;
            double z_tmp = (p.z - Dz_BF) / M_BF;

            p.x = x_tmp + Rz_BF * y_tmp - Ry_BF * z_tmp;
            p.y = -Rz_BF * x_tmp + y_tmp + Rx_BF * z_tmp;
            p.z = Ry_BF * x_tmp - Rx_BF * y_tmp + z_tmp;
        }
    }

    public void shift(ProjCoordinate xy) {
        Grid.shift(gridList(), false, xy);
    }

    public void inverseShift(ProjCoordinate xy) {
        Grid.shift(gridList(), true, xy);
    }

    /**
     * This datum's effective grid list, resolved against the resolver chain in force <em>now</em>,
     * as a {@code Grid[]} for a caller that will apply it to many points.
     *
     * <h4>Why this exists</h4>
     *
     * <p>{@link #shift} and {@link #inverseShift} call {@link #gridList()} <em>per point</em>. For
     * an ordinary datum that is one plain field read and free. For the two spec-backed singletons
     * — {@link #NAD27} and {@link #POTSDAM}, i.e. the datums for which a grid shift is the common
     * case — it is two {@code volatile} reads: {@link ResourceResolvers#resolver()}'s memoised
     * chain and this datum's {@link #resolvedGrids} memo. Each is an acquire load, so besides its
     * own cost it is a barrier the JIT may not hoist ordinary loads across, in the innermost loop
     * of a batch. {@code BasicCoordinateTransform}'s bulk path calls this once and keeps the
     * array, which takes the fence out of the loop; the single-point path is deliberately left
     * calling {@link #shift} per point, because it is kept byte-identical to 1.4.3.
     *
     * <p>Returns a fresh array each call — it is a snapshot, not a view, and the caller may hold
     * it. {@code null} for a datum with no grid list at all, which is what
     * {@code Grid.shift(Grid[], …)} treats as a no-op.
     *
     * @return the resolved grids, or {@code null} if this datum has none
     * @since 1.5.0
     */
    public Grid[] gridArray() {
        List<Grid> resolved = gridList();
        if (resolved == null) {
            return null;
        }
        return resolved.toArray(new Grid[0]);
    }

    /**
     * Replaces this datum's grid list.
     * <p>
     * <b>Refused on {@link #NAD27} and {@link #POTSDAM}</b>, the two process-wide singletons whose
     * grid list is declared rather than supplied. A parser bug once called {@code setGrids(null)}
     * on {@link #NAD27} while parsing {@code EPSG:4267} — a definition with no {@code +nadgrids}
     * token — which destroyed the shared grid list for the life of the JVM and flipped every
     * NAD27 transform in the process, including already-cached ones, to
     * {@link #TYPE_UNKNOWN}. Nothing in Proj4J calls this method; a caller that needs a different
     * grid list should construct a new {@link Datum} with
     * {@link #Datum(String, double[], List, Ellipsoid, String)} rather than edit a shared one.
     *
     * @param grids the new grid list
     * @throws UnsupportedOperationException if this datum's grid list comes from a declared
     *                                       {@code +nadgrids=} spec
     */
    public void setGrids(List<Grid> grids) {
        if (nadgridsSpec != null) {
            throw new UnsupportedOperationException("Datum " + code + " is a shared singleton whose"
                    + " grid list is declared as '" + nadgridsSpec + "' and resolved from the"
                    + " resource resolver chain; mutating it would change every transform in the"
                    + " JVM. Construct a new Datum instead.");
        }
        this.grids = grids;
    }
}