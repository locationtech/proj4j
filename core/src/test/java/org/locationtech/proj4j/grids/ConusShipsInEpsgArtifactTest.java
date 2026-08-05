/*******************************************************************************
 * Copyright 2026 Proj4J contributors
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
 *******************************************************************************/
package org.locationtech.proj4j.grids;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.junit.Test;
import org.locationtech.proj4j.CRSFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * <strong>{@code proj4j-epsg} must ship {@code conus}, and a consumer with only {@code proj4j} and
 * {@code proj4j-epsg} on the classpath must get a real NAD27 datum shift across the conterminous
 * United States.</strong> This class fails if that grid ever stops shipping.
 *
 * <h2>Why this cannot be tested the obvious way</h2>
 *
 * {@link Nad27ToNad83ConusTest} already pins the <em>numbers</em>, but it cannot pin the
 * <em>packaging</em>: core's own test classpath carries a copy of the grid at
 * {@code core/src/test/resources/proj4j-data/grids/conus}, and that copy sits at resolver step 3
 * ({@code classpath:/proj4j-data/grids/}), <em>ahead</em> of the legacy step 4
 * ({@code classpath:/proj4/nad/}) where {@code proj4j-epsg} publishes. So an ordinary in-process
 * transform test goes on passing after {@code proj4j-epsg} stops shipping anything at all — it is
 * masked by the fixture, and it would report success for a consumer who gets none.
 *
 * <p>The fix is to build the consumer's classpath explicitly: a {@link URLClassLoader} over exactly
 * two URLs — core's own code and whatever artifact publishes {@code /proj4/nad/epsg} — with a
 * {@code null} parent, so the bootstrap loader supplies {@code java.*} and <em>nothing else is
 * reachable</em>. proj4j core has zero runtime dependencies, which is what makes this legal. Core's
 * test classes and test resources are not on it, so the fixture copy is invisible and any shift
 * observed came from the shipped artifact.
 *
 * <h2>Controls, because a packaging test that cannot fail is worthless</h2>
 *
 * <ul>
 *   <li>{@link #theIsolatedLoaderCannotSeeTheTestFixtureCopy()} is the load-bearing one: it asserts
 *       the isolated loader finds {@code /proj4/nad/conus} and does <strong>not</strong> find
 *       {@code /proj4j-data/grids/conus}, while the ordinary test loader finds both. If the
 *       isolation ever silently stopped working, every other assertion here would become vacuous —
 *       so the isolation itself is asserted rather than assumed.</li>
 *   <li>{@link #theClasspathProbeDiscriminates()} finds a resource known to be present
 *       ({@code /proj4/nad/ntv1_can.dat}) and fails to find a fabricated sibling, so a
 *       {@code null} from this probe is evidence about the artifact rather than about the probe.</li>
 *   <li>{@link #pointsOutsideEveryShippedGridStillFailClosed()} is the negative half. Adding
 *       {@code conus} must fix CONUS and <strong>nothing else</strong>: Hawaii, Puerto Rico, Alaska
 *       and the open ocean must keep raising {@code COORDINATE_OUTSIDE_GRID}. Without this, a
 *       blanket fail-open would pass the positive half just as well.</li>
 * </ul>
 *
 * <h2>Reference values</h2>
 *
 * PROJ 9.8.1 (<em>Rel. 9.8.1, April 10th, 2026</em>) reading <em>the same grid bytes</em>:
 *
 * <pre>
 * printf -- "-122.4194 37.7749 0 0\n-97.5 39.0 0 0\n" | cct -d 12 +proj=hgridshift +grids=conus
 *   -122.420482174289  37.774829514951
 *    -97.500307341108  38.999999348889
 * </pre>
 *
 * The CTABLE V2 {@code conus} shipped here and PROJ's own GeoTIFF {@code us_noaa_conus.tif} give
 * results identical to all 12 printed decimals, so the format choice is not a source of divergence.
 *
 * <p><strong>The reference is deliberately <em>not</em> {@code cs2cs EPSG:4267 EPSG:4269}.</strong>
 * On a machine with the full {@code proj-data} package installed that command selects
 * <em>&ldquo;NAD27 to NAD83 (7)&rdquo;</em> (EPSG:8555), which is <strong>NADCON5</strong>
 * ({@code us_noaa_nadcon5_nad27_nad83_1986_conus.tif}) and answers
 * {@code -122.420481960698 37.774831945189} — about 0.27 m away. That is a different published
 * transformation, not a discrepancy: proj4j realises {@code +datum=NAD27} as PROJ's legacy
 * {@code +nadgrids=@conus,...} table entry, i.e. EPSG:1241 via {@code hgridshift}, so the operation
 * above is the one to pin against. Pinning the {@code cs2cs} number would have encoded a 0.27 m
 * error as ground truth.
 *
 * <p>Shift magnitudes, as geodesic distances on GRS80 ({@code geod -I +ellps=GRS80}, whose
 * instrument check reproduces 110574.389 m for one degree of latitude at the equator and
 * 111319.491 m for one degree of longitude): <strong>95.660 m</strong> at San Francisco and
 * <strong>26.624 m</strong> in Kansas. Both were 0 m before, because no shipped grid reached them.
 *
 * @see Nad27ToNad83ConusTest for the numerical behaviour of the grid itself
 * @see Ntv1CanHeaderTest for why {@code ntv1_can.dat} alone does not cover CONUS
 */
public class ConusShipsInEpsgArtifactTest {

    /** {@code git -C <PROJ> cat-file blob 44b4900f3168a5b87794f41d201d03d5aea0b964 | shasum -a 256} */
    private static final String CONUS_SHA256 =
            "504d184f9a9f6e6c6b76df753346fd236b74772f52a8a5c90d8a43d3651d274d";

    private static final int CONUS_BYTES = 264424;

    /** Where {@code proj4j-epsg} publishes its grids; resolver chain step 4. */
    private static final String CONUS_ON_CLASSPATH = "/proj4/nad/conus";

    /** Where a grid <em>pack</em> publishes; resolver chain step 3, and where the fixture copy is. */
    private static final String CONUS_IN_GRID_PACK = "/proj4j-data/grids/conus";

    /** A resource {@code proj4j-epsg} has always shipped, used as the probe's positive control. */
    private static final String KNOWN_PRESENT = "/proj4/nad/ntv1_can.dat";

    /** Tolerance in degrees. PROJ's printed reference carries 12 decimals. */
    private static final double TOL_DEG = 1e-9;

    private static final double[] SAN_FRANCISCO = {-122.4194, 37.7749};
    private static final double[] KANSAS = {-97.5, 39.0};

    private static final double[] SAN_FRANCISCO_NAD83 = {-122.420482174289, 37.774829514951};
    private static final double[] KANSAS_NAD83 = {-97.500307341108, 38.999999348889};

    // --- The shipping guarantee ---------------------------------------------------------------

    /**
     * The bytes are on the classpath at the path {@code proj4j-epsg} owns, and they are PROJ's.
     * Core's test resources publish {@code conus} at a <em>different</em> path, so this assertion
     * cannot be satisfied by the fixture.
     */
    @Test
    public void epsgShipsConusAtTheLegacyGridPath() throws IOException {
        byte[] bytes = read(CONUS_ON_CLASSPATH);
        assertNotNull(CONUS_ON_CLASSPATH + " is not on the classpath. proj4j-epsg must ship it: "
                + "without it NAD27 transforms across the conterminous United States have no grid "
                + "and fail closed.", bytes);
        assertEquals("conus size", CONUS_BYTES, bytes.length);
        assertEquals("conus SHA-256 -- must stay byte-identical to PROJ 9.8.1 data/tests/conus, "
                + "blob 44b4900f3168a5b87794f41d201d03d5aea0b964", CONUS_SHA256, sha256(bytes));
        assertEquals("CTABLE V2 magic", "CTABLE V2", new String(bytes, 0, 9, "US-ASCII"));
    }

    /**
     * A {@code null} from {@link Class#getResource} must be evidence about the artifact, not about
     * the probe. Finds something known to be present; fails to find a fabricated sibling.
     */
    @Test
    public void theClasspathProbeDiscriminates() throws IOException {
        assertNotNull("positive control: " + KNOWN_PRESENT + " has always shipped in proj4j-epsg",
                read(KNOWN_PRESENT));
        assertNull("negative control: a fabricated name must not resolve, or this probe proves nothing",
                read("/proj4/nad/conus-that-does-not-exist"));
    }

    // --- The consumer classpath ---------------------------------------------------------------

    /**
     * The isolation itself, asserted rather than assumed. Every behavioural assertion below is
     * vacuous if this fails: the isolated loader must see the shipped grid and must <em>not</em>
     * see the test fixture, while the ordinary test loader sees both.
     */
    @Test
    public void theIsolatedLoaderCannotSeeTheTestFixtureCopy() throws Exception {
        assertNotNull("fixture copy is expected on the ordinary test classpath -- if this is gone "
                + "the premise of this class has changed", read(CONUS_IN_GRID_PACK));
        assertNotNull("shipped copy is expected on the ordinary test classpath too", read(CONUS_ON_CLASSPATH));

        URLClassLoader consumer = consumerClassLoader();
        try {
            assertNotNull("the consumer classpath must carry the shipped grid",
                    consumer.getResource(strip(CONUS_ON_CLASSPATH)));
            assertNull("the consumer classpath must NOT carry core's test fixture, or this whole "
                    + "class silently stops testing packaging",
                    consumer.getResource(strip(CONUS_IN_GRID_PACK)));
            assertNull("the consumer classpath must not carry core's test classes either",
                    consumer.getResource("org/locationtech/proj4j/grids/ConusShipsInEpsgArtifactTest.class"));
        } finally {
            consumer.close();
        }
    }

    /**
     * The deliverable. With only {@code proj4j} and {@code proj4j-epsg} reachable,
     * {@code EPSG:4267 -> EPSG:4269} shifts, and shifts to PROJ 9.8.1's value.
     */
    @Test
    public void nad27ShiftsAcrossConusWithOnlyCoreAndEpsg() throws Exception {
        URLClassLoader consumer = consumerClassLoader();
        try {
            assertTransforms(consumer, "San Francisco", SAN_FRANCISCO, SAN_FRANCISCO_NAD83);
            assertTransforms(consumer, "Kansas", KANSAS, KANSAS_NAD83);
        } finally {
            consumer.close();
        }
    }

    /**
     * The negative half: the fix must be targeted, not blanket. Hawaii, Puerto Rico and Alaska have
     * no CTABLE V2 form in PROJ 9.8.1's tree and are not shipped; the Gulf of Guinea point is the
     * one every {@code proj4-epsg.csv} row probes, ~12,000 km outside every US grid. All must still
     * refuse rather than invent a plausible coordinate.
     */
    @Test
    public void pointsOutsideEveryShippedGridStillFailClosed() throws Exception {
        URLClassLoader consumer = consumerClassLoader();
        try {
            assertRefuses(consumer, "Honolulu", new double[]{-157.8583, 21.3069});
            assertRefuses(consumer, "San Juan PR", new double[]{-66.1057, 18.4655});
            assertRefuses(consumer, "Anchorage", new double[]{-149.9003, 61.2181});
            assertRefuses(consumer, "Gulf of Guinea", new double[]{1.0, -1.0});
        } finally {
            consumer.close();
        }
    }

    // --- helpers ------------------------------------------------------------------------------

    private void assertTransforms(ClassLoader cl, String label, double[] in, double[] expected)
            throws Exception {
        double[] got = transform(cl, in);
        assertEquals(label + " longitude", expected[0], got[0], TOL_DEG);
        assertEquals(label + " latitude", expected[1], got[1], TOL_DEG);
        assertTrue(label + " must actually move -- an unchanged coordinate is the defect this "
                + "class exists to prevent", Math.abs(got[0] - in[0]) + Math.abs(got[1] - in[1]) > 1e-8);
    }

    private void assertRefuses(ClassLoader cl, String label, double[] in) throws Exception {
        try {
            double[] got = transform(cl, in);
            fail(label + " is outside every grid proj4j-epsg ships and must raise "
                    + "COORDINATE_OUTSIDE_GRID, but returned (" + got[0] + ", " + got[1] + ")");
        } catch (InvocationTargetException e) {
            Throwable t = e.getCause();
            assertEquals(label + " must fail with a CrsTransformException, got: " + t,
                    "org.locationtech.proj4j.CrsTransformException", t.getClass().getName());
            Method cause = t.getClass().getMethod("cause");
            Object errorCause = cause.invoke(t);
            assertNotNull(label + " must carry a machine-readable cause", errorCause);
            assertEquals(label + " cause", "COORDINATE_OUTSIDE_GRID",
                    ((Enum<?>) errorCause).name());
        }
    }

    /** Drives {@code EPSG:4267 -> EPSG:4269} entirely inside the given loader, by reflection. */
    private double[] transform(ClassLoader cl, double[] in) throws Exception {
        Class<?> crsFactoryType = cl.loadClass("org.locationtech.proj4j.CRSFactory");
        Class<?> ctFactoryType = cl.loadClass("org.locationtech.proj4j.CoordinateTransformFactory");
        Class<?> crsType = cl.loadClass("org.locationtech.proj4j.CoordinateReferenceSystem");
        Class<?> coordType = cl.loadClass("org.locationtech.proj4j.ProjCoordinate");

        Object crsFactory = crsFactoryType.getDeclaredConstructor().newInstance();
        Object ctFactory = ctFactoryType.getDeclaredConstructor().newInstance();

        Method createFromName = crsFactoryType.getMethod("createFromName", String.class);
        Object src = createFromName.invoke(crsFactory, "EPSG:4267");
        Object tgt = createFromName.invoke(crsFactory, "EPSG:4269");

        Object ct = ctFactoryType.getMethod("createTransform", crsType, crsType)
                .invoke(ctFactory, src, tgt);

        Object from = coordType.getDeclaredConstructor(double.class, double.class)
                .newInstance(in[0], in[1]);
        Object to = coordType.getDeclaredConstructor().newInstance();

        ct.getClass().getMethod("transform", coordType, coordType).invoke(ct, from, to);

        return new double[]{
                coordType.getField("x").getDouble(to),
                coordType.getField("y").getDouble(to)};
    }

    /**
     * Exactly what a consumer gets: core's code plus whatever artifact publishes the EPSG
     * dictionary, and a {@code null} parent so nothing else on the surefire classpath leaks in.
     * Legal only because proj4j core has zero runtime dependencies.
     */
    private static URLClassLoader consumerClassLoader() {
        URL core = CRSFactory.class.getProtectionDomain().getCodeSource().getLocation();
        assertNotNull("cannot locate core's own code", core);
        URL epsg = rootOf("/proj4/nad/epsg");
        assertNotNull("cannot locate the proj4j-epsg artifact from /proj4/nad/epsg -- this test "
                + "requires it on the classpath", epsg);
        return new URLClassLoader(new URL[]{core, epsg}, null);
    }

    /**
     * The classpath root that publishes {@code resource}, whether that is an exploded directory or
     * a jar. Handles both so the test means the same thing in a reactor build and against the
     * packaged artifact.
     */
    private static URL rootOf(String resource) {
        URL url = ConusShipsInEpsgArtifactTest.class.getResource(resource);
        if (url == null) {
            return null;
        }
        String s = url.toExternalForm();
        try {
            if ("jar".equals(url.getProtocol())) {
                int bang = s.indexOf("!/");
                return new URL(s.substring("jar:".length(), bang));
            }
            if (!s.endsWith(resource)) {
                return null;
            }
            return new URL(s.substring(0, s.length() - resource.length() + 1));
        } catch (IOException e) {
            return null;
        }
    }

    private static String strip(String absolute) {
        return absolute.startsWith("/") ? absolute.substring(1) : absolute;
    }

    /** Whole-resource read, or {@code null} if absent. */
    private static byte[] read(String resource) throws IOException {
        InputStream in = ConusShipsInEpsgArtifactTest.class.getResourceAsStream(resource);
        if (in == null) {
            return null;
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xf, 16));
                sb.append(Character.forDigit(b & 0xf, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every JRE", e);
        }
    }
}
