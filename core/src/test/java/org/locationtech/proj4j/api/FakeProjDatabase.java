/*
 * Copyright 2026, PROJ4J contributors
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
package org.locationtech.proj4j.api;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

import org.locationtech.proj4j.spi.DbCelestialBody;
import org.locationtech.proj4j.spi.DbConversion;
import org.locationtech.proj4j.spi.DbCoordinateSystem;
import org.locationtech.proj4j.spi.DbCrs;
import org.locationtech.proj4j.spi.DbCrsType;
import org.locationtech.proj4j.spi.DbDatum;
import org.locationtech.proj4j.spi.DbEllipsoid;
import org.locationtech.proj4j.spi.DbExtent;
import org.locationtech.proj4j.spi.DbGridAlternative;
import org.locationtech.proj4j.spi.DbObjectRef;
import org.locationtech.proj4j.spi.DbObjectType;
import org.locationtech.proj4j.spi.DbOperation;
import org.locationtech.proj4j.spi.DbPrimeMeridian;
import org.locationtech.proj4j.spi.DbSupersession;
import org.locationtech.proj4j.spi.DbUnit;
import org.locationtech.proj4j.spi.ProjDatabase;

/**
 * A {@link ProjDatabase} carrying a hand-transcribed slice of PROJ 9.8.1's real one.
 *
 * <h2>Why a fake at all, when the real thing exists</h2>
 *
 * <p>Because <b>ranking, {@link BallparkPolicy}, {@link GridPolicy}, {@link BestOperationPolicy} and
 * what to throw are this library's policy, and the database has none.</b> That split is the reason the
 * SPI returns rows in {@code (kind, authority, code)} order and never by accuracy, and the payoff is
 * exactly this: policy is testable against a few dozen transcribed rows in {@code core}, which has no
 * {@code proj4j-db} on its classpath and must keep working without one. The real index is exercised
 * end-to-end in {@code db/src/test}, where it lives.
 *
 * <h2>Every row here is real, and here is how to re-verify it</h2>
 *
 * <p>Not one number below was invented or copied from Proj4J's own output. Each was read out of the
 * Homebrew {@code proj 9.8.1} database and can be re-read:
 *
 * <pre>
 * sqlite3 /opt/homebrew/share/proj/proj.db \
 *   "select code,name,method_name,accuracy,grid_name,grid2_name from grid_transformation
 *     where source_crs_code='4267' and target_crs_code='4269';"
 * </pre>
 *
 * <p>which returns exactly the <b>nine</b> rows {@link #nad27ToNad83()} carries, and
 *
 * <pre>
 * projinfo -s EPSG:4267 -t EPSG:4269 --spatial-test intersects --summary
 * </pre>
 *
 * <p>which reports <b>10 candidate operations</b> &mdash; those nine plus the ballpark offset that is
 * synthesised rather than stored, and that has no row anywhere in the database.
 *
 * <p>Immutable after construction and safe for concurrent use, as the SPI requires.
 */
final class FakeProjDatabase implements ProjDatabase {

    private final String name;
    private final Map<String, String> metadata;
    private final Map<String, DbCrs> crss = new LinkedHashMap<String, DbCrs>();
    private final Map<String, DbCoordinateSystem> coordinateSystems =
            new LinkedHashMap<String, DbCoordinateSystem>();
    private final Map<String, DbDatum> datums = new LinkedHashMap<String, DbDatum>();
    private final Map<String, DbEllipsoid> ellipsoids = new LinkedHashMap<String, DbEllipsoid>();
    private final Map<String, DbPrimeMeridian> primeMeridians =
            new LinkedHashMap<String, DbPrimeMeridian>();
    private final Map<String, DbUnit> units = new LinkedHashMap<String, DbUnit>();
    private final Map<String, DbExtent> extents = new LinkedHashMap<String, DbExtent>();
    private final Map<String, DbGridAlternative> alternatives =
            new TreeMap<String, DbGridAlternative>();
    private final List<DbOperation> operations = new ArrayList<DbOperation>();
    private final Map<DbObjectRef, List<DbObjectRef>> usage =
            new LinkedHashMap<DbObjectRef, List<DbObjectRef>>();
    private final Map<DbObjectRef, List<DbSupersession>> supersessions =
            new LinkedHashMap<DbObjectRef, List<DbSupersession>>();

    private FakeProjDatabase(String name) {
        this.name = name;
        TreeMap<String, String> meta = new TreeMap<String, String>();
        meta.put("PROJ.VERSION", "9.8.1");
        meta.put("EPSG.VERSION", "v12.029");
        meta.put("EPSG.DATE", "2026-02-27");
        meta.put("DATABASE.LAYOUT.VERSION.MAJOR", "1");
        meta.put("DATABASE.LAYOUT.VERSION.MINOR", "6");
        this.metadata = Collections.unmodifiableMap(meta);
    }

    // ------------------------------------------------------------------ fixtures

    /**
     * {@code EPSG:4267} and {@code EPSG:4269} with all <b>nine</b> published grid transformations
     * between them, their real accuracies, their real grid names including every {@code grid2_name},
     * their real {@code grid_alternatives} rows and their real extents.
     *
     * <p>Note two things that are properties of the upstream data and not of this fixture:
     * <ul>
     * <li>{@code conus.los} has <b>no</b> {@code grid_alternatives} row. Only 1 of the 85 distinct
     *     {@code grid2_name}s upstream has one. That is why the second slot has to be satisfied through
     *     the first slot's file rather than resolved on its own.</li>
     * <li>{@code EPSG:1243} and {@code EPSG:8549} have extents that <b>cross the antimeridian</b>
     *     ({@code west = 167.65}, {@code east = -129.99}). Normalising that would turn an Alaskan
     *     extent into an almost-global one, which would then win the area tier of every ranking.</li>
     * </ul>
     */
    static FakeProjDatabase nad27ToNad83() {
        FakeProjDatabase db = new FakeProjDatabase("fake:nad27-to-nad83");
        db.commonObjects();

        // grid_transformation, verbatim. Columns: code | name | method | accuracy | grid | grid2
        db.gridOp("1241", "NAD27 to NAD83 (1)", "9613", "NADCON", 0.15,
                "conus.las", "conus.los", "4267", "4269", "2374");
        db.gridOp("1243", "NAD27 to NAD83 (2)", "9613", "NADCON", 0.5,
                "alaska.las", "alaska.los", "4267", "4269", "2373");
        db.gridOp("1312", "NAD27 to NAD83 (3)", "9614", "NTv1", 2.0,
                "NTv1_0.gsb", null, "4267", "4269", "4517");
        db.gridOp("1313", "NAD27 to NAD83 (4)", "9615", "NTv2", 1.5,
                "NTv2_0.gsb", null, "4267", "4269", "4517");
        db.gridOp("1462", "NAD27 to NAD83 (5)", "9614", "NTv1", 2.0,
                "GS2783v1.QUE", null, "4267", "4269", "1368");
        db.gridOp("1573", "NAD27 to NAD83 (6)", "9615", "NTv2", 1.5,
                "NA27NA83.GSB", null, "4267", "4269", "1368");
        db.gridOp("8549", "NAD27 to NAD83 (8)", "1074", "NADCON5 (2D)", 0.5,
                "nadcon5.nad27.nad83_1986.alaska.lat.trn.20160901.b",
                "nadcon5.nad27.nad83_1986.alaska.lon.trn.20160901.b", "4267", "4269", "1330");
        db.gridOp("8555", "NAD27 to NAD83 (7)", "1074", "NADCON5 (2D)", 0.15,
                "nadcon5.nad27.nad83_1986.conus.lat.trn.20160901.b",
                "nadcon5.nad27.nad83_1986.conus.lon.trn.20160901.b", "4267", "4269", "4516");
        db.gridOp("9111", "NAD27 to NAD83 (9)", "9615", "NTv2", 1.5,
                "SK27-83.gsb", null, "4267", "4269", "2375");

        // grid_alternatives, verbatim. conus.los and alaska.los deliberately absent: they have no row.
        db.alternative("conus.las", "us_noaa_conus.tif", "conus", "GTiff", "hgridshift");
        db.alternative("alaska.las", "us_noaa_alaska.tif", "alaska", "GTiff", "hgridshift");
        db.alternative("NTv1_0.gsb", "ca_nrc_ntv1_can.tif", "ntv1_can.dat", "GTiff", "hgridshift");
        db.alternative("NTv2_0.gsb", "ca_nrc_ntv2_0.tif", "ntv2_0.gsb", "GTiff", "hgridshift");
        db.alternative("NA27NA83.GSB", "ca_que_mern_na27na83.tif", "na27na83.gsb", "GTiff",
                "hgridshift");
        // NADCON 5 maps to the unified operator, which Proj4J does not implement. This one row is why
        // EPSG:8555 loses to EPSG:1241 despite being tied at 0.15 m and being what PROJ itself picks.
        db.alternative("nadcon5.nad27.nad83_1986.conus.lat.trn.20160901.b",
                "us_noaa_nadcon5_nad27_nad83_1986_conus.tif", null, "GTiff", "gridshift");
        db.alternative("nadcon5.nad27.nad83_1986.alaska.lat.trn.20160901.b",
                "us_noaa_nadcon5_nad27_nad83_1986_alaska.tif", null, "GTiff", "gridshift");
        return db;
    }

    /**
     * The same nine operations, but with <b>no {@code grid_alternatives} rows at all</b> &mdash; the
     * deployment in which the authority's own file names are the only ones there are.
     *
     * <p>This is what makes the {@code grid2_name} trap testable end to end: with no modern GeoTIFF to
     * collapse the pair onto, {@code EPSG:1241} needs {@code conus.las} <em>and</em> {@code conus.los}
     * as two separate files, and a message that named only the first would send a reader looking for
     * one file when two are required.
     */
    static FakeProjDatabase nad27ToNad83WithNoGridAlternatives() {
        FakeProjDatabase db = FakeProjDatabase.nad27ToNad83();
        db.alternatives.clear();
        return db;
    }

    /**
     * The nine operations with {@code EPSG:1241} removed, so the best executable candidate becomes
     * {@code EPSG:1243} "NAD27 to NAD83 (2)" at 0.5&nbsp;m &mdash; a real NADCON pair needing
     * {@code alaska.las} and {@code alaska.los}, whose files are <b>genuinely absent</b> from every
     * deployment in this repository.
     *
     * <p>Why {@code EPSG:1243} rather than {@code EPSG:1241} for the missing-grid case: core's test
     * classpath ships the real CTABLE V2 {@code conus} (264,424 B), which is exactly the file
     * {@code conus.las}'s {@code grid_alternatives} row points at through
     * {@code old_proj_grid_name}. So {@code EPSG:1241} is <em>satisfiable</em> here &mdash; that is
     * what {@link OperationSelectionTest#withADatabaseTheNad27PairSelectsEpsg1241At015m} proves
     * &mdash; and testing the unreachable case needs a pair whose files really are missing rather
     * than a fixture rigged to pretend. {@code alaska} has no CTABLE V2 form on this classpath and no
     * GeoTIFF either. The trap is identical: two authority slots, one of which a naive reader drops.
     */
    static FakeProjDatabase nad27ToNad83WithoutConus() {
        return FakeProjDatabase.nad27ToNad83().without("1241");
    }

    /**
     * The nine operations with both of the ones this classpath can satisfy removed &mdash;
     * {@code EPSG:1241} (whose {@code conus} is in core's test resources) and {@code EPSG:1312} (whose
     * {@code ntv1_can.dat} arrives with {@code proj4j-epsg}).
     *
     * <p>What is left is the deployment the consumer actually reported: real published operations, real
     * accuracies, and not one grid file present. The best <em>executable</em> candidate is then
     * {@code EPSG:1243} at 0.5&nbsp;m needing {@code alaska.las} and {@code alaska.los}, which is what
     * makes this the fixture for "does the message name both files".
     */
    static FakeProjDatabase nad27ToNad83WithNoReachableGrid() {
        return FakeProjDatabase.nad27ToNad83().without("1241").without("1312");
    }

    private FakeProjDatabase without(String code) {
        for (int i = operations.size() - 1; i >= 0; i--) {
            if (code.equals(operations.get(i).code())) {
                operations.remove(i);
            }
        }
        return this;
    }

    /**
     * {@code EPSG:4326} to {@code EPSG:9057}: two members of the <b>{@code EPSG:6326} datum
     * ensemble</b>, related by {@code PROJ:WGS84_TO_WGS84_G1762} at <b>2.0 m</b> &mdash; the ensemble
     * accuracy.
     *
     * <p>Verified with {@code projinfo -s EPSG:4326 -t EPSG:9057 --summary}, which reports exactly one
     * candidate at 2.0 m, and {@code -o PROJ}, which gives {@code +proj=noop}. That pairing is the
     * whole point: the operation is arithmetically a no-op, and it is <b>not ballpark</b>, because the
     * authority publishes a <em>bound</em> for it. Treating {@code EPSG:6326} as an ordinary datum is
     * how an ensemble-crossing pair loses that bound and becomes "accuracy unknown".
     */
    static FakeProjDatabase wgs84Ensemble() {
        FakeProjDatabase db = new FakeProjDatabase("fake:wgs84-ensemble");
        db.commonObjects();
        db.operations.add(new DbOperation(DbObjectType.HELMERT_TRANSFORMATION, "PROJ",
                "WGS84_TO_WGS84_G1762", "WGS 84 to WGS 84 (G1762)", "EPSG", "9603",
                "Geocentric translations (geog2D domain)", crsRef("4326"), crsRef("9057"), 2.0,
                null, null, null, null, null, false));
        db.usage.put(new DbObjectRef(DbObjectType.HELMERT_TRANSFORMATION, "PROJ",
                        "WGS84_TO_WGS84_G1762"),
                Collections.singletonList(new DbObjectRef(DbObjectType.EXTENT, "EPSG", "1262")));
        return db;
    }

    /**
     * {@code EPSG:4277} to {@code EPSG:4326}: {@code EPSG:7710} "OSGB36 to WGS 84 (9)", NTv2,
     * <b>1.0 m</b>, {@code OSTN15_NTv2_OSGBtoETRS.gsb}.
     *
     * <p>Reachable from a bare {@code +datum=OSGB36} through PROJ's own ten-entry {@code +datum=} table
     * ({@code 9.8.1:src/iso19111/io.cpp}), which is the point: <b>1,962 lines of the shipped legacy
     * dictionaries carry a {@code datum=}</b>, so that table is the bridge between them and the
     * authority database, not a corner case.
     */
    static FakeProjDatabase osgb36() {
        FakeProjDatabase db = new FakeProjDatabase("fake:osgb36");
        db.commonObjects();
        db.gridOp("7710", "OSGB36 to WGS 84 (9)", "9615", "NTv2", 1.0,
                "OSTN15_NTv2_OSGBtoETRS.gsb", null, "4277", "4326", "4390");
        db.alternative("OSTN15_NTv2_OSGBtoETRS.gsb", "uk_os_OSTN15_NTv2_OSGBtoETRS.tif",
                "OSTN15_NTv2_OSGBtoETRS.gsb", "GTiff", "hgridshift");
        return db;
    }

    /** A database that knows the CRSs but publishes no operation at all between them. */
    static FakeProjDatabase noOperations() {
        FakeProjDatabase db = new FakeProjDatabase("fake:no-operations");
        db.commonObjects();
        return db;
    }

    /**
     * The CRSs, coordinate systems, datums, ellipsoids, prime meridians, units and extents every
     * fixture shares. All verbatim from the shipped database.
     */
    private void commonObjects() {
        // EPSG:6422 is (Geodetic latitude north, Geodetic longitude east) -- which is exactly why
        // PROJ 6+ is latitude-first for EPSG:4326 and proj4j 1.4.3 is not.
        coordinateSystems.put("EPSG:6422", new DbCoordinateSystem("EPSG", "6422", "ellipsoidal", 2,
                Arrays.asList(
                        new org.locationtech.proj4j.spi.DbAxis("Geodetic latitude", "Lat", "north", 1,
                                unitRef("9122")),
                        new org.locationtech.proj4j.spi.DbAxis("Geodetic longitude", "Lon", "east", 2,
                                unitRef("9122")))));
        // EPSG:4400 is (Easting, Northing) and EPSG:4530 is (Northing, Easting) -- the pair that makes
        // an authority-northing-first projected CRS look like a 4,652 km error when it is honoured on
        // one side only.
        coordinateSystems.put("EPSG:4400", new DbCoordinateSystem("EPSG", "4400", "Cartesian", 2,
                Arrays.asList(
                        new org.locationtech.proj4j.spi.DbAxis("Easting", "E", "east", 1,
                                unitRef("9001")),
                        new org.locationtech.proj4j.spi.DbAxis("Northing", "N", "north", 2,
                                unitRef("9001")))));
        coordinateSystems.put("EPSG:4530", new DbCoordinateSystem("EPSG", "4530", "Cartesian", 2,
                Arrays.asList(
                        new org.locationtech.proj4j.spi.DbAxis("Northing", "X", "north", 1,
                                unitRef("9001")),
                        new org.locationtech.proj4j.spi.DbAxis("Easting", "Y", "east", 2,
                                unitRef("9001")))));

        crs("4267", "NAD27", "6267");
        crs("4269", "NAD83", "6269");
        crs("4277", "OSGB36", "6277");
        crs("4326", "WGS 84", "6326");
        crs("9057", "WGS 84 (G1762)", "1156");

        datum("6267", "North American Datum 1927", "7008", Double.NaN, null);
        datum("6269", "North American Datum 1983", "7019", Double.NaN, null);
        datum("6277", "OSGB36", "7001", Double.NaN, null);
        // The ensemble: 2.0 m, 8 members in authority sequence order.
        datum("6326", "World Geodetic System 1984 ensemble", "7030", 2.0,
                Arrays.asList(datumRef("1166"), datumRef("1152"), datumRef("1153"),
                        datumRef("1154"), datumRef("1155"), datumRef("1156"), datumRef("1309"),
                        datumRef("1383")));
        datum("1156", "World Geodetic System 1984 (G1762)", "7030", Double.NaN, null);

        ellipsoids.put("EPSG:7008", new DbEllipsoid("EPSG", "7008", "Clarke 1866", null,
                6378206.4, unitRef("9001"), Double.NaN, 6356583.8, false));
        ellipsoids.put("EPSG:7019", new DbEllipsoid("EPSG", "7019", "GRS 1980", null,
                6378137.0, unitRef("9001"), 298.257222101, Double.NaN, false));
        ellipsoids.put("EPSG:7001", new DbEllipsoid("EPSG", "7001", "Airy 1830", null,
                6377563.396, unitRef("9001"), 299.3249646, Double.NaN, false));
        ellipsoids.put("EPSG:7030", new DbEllipsoid("EPSG", "7030", "WGS 84", null,
                6378137.0, unitRef("9001"), 298.257223563, Double.NaN, false));

        primeMeridians.put("EPSG:8901",
                new DbPrimeMeridian("EPSG", "8901", "Greenwich", 0.0, unitRef("9102"), false));

        units.put("EPSG:9001", new DbUnit("EPSG", "9001", "metre", DbUnit.Type.LENGTH, 1.0, "m",
                false));
        units.put("EPSG:9102", new DbUnit("EPSG", "9102", "degree", DbUnit.Type.ANGLE,
                Math.PI / 180.0, "deg", false));
        units.put("EPSG:9122", new DbUnit("EPSG", "9122", "degree (supplier to define "
                + "representation)", DbUnit.Type.ANGLE, Math.PI / 180.0, "deg", false));

        extent("2374", "USA - CONUS including EEZ",
                "United States (USA) - CONUS including EEZ - onshore and offshore.",
                -129.17, 23.81, -65.69, 49.38);
        // Crosses the antimeridian. Not corrupt data.
        extent("2373", "USA - Alaska including EEZ", "United States (USA) - Alaska including EEZ.",
                167.65, 47.88, -129.99, 74.71);
        extent("1330", "USA - Alaska", "United States (USA) - Alaska.",
                172.42, 51.3, -129.99, 71.4);
        extent("4516", "USA - CONUS and GoM", "United States (USA) - CONUS onshore and GoM OCS.",
                -124.79, 23.82, -66.91, 49.38);
        extent("4517", "Canada - NAD27", "Canada - onshore and offshore east coast.",
                -141.01, 40.0, -44.0, 83.17);
        extent("1368", "Canada - Quebec", "Canada - Quebec.", -79.85, 44.99, -57.1, 62.62);
        extent("2375", "Canada - Saskatchewan", "Canada - Saskatchewan.",
                -110.0, 49.0, -101.34, 60.01);
        extent("4390", "UK - Britain and UKCS 49°45'N to 61°N, 9°W to 2°E",
                "United Kingdom (UK) - offshore to boundary of UKCS; onshore Great Britain.",
                -9.01, 49.75, 2.01, 61.01);
        extent("1262", "World", "World.", -180.0, -90.0, 180.0, 90.0);
    }

    // ------------------------------------------------------------------ row builders

    private void crs(String code, String name, String datumCode) {
        crss.put("EPSG:" + code, new DbCrs(DbCrsType.GEOGRAPHIC_2D, "EPSG", code, name, false,
                new DbObjectRef(DbObjectType.COORDINATE_SYSTEM, "EPSG", "6422"),
                datumRef(datumCode), null, null, null, null, null));
        usage.put(new DbObjectRef(DbObjectType.GEODETIC_CRS, "EPSG", code),
                Collections.singletonList(new DbObjectRef(DbObjectType.EXTENT, "EPSG", "1262")));
    }

    private void datum(String code, String name, String ellipsoidCode, double ensembleAccuracy,
                       List<DbObjectRef> members) {
        datums.put("EPSG:" + code, new DbDatum(DbObjectType.GEODETIC_DATUM, "EPSG", code, name,
                new DbObjectRef(DbObjectType.ELLIPSOID, "EPSG", ellipsoidCode),
                new DbObjectRef(DbObjectType.PRIME_MERIDIAN, "EPSG", "8901"), null, Double.NaN,
                ensembleAccuracy, members, false));
    }

    private void extent(String code, String name, String description, double west, double south,
                        double east, double north) {
        extents.put("EPSG:" + code,
                new DbExtent("EPSG", code, name, description, west, south, east, north, false));
    }

    private void gridOp(String code, String name, String methodCode, String methodName,
                        double accuracy, String grid1, String grid2, String srcCode, String tgtCode,
                        String extentCode) {
        List<String> grids = grid2 == null
                ? Collections.singletonList(grid1)
                : Arrays.asList(grid1, grid2);
        operations.add(new DbOperation(DbObjectType.GRID_TRANSFORMATION, "EPSG", code, name, "EPSG",
                methodCode, methodName, crsRef(srcCode), crsRef(tgtCode), accuracy, null, grids,
                null, null, null, false));
        usage.put(new DbObjectRef(DbObjectType.GRID_TRANSFORMATION, "EPSG", code),
                Collections.singletonList(new DbObjectRef(DbObjectType.EXTENT, "EPSG", extentCode)));
    }

    private void alternative(String original, String modern, String legacy, String format,
                             String method) {
        alternatives.put(original, new DbGridAlternative(original, modern, legacy, format, method,
                false, "https://cdn.proj.org/" + modern, Boolean.TRUE, Boolean.TRUE, null));
    }

    private static DbObjectRef crsRef(String code) {
        return new DbObjectRef(DbObjectType.GEODETIC_CRS, "EPSG", code);
    }

    private static DbObjectRef datumRef(String code) {
        return new DbObjectRef(DbObjectType.GEODETIC_DATUM, "EPSG", code);
    }

    private static DbObjectRef unitRef(String code) {
        return new DbObjectRef(DbObjectType.UNIT_OF_MEASURE, "EPSG", code);
    }

    // ------------------------------------------------------------------ ProjDatabase

    @Override
    public String name() {
        return name;
    }

    @Override
    public Map<String, String> metadata() {
        return metadata;
    }

    @Override
    public SortedSet<String> authorities() {
        return Collections.unmodifiableSortedSet(new TreeSet<String>(Arrays.asList("EPSG", "PROJ")));
    }

    @Override
    public DbCrs crs(String authName, String code) {
        return crss.get(authName + ":" + code);
    }

    @Override
    public List<DbObjectRef> crsCodes(String authName) {
        List<DbObjectRef> out = new ArrayList<DbObjectRef>();
        for (DbCrs c : crss.values()) {
            if (authName == null || authName.equals(c.authName())) {
                out.add(c.ref());
            }
        }
        Collections.sort(out);
        return Collections.unmodifiableList(out);
    }

    @Override
    public DbCoordinateSystem coordinateSystem(String authName, String code) {
        return coordinateSystems.get(authName + ":" + code);
    }

    @Override
    public DbDatum datum(DbObjectType type, String authName, String code) {
        if (type != DbObjectType.GEODETIC_DATUM && type != DbObjectType.VERTICAL_DATUM) {
            throw new IllegalArgumentException("not a datum type: " + type);
        }
        DbDatum found = datums.get(authName + ":" + code);
        return found != null && found.type() == type ? found : null;
    }

    @Override
    public DbEllipsoid ellipsoid(String authName, String code) {
        return ellipsoids.get(authName + ":" + code);
    }

    @Override
    public DbPrimeMeridian primeMeridian(String authName, String code) {
        return primeMeridians.get(authName + ":" + code);
    }

    @Override
    public DbUnit unit(String authName, String code) {
        return units.get(authName + ":" + code);
    }

    @Override
    public DbCelestialBody celestialBody(String authName, String code) {
        return null;
    }

    @Override
    public List<DbObjectRef> crsUsingDatum(DbObjectType datumType, String datumAuthName,
                                           String datumCode) {
        List<DbObjectRef> out = new ArrayList<DbObjectRef>();
        for (DbCrs c : crss.values()) {
            DbObjectRef d = c.datum();
            if (d != null && d.authName().equals(datumAuthName) && d.code().equals(datumCode)) {
                out.add(c.ref());
            }
        }
        Collections.sort(out);
        return Collections.unmodifiableList(out);
    }

    @Override
    public DbConversion conversion(String authName, String code) {
        return null;
    }

    @Override
    public DbOperation operation(String authName, String code) {
        for (int i = 0; i < operations.size(); i++) {
            DbOperation op = operations.get(i);
            if (op.authName().equals(authName) && op.code().equals(code)) {
                return op;
            }
        }
        return null;
    }

    /**
     * <b>Only the stored direction</b>, exactly as the SPI specifies, and sorted by
     * {@code (kind, authority, code)} rather than by accuracy. A fake that helpfully merged the two
     * directions or pre-sorted by accuracy would test a database this library does not have, and would
     * hide the very bug it exists to catch.
     */
    @Override
    public List<DbOperation> operationsBetween(String srcAuthName, String srcCode,
                                               String tgtAuthName, String tgtCode) {
        List<DbOperation> out = new ArrayList<DbOperation>();
        for (int i = 0; i < operations.size(); i++) {
            DbOperation op = operations.get(i);
            if (op.sourceCrs() == null || op.targetCrs() == null) {
                continue;
            }
            if (op.sourceCrs().authName().equals(srcAuthName)
                    && op.sourceCrs().code().equals(srcCode)
                    && op.targetCrs().authName().equals(tgtAuthName)
                    && op.targetCrs().code().equals(tgtCode)) {
                out.add(op);
            }
        }
        Collections.sort(out, new java.util.Comparator<DbOperation>() {
            @Override
            public int compare(DbOperation a, DbOperation b) {
                return a.ref().compareTo(b.ref());
            }
        });
        return Collections.unmodifiableList(out);
    }

    @Override
    public List<DbObjectRef> operationsWithSourceCrs(String authName, String code) {
        List<DbObjectRef> out = new ArrayList<DbObjectRef>();
        for (int i = 0; i < operations.size(); i++) {
            DbOperation op = operations.get(i);
            if (op.sourceCrs() != null && op.sourceCrs().authName().equals(authName)
                    && op.sourceCrs().code().equals(code)) {
                out.add(op.ref());
            }
        }
        Collections.sort(out);
        return Collections.unmodifiableList(out);
    }

    @Override
    public List<DbObjectRef> operationsWithTargetCrs(String authName, String code) {
        List<DbObjectRef> out = new ArrayList<DbObjectRef>();
        for (int i = 0; i < operations.size(); i++) {
            DbOperation op = operations.get(i);
            if (op.targetCrs() != null && op.targetCrs().authName().equals(authName)
                    && op.targetCrs().code().equals(code)) {
                out.add(op.ref());
            }
        }
        Collections.sort(out);
        return Collections.unmodifiableList(out);
    }

    @Override
    public List<DbExtent> extentsFor(DbObjectRef object) {
        List<DbObjectRef> refs = usage.get(object);
        if (refs == null) {
            return Collections.emptyList();
        }
        List<DbExtent> out = new ArrayList<DbExtent>(refs.size());
        for (int i = 0; i < refs.size(); i++) {
            DbExtent e = extents.get(refs.get(i).authName() + ":" + refs.get(i).code());
            if (e != null) {
                out.add(e);
            }
        }
        return Collections.unmodifiableList(out);
    }

    @Override
    public DbExtent extent(String authName, String code) {
        return extents.get(authName + ":" + code);
    }

    @Override
    public List<String> aliases(DbObjectRef object) {
        return Collections.emptyList();
    }

    @Override
    public List<DbObjectRef> findCrsByName(String crsName) {
        List<DbObjectRef> out = new ArrayList<DbObjectRef>();
        for (DbCrs c : crss.values()) {
            if (c.name().equalsIgnoreCase(crsName)) {
                out.add(c.ref());
            }
        }
        Collections.sort(out);
        return Collections.unmodifiableList(out);
    }

    @Override
    public List<DbSupersession> supersededBy(DbObjectRef object) {
        List<DbSupersession> rows = supersessions.get(object);
        return rows == null ? Collections.<DbSupersession>emptyList()
                : Collections.unmodifiableList(rows);
    }

    @Override
    public List<DbObjectRef> replacementsFor(DbObjectRef object) {
        return Collections.emptyList();
    }

    @Override
    public DbGridAlternative gridAlternative(String originalGridName) {
        return alternatives.get(originalGridName);
    }

    @Override
    public List<DbGridAlternative> gridAlternatives() {
        return Collections.unmodifiableList(
                new ArrayList<DbGridAlternative>(alternatives.values()));
    }

    @Override
    public void close() {
        // Nothing to release. Idempotent, as the SPI requires.
    }

    /**
     * Records a {@code supersession} row, so the ranking rule that a superseded operation loses only to
     * a replacement connecting the <em>same</em> CRS pair can be exercised.
     */
    FakeProjDatabase superseded(DbObjectRef older, DbObjectRef replacement,
                               boolean sameSourceTargetCrs) {
        List<DbSupersession> rows = supersessions.get(older);
        if (rows == null) {
            rows = new ArrayList<DbSupersession>();
            supersessions.put(older, rows);
        }
        rows.add(new DbSupersession(older, replacement, "EPSG", sameSourceTargetCrs));
        return this;
    }

    /** A reference to one of this fixture's grid transformations, for {@link #superseded}. */
    static DbObjectRef gridTransformation(String code) {
        return new DbObjectRef(DbObjectType.GRID_TRANSFORMATION, "EPSG", code);
    }
}
