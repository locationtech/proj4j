/*
 * Copyright 2026 The Proj4J Contributors.
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
package org.locationtech.proj4j.proj;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;

import org.junit.Test;
import org.locationtech.proj4j.CRSFactory;

/**
 * Pins the <em>effective</em> projection centre of every azimuthal projection whose definition
 * omits {@code +lat_0}/{@code +lon_0}, and pins the enumeration of the classes that can be
 * affected by a change to it.
 *
 * <h2>Why a whole test for two numbers</h2>
 *
 * <p>{@code parser/Proj4Parser} assigns {@code +lat_0} and {@code +lon_0} <b>only when the keyword
 * is present</b>. A Java constructor therefore defines the effective default, and
 * {@link AzimuthalProjection#AzimuthalProjection()} used to define it as <b>45 degrees on both
 * axes</b> where PROJ uses 0. Every {@code +proj=ortho +ellps=WGS84} definition in the corpus was
 * silently oblique.
 *
 * <p>Correcting a shared superclass default is exactly the change whose reach is easy to
 * mis-state, so this test makes both halves of the claim falsifiable:
 *
 * <ol>
 * <li>{@link #everyAzimuthalOperatorTakesProjsDefaultCentre()} asserts the centre of each
 *     azimuthal operator, one row per operator, with the value taken from
 *     <b>{@code proj 9.8.1} itself</b> rather than from this codebase - {@code proj +proj=X +R=1}
 *     and {@code proj +proj=X +R=1 +lat_0=0 +lon_0=0} print the same coordinate for
 *     {@code ortho}, {@code gnom}, {@code stere}, {@code aeqd} and {@code laea}, while
 *     {@code +proj=ups +ellps=WGS84} reproduces {@code +proj=stere +lat_0=90 +lon_0=0 +k_0=0.994
 *     +x_0=2000000 +y_0=2000000} exactly, which is why {@code ups} is the one row that is not 0.
 * <li>{@link #theSubclassEnumerationIsComplete()} walks the source tree for every class that
 *     extends {@link AzimuthalProjection}, transitively, and fails if one is missing from the
 *     table above. Without it a newly added subclass would inherit whatever the superclass
 *     default happens to be and nothing would notice.
 * </ol>
 *
 * <h2>The measurement this guards</h2>
 *
 * <p>Reverting the default alone on a frozen tree and re-running all 7,923 gie assertions moves
 * <b>40</b>, every one of them in {@code ortho}; {@code aeqd}, {@code gnom}, {@code stere},
 * {@code ups} and {@code laea} are unchanged in <em>both</em> directions. The reason the blast
 * radius is that narrow is the enumeration in point 2: only
 * {@link OrthographicAzimuthalProjection} and the unregistered
 * {@link EqualAreaAzimuthalProjection} reach the no-argument constructor at all - every other
 * subclass chains through {@code this(0.0, 0.0)} or sets its own pole.
 *
 * <h2>Why this test can fail</h2>
 *
 * <p>A scan that cannot fail is worthless. The source walk is proven two ways rather than
 * assumed, mirroring {@code determinism.NoJdkAngleConversionTest} and
 * {@code io.wkt.NoGeoApiInCoreTest}: {@link #theSourceWalkFindsAKnownNeedle()} points the same
 * walker at a class it must find and at a name that cannot exist, and
 * {@link #theCentreAssertionWouldNoticeA45DegreeDefault()} shows that the comparison used by the
 * table rejects the exact historical wrong value.
 */
public class AzimuthalCentreDefaultTest {

    /**
     * One row per azimuthal operator: the proj-string with <b>no</b> {@code +lat_0}/{@code +lon_0},
     * and the centre {@code proj 9.8.1} uses for it, in degrees.
     */
    private static final String[][] CENTRES = {
        // +proj=ortho: ortho.cpp has no lat_0/lon_0 handling of its own, so pj_init's 0 stands.
        {"+proj=ortho +ellps=WGS84", "0", "0"},
        {"+proj=ortho +R=1", "0", "0"},
        // +proj=gnom, +proj=stere, +proj=aeqd, +proj=laea: same, verified against the binary.
        {"+proj=gnom +ellps=WGS84", "0", "0"},
        {"+proj=stere +ellps=WGS84", "0", "0"},
        {"+proj=aeqd +ellps=WGS84", "0", "0"},
        {"+proj=laea +ellps=WGS84", "0", "0"},
        // +proj=ups: ups.cpp fixes phi0 = +/- pi/2 from +south and lam0 = 0. NOT a 0 row, and it
        // is here precisely so that "all azimuthal centres are 0" cannot be written by accident.
        {"+proj=ups +ellps=WGS84", "90", "0"},
        {"+proj=ups +south +ellps=WGS84", "-90", "0"},
    };

    /**
     * Every class extending {@link AzimuthalProjection}, transitively. Kept as source-relative
     * paths because {@link #theSubclassEnumerationIsComplete()} compares against a source walk;
     * a class file would not tell us about a subclass that exists but is never loaded.
     */
    private static final String[] KNOWN_SUBCLASSES = {
        "EqualAreaAzimuthalProjection",
        "EquidistantAzimuthalProjection",
        "GnomonicAzimuthalProjection",
        "OrthographicAzimuthalProjection",
        "StereographicAzimuthalProjection",
        "UniversalPolarStereographicProjection",
    };

    /**
     * The subset of {@link #KNOWN_SUBCLASSES} that actually reaches
     * {@link AzimuthalProjection#AzimuthalProjection()}, i.e. the ones a change to that
     * constructor can move. Asserted behaviourally below rather than by reading constructors.
     */
    private static final String[] REACH_THE_NO_ARG_CONSTRUCTOR = {
        "EqualAreaAzimuthalProjection",
        "OrthographicAzimuthalProjection",
    };

    // ------------------------------------------------------------------ the centres themselves

    @Test
    public void everyAzimuthalOperatorTakesProjsDefaultCentre() {
        CRSFactory factory = new CRSFactory();
        for (String[] row : CENTRES) {
            Projection p = factory.createFromParameters("t", row[0]).getProjection();
            assertEquals(row[0] + " lat_0", Double.parseDouble(row[1]),
                    p.getProjectionLatitudeDegrees(), 1e-12);
            assertEquals(row[0] + " lon_0", Double.parseDouble(row[2]),
                    p.getProjectionLongitudeDegrees(), 1e-12);
        }
    }

    /**
     * The classes a change to the no-argument constructor can reach, instantiated directly. Both
     * must sit at the origin; if either moves, the 40-assertion measurement recorded on
     * {@link AzimuthalProjection#AzimuthalProjection()} no longer describes the code.
     */
    @Test
    public void theTwoClassesReachingTheNoArgConstructorSitAtTheOrigin() {
        assertEquals(2, REACH_THE_NO_ARG_CONSTRUCTOR.length);
        AzimuthalProjection[] built = {
            new EqualAreaAzimuthalProjection(),
            new OrthographicAzimuthalProjection(),
        };
        for (AzimuthalProjection p : built) {
            assertEquals(p.getClass().getSimpleName() + " lat_0", 0.0,
                    p.getProjectionLatitude(), 0.0);
            assertEquals(p.getClass().getSimpleName() + " lon_0", 0.0,
                    p.getProjectionLongitude(), 0.0);
            assertEquals(p.getClass().getSimpleName() + " must be EQUATOR, not OBLIQUE",
                    AzimuthalProjection.EQUATOR, p.mode);
        }
    }

    /**
     * The remaining four never touch the no-argument constructor, so they are unaffected by it.
     * Asserted rather than assumed: each is built by its own no-argument constructor and must
     * land where its own chain puts it, not where {@link AzimuthalProjection}'s does.
     */
    @Test
    public void theOtherSubclassesSetTheirOwnCentreAndAreUnaffected() {
        assertEquals(0.0, new EquidistantAzimuthalProjection().getProjectionLatitude(), 0.0);
        assertEquals(0.0, new GnomonicAzimuthalProjection().getProjectionLatitude(), 0.0);
        assertEquals(0.0, new StereographicAzimuthalProjection().getProjectionLatitude(), 0.0);
        // ups is the deliberate exception: ups.cpp assigns phi0 itself, from +south.
        assertEquals(Math.PI / 2, new UniversalPolarStereographicProjection()
                .getProjectionLatitude(), 0.0);
    }

    // --------------------------------------------------------------- the enumeration is complete

    @Test
    public void theSubclassEnumerationIsComplete() throws IOException {
        TreeSet<String> found = subclassesOfAzimuthalProjection();
        TreeSet<String> known = new TreeSet<String>(Arrays.asList(KNOWN_SUBCLASSES));
        assertEquals("the source tree's AzimuthalProjection subclasses no longer match the table "
                + "in this test. A new subclass inherits whatever AzimuthalProjection's no-arg "
                + "constructor defaults to, so add it to CENTRES with the value proj 9.8.1 gives "
                + "for it, and re-measure the corpus before assuming the blast radius is still "
                + "40 assertions in ortho alone.", known, found);
    }

    // ------------------------------------------------------------------------ positive controls

    /**
     * The walker must be able to find something and to not find something. Without this, an empty
     * or mis-rooted walk would make {@link #theSubclassEnumerationIsComplete()} pass by returning
     * nothing at all and comparing it against a table that had also been emptied.
     */
    @Test
    public void theSourceWalkFindsAKnownNeedle() throws IOException {
        List<File> sources = new ArrayList<File>();
        collect(projSourceDirectory(), sources);
        assertTrue("the walk reached " + sources.size() + " sources, which is too few for "
                + "org/locationtech/proj4j/proj - the root is wrong", sources.size() > 100);

        // A needle that is there.
        assertTrue("the walker cannot see 'extends Projection', so it proves nothing",
                anySourceContains(sources, "extends Projection"));
        // A needle that cannot be there.
        assertFalse("the walker reported a string that does not exist, so it is not reading files",
                anySourceContains(sources, "extends AbsolutelyNoSuchSuperclass"));
        // And the specific relation this test is built on.
        assertTrue(subclassesOfAzimuthalProjection().contains("OrthographicAzimuthalProjection"));
    }

    /**
     * The comparison used by {@link #everyAzimuthalOperatorTakesProjsDefaultCentre()} must reject
     * the exact historical wrong value. {@code Math.toRadians(45)} in degrees is 45, and the
     * tolerance is 1e-12, so a 45-degree default cannot slip through as "close enough to 0".
     */
    @Test
    public void theCentreAssertionWouldNoticeA45DegreeDefault() {
        double historicalWrongDefault = Math.toDegrees(Math.toRadians(45.0));
        assertTrue("a 45 degree default must be further from 0 than the assertion tolerance",
                Math.abs(historicalWrongDefault - 0.0) > 1e-12);
        // And the wrong value really was reachable: it is what a bare +proj=ortho produced, and
        // the coordinate it produced, a*cos(10)*sin(170-45), is 5145289.577 m.
        assertEquals(5145289.577,
                6378137.0 * Math.cos(Math.toRadians(10)) * Math.sin(Math.toRadians(170 - 45)),
                1e-3);
    }

    // ------------------------------------------------------------------------------- the walker

    private static TreeSet<String> subclassesOfAzimuthalProjection() throws IOException {
        List<File> sources = new ArrayList<File>();
        collect(projSourceDirectory(), sources);

        TreeSet<String> supers = new TreeSet<String>();
        supers.add("AzimuthalProjection");
        TreeSet<String> found = new TreeSet<String>();
        // Transitive closure: a subclass of a subclass is still affected by the superclass.
        boolean grew = true;
        while (grew) {
            grew = false;
            for (File f : sources) {
                String simple = f.getName().substring(0, f.getName().length() - ".java".length());
                if (found.contains(simple)) {
                    continue;
                }
                String text = read(f);
                for (String s : supers) {
                    if (text.contains("extends " + s + " ")
                            || text.contains("extends " + s + "\n")
                            || text.contains("extends " + s + "{")) {
                        found.add(simple);
                        grew = true;
                        break;
                    }
                }
            }
            supers.addAll(found);
        }
        return found;
    }

    private static boolean anySourceContains(List<File> sources, String needle) throws IOException {
        for (File f : sources) {
            if (read(f).contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static void collect(File dir, List<File> out) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File c : children) {
            if (c.isDirectory()) {
                collect(c, out);
            } else if (c.getName().endsWith(".java")) {
                out.add(c);
            }
        }
    }

    /**
     * {@code core/src/main/java/org/locationtech/proj4j/proj}, located from the compiled class's
     * own URL so the test does not depend on the working directory the JVM was started in.
     */
    private static File projSourceDirectory() {
        URL url = AzimuthalCentreDefaultTest.class.getResource(
                "/" + AzimuthalCentreDefaultTest.class.getName().replace('.', '/') + ".class");
        if (url != null && "file".equals(url.getProtocol())) {
            // .../core/target/test-classes/org/locationtech/proj4j/proj/<this>.class
            File f = new File(url.getPath());
            for (int i = 0; i < 6 && f != null; i++) {
                f = f.getParentFile();
            }
            if (f != null) {
                File candidate = new File(f, "src/main/java/org/locationtech/proj4j/proj");
                if (candidate.isDirectory()) {
                    return candidate;
                }
            }
        }
        for (String prefix : new String[] {"core/", ""}) {
            File candidate = new File(prefix + "src/main/java/org/locationtech/proj4j/proj");
            if (candidate.isDirectory()) {
                return candidate;
            }
        }
        fail("could not locate core/src/main/java/org/locationtech/proj4j/proj");
        return null;
    }

    private static String read(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
            return new String(out.toByteArray(), "UTF-8");
        } finally {
            in.close();
        }
    }
}
