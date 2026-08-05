/*******************************************************************************
 * Copyright 2026
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

package org.locationtech.proj4j.determinism;

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
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.util.ProjectionMath;

/**
 * Asserts that no <b>result-bearing</b> code path in {@code core} calls {@link Math#toRadians} or
 * {@link Math#toDegrees}, because neither is stable across Java versions and neither matches the
 * arithmetic PROJ uses. {@link ProjectionMath#toRad(double)} and
 * {@link ProjectionMath#toDeg(double)} are the replacements.
 *
 * <h2>What was measured, and on what</h2>
 *
 * <p>Over the 721 whole degrees in {@code [-360, 360]}, on Temurin <b>8u502 (x86-64)</b>,
 * <b>11.0.32 (aarch64)</b>, <b>11.0.32 (x86-64)</b> and <b>21.0.11 (aarch64)</b>, with the input
 * set pinned by digest so that a moved argument could not be mistaken for a moved function:
 *
 * <table border="1">
 * <caption>1-ULP disagreement counts out of 721</caption>
 * <tr><th>pair</th><th>Java 8</th><th>Java 11 / 21</th></tr>
 * <tr><td>{@code Math.toRadians(d)} vs {@code d * DTR}</td><td><b>182</b> (25.24%)</td><td>0</td></tr>
 * <tr><td>{@code Math.toRadians(d)} vs {@code d * PI / 180.0}</td><td>196</td><td>186</td></tr>
 * <tr><td>{@code Math.toDegrees(r)} vs {@code r / DTR}</td><td><b>178</b></td><td><b>46</b></td></tr>
 * <tr><td>{@code Math.toDegrees(r)} vs {@code r * RTD}</td><td>180</td><td>0</td></tr>
 * <tr><td>{@code r * RTD} vs {@code r / DTR}</td><td>46</td><td>46</td></tr>
 * </table>
 *
 * <p>Three conclusions follow, and the third is the one that cost a wrong first attempt.
 *
 * <ol>
 * <li>Both JDK methods changed body at Java 9, from an association to a constant multiply, so
 *     <b>both directions are version-unstable</b>. {@code toDegrees} is <em>not</em> exempt: the
 *     often-repeated claim that {@code PJ_TODEG} is bit-identical to {@link Math#toDegrees} was
 *     measured on Java 8 and stopped being true at Java 9.</li>
 * <li>1 ULP of a radian is about 1.4e-9 m on the ellipsoid, which is exactly the bar of the 16
 *     {@code nm}-tolerance rows in the gie corpus, so this is a conformance question.</li>
 * <li><b>PROJ has two idioms, and the {@code PJ_TORAD}/{@code PJ_TODEG} macros are the minority
 *     one.</b> {@code DEG_TO_RAD}/{@code RAD_TO_DEG} ({@code proj_internal.h:1033-1034}) are used
 *     at <b>147</b> and <b>33</b> sites against the macros' <b>7</b> and <b>11</b>, and their
 *     literals are bit-identical to Java's {@code Math.PI / 180.0} and {@code 180.0 / Math.PI}.
 *     The projections that actually convert at this boundary — {@code gnom.cpp:124-131},
 *     {@code aeqd.cpp:110-116} — write {@code lp.phi / DEG_TO_RAD} and {@code azi1 *= DEG_TO_RAD}.
 *     Adopting the macro association instead was tried and <b>measurably worsened</b> agreement
 *     with {@code proj 9.8.1}: over 1,187 in-domain points {@code +proj=gnom} fell from 204 to 148
 *     bit-exact matches. With {@code DTR} it rises to 214. Hence
 *     {@link ProjectionMath#toRad(double)} is {@code deg * DTR} and
 *     {@link ProjectionMath#toDeg(double)} is {@code rad / DTR} — a divide, because
 *     {@code x * (1/s)} is not {@code x / s} and PROJ divides here.</li>
 * </ol>
 *
 * <h2>What is deliberately allowed</h2>
 *
 * <p><b>{@code geodesic/**} is exempt, and the reason is that its two upstreams disagree.</b> That
 * package is Karney's GeographicLib, vendored and kept close to source. Karney's <em>Java</em>
 * edition writes {@code Math.toRadians}/{@code Math.toDegrees}; his <em>C</em> edition — which is
 * the one PROJ actually links, bundled at {@code 9.8.1:src/geodesic.c} — precomputes
 * {@code degree = pi/180} and writes {@code r *= degree} and {@code ang = atan2(y, x) / degree}.
 * Measured over Karney's own reduced argument range ({@code |r| <= 45}, {@code |t| <= pi/4}):
 * the radian direction agrees with the C edition on Java 11 and 21 and disagrees on 26.91% of
 * samples on Java 8; the degree direction <b>never</b> agrees, differing on 11.55% of samples on
 * every JVM including 21, because {@code x * (1/s)} is not {@code x / s}. So no single edit
 * reconciles both upstreams: converting the package would diverge from its Java upstream without
 * reaching its C one. The divergence is pre-existing, is Karney's, and is recorded here rather
 * than silently taken.
 *
 * <p>Cosmetic occurrences — a value interpolated into an exception message, a {@code toString} or a
 * javadoc snippet — are allowed anywhere. A 1-ULP difference in a number that only ever reaches a
 * string cannot reach a coordinate, and forbidding them would be churn with no determinism content.
 *
 * <h2>Why this test can fail</h2>
 *
 * <p>A scan that cannot fail is worthless, so the detector's sensitivity is proven three ways
 * rather than assumed. The sibling scans in {@code io.wkt.NoGeoApiInCoreTest} and
 * {@code resource.NoAmbientInputInCoreTest} carry the same shape — note that
 * {@code NoGeoApiInCoreTest} did <em>not</em>, until it was given one; the 28-of-32
 * {@code geoapi}-classes measurement was for a while attributed to a test that never ran it, and it
 * now does ({@code theGeoApiModuleIsFullOfTheNeedleThisScanLooksFor}):
 *
 * <ul>
 * <li>{@link #classifierDistinguishesResultBearingFromCosmetic()} feeds the classifier crafted
 *     lines of both kinds and asserts each verdict, so the rule itself is tested rather than only
 *     the directory walk.</li>
 * <li>{@link #scanFindsTheKnownPositivesInTheVendoredGeodesicPackage()} points the very same
 *     scanner at {@code geodesic/**}, which is known to contain result-bearing calls, and requires
 *     it to find them. If the walk silently reached nothing, the main assertion would pass
 *     trivially; this makes that impossible.</li>
 * <li>{@link #compiledGeodesicClassesStillNameTheJdkMethods()} does the same at the bytecode level,
 *     independently of source layout.</li>
 * </ul>
 *
 * <p>If the main assertion fails, do not add the offender to the allow-list. Replace the call with
 * {@link ProjectionMath#toRad(double)} or {@link ProjectionMath#toDeg(double)}.
 */
public class NoJdkAngleConversionTest {

    /**
     * Package paths, relative to the source root, that are exempt. Only the vendored
     * GeographicLib port is, and only for the reason set out in the class comment.
     */
    private static final String[] ALLOWED_PACKAGES = {
        "org/locationtech/proj4j/geodesic/"
    };

    /** The two JDK methods no result-bearing core path may name. */
    private static final String[] FORBIDDEN = {"Math.toRadians", "Math.toDegrees"};

    // ------------------------------------------------------------------ the guard

    @Test
    public void noResultBearingCoreCodeCallsTheJdkAngleConversions() throws IOException {
        File sourceRoot = coreSourceRoot();
        List<File> sources = new ArrayList<File>();
        collect(sourceRoot, sources, ".java");
        assertTrue("found no core sources to scan under " + sourceRoot + ", so this test would "
                + "prove nothing", sources.size() > 100);

        List<String> offenders = new ArrayList<String>();
        int cosmetic = 0;
        int exempt = 0;
        for (int i = 0; i < sources.size(); i++) {
            File f = sources.get(i);
            String rel = relative(sourceRoot, f).replace(File.separatorChar, '/');
            List<Occurrence> found = scan(read(f));
            for (int j = 0; j < found.size(); j++) {
                Occurrence o = found.get(j);
                if (!o.resultBearing) {
                    cosmetic++;
                } else if (isAllowedPackage(rel)) {
                    exempt++;
                } else {
                    offenders.add(rel + ":" + o.line + "  " + o.text);
                }
            }
        }

        // The scan must have reached the exempt package, otherwise the walk is broken and the
        // zero-offender result below is vacuous.
        assertTrue("the scan did not reach the vendored geodesic package, so it proves nothing",
                exempt > 0);
        assertTrue("the scan found no cosmetic occurrences at all, which means the classifier is "
                + "rejecting everything and the guard is not actually discriminating", cosmetic > 0);

        if (!offenders.isEmpty()) {
            fail("Math.toRadians/Math.toDegrees are not bit-stable across Java versions -- both "
                    + "changed body at Java 9, from an association to a constant multiply -- and "
                    + "neither matches the DEG_TO_RAD multiply/divide that PROJ actually uses at "
                    + "these boundaries. Use ProjectionMath.toRad/toDeg on any path whose value "
                    + "reaches a coordinate, a grid index, a comparison or a stored parameter. Do "
                    + "not add the site to ALLOWED_PACKAGES. Offending sites ("
                    + offenders.size() + "): " + offenders);
        }
    }

    // ------------------------------------------------------- sensitivity proof 1: the classifier

    /**
     * The classifier is the part that could silently stop working, so it is tested directly against
     * lines of both kinds — including the two shapes that actually caused trouble when this audit
     * was done by hand.
     */
    @Test
    public void classifierDistinguishesResultBearingFromCosmetic() {
        // Result-bearing.
        assertResultBearing("        lp.y = Math.toRadians(lat1);");
        assertResultBearing("        double azi = Math.toRadians(g.azi1);");
        assertResultBearing("        minLatitude = Math.toRadians(-80);");
        assertResultBearing("        return Math.toDegrees(proj.getAlpha());");
        assertResultBearing("        pm.setLongitude(Math.toDegrees(radians(projPm)));");
        // The false positive a naive "is there a quote earlier on the line" rule gets wrong: a
        // real conversion sharing a line with unrelated string constructor arguments.
        assertResultBearing(
                "    static final Unit RADIANS = new Unit(\"radian\", \"rad\", Math.toDegrees(1));");

        // Cosmetic: the value only ever reaches a message.
        assertCosmetic("            \"(\" + Math.toDegrees(lam) + \", \" + Math.toDegrees(phi)");
        assertCosmetic("                    + Math.toDegrees(phi1));");
        assertCosmetic("                + \"lat_0 = \" + Math.toDegrees(lat) + \" degrees\");");
        // Comments and javadoc are never code.
        assertCosmetic("     * {@code maxLatitude = Math.toRadians(60);//FIXME} that hard-coded");
        assertCosmetic("        // r = Math.toRadians(r);");

        // And a line with neither must yield nothing at all.
        assertEquals(0, scan("        double x = ProjectionMath.toRad(deg);").size());
    }

    private static void assertResultBearing(String line) {
        List<Occurrence> found = scan(line);
        assertEquals("expected exactly one occurrence in: " + line, 1, found.size());
        assertTrue("should have been classified result-bearing: " + line,
                found.get(0).resultBearing);
    }

    private static void assertCosmetic(String line) {
        List<Occurrence> found = scan(line);
        assertTrue("expected at least one occurrence in: " + line, found.size() > 0);
        for (int i = 0; i < found.size(); i++) {
            assertFalse("should have been classified cosmetic: " + line,
                    found.get(i).resultBearing);
        }
    }

    // ------------------------------------------------- sensitivity proof 2: a known-positive tree

    /**
     * Points the production scanner at the one package known to contain result-bearing calls. This
     * is the analogue of {@code NoGeoApiInCoreTest} finding {@code org/opengis/} in 28 of 32
     * {@code geoapi} classes: a positive control that keeps working on its own, because it is the
     * exemption itself. The difference is that this one needs nothing outside {@code core} and so
     * runs unconditionally, where that one is skipped on a clean build until the {@code geoapi}
     * module has been compiled.
     */
    @Test
    public void scanFindsTheKnownPositivesInTheVendoredGeodesicPackage() throws IOException {
        File root = coreSourceRoot();
        File geodesic = new File(root, "org/locationtech/proj4j/geodesic".replace('/',
                File.separatorChar));
        assertTrue("the vendored geodesic sources are missing from " + root, geodesic.isDirectory());

        List<File> sources = new ArrayList<File>();
        collect(geodesic, sources, ".java");
        assertTrue("no geodesic sources found", sources.size() >= 5);

        int resultBearing = 0;
        TreeSet<String> filesWithHits = new TreeSet<String>();
        for (int i = 0; i < sources.size(); i++) {
            List<Occurrence> found = scan(read(sources.get(i)));
            for (int j = 0; j < found.size(); j++) {
                if (found.get(j).resultBearing) {
                    resultBearing++;
                    filesWithHits.add(sources.get(i).getName());
                }
            }
        }
        // Measured on the vendored tree: GeoMath (sincosd, sincosde, atan2d), Geodesic and
        // GeodesicLine. Requiring a floor rather than an exact count keeps this from breaking on an
        // upstream resync, while still failing outright if the scanner stops detecting anything.
        assertTrue("the scanner found only " + resultBearing + " result-bearing calls in the "
                + "vendored geodesic package, which is fewer than the known positives -- the "
                + "detector has stopped working, so the main assertion proves nothing",
                resultBearing >= 8);
        assertTrue("expected hits in at least 3 geodesic files, got " + filesWithHits,
                filesWithHits.size() >= 3);
    }

    // ------------------------------------------- sensitivity proof 3: the same claim in bytecode

    /**
     * The source scan depends on finding {@code src/main/java}. This one does not: it reads the
     * compiled classes, so the guarantee survives a build-layout change, and it independently
     * confirms that the needle is findable at all.
     */
    @Test
    public void compiledGeodesicClassesStillNameTheJdkMethods() throws IOException {
        File classes = coreClassesDirectory();
        List<File> all = new ArrayList<File>();
        collect(classes, all, ".class");
        assertTrue("found no compiled classes under " + classes, all.size() > 100);

        int geodesicHits = 0;
        int geodesicScanned = 0;
        List<String> convertedButStillNaming = new ArrayList<String>();
        for (int i = 0; i < all.size(); i++) {
            File f = all.get(i);
            String rel = relative(classes, f).replace(File.separatorChar, '/');
            boolean names = namesJdkAngleConversion(f);
            if (rel.startsWith("org/locationtech/proj4j/geodesic/")) {
                geodesicScanned++;
                if (names) {
                    geodesicHits++;
                }
            }
            if (rel.startsWith("org/locationtech/proj4j/util/ProjectionMath")) {
                // The replacement must not be implemented in terms of the thing it replaces.
                if (names) {
                    convertedButStillNaming.add(rel);
                }
            }
        }
        assertTrue("scanned no geodesic classes", geodesicScanned >= 5);
        assertTrue("the bytecode scan found the JDK angle conversions in " + geodesicHits + " of "
                + geodesicScanned + " geodesic classes; finding none would mean the needle is not "
                + "detectable in bytecode and the scan proves nothing", geodesicHits >= 3);
        assertTrue("ProjectionMath must not implement toRad/toDeg via the JDK methods it exists to "
                + "replace: " + convertedButStillNaming, convertedButStillNaming.isEmpty());
    }

    /**
     * The replacements' contract, asserted rather than documented: PROJ's {@code DEG_TO_RAD}
     * idiom, and no dependence on a JDK library body.
     *
     * <p>PROJ's two literals are checked for bit-identity with Java's expressions first, because
     * that identity is what makes {@link ProjectionMath#DTR} usable as {@code DEG_TO_RAD} at all.
     */
    @Test
    public void replacementsUsePROJsDegToRadIdiom() {
        // 9.8.1:src/proj_internal.h:1033-1034, verbatim.
        assertEquals("PROJ's DEG_TO_RAD literal must be bit-identical to Math.PI / 180.0",
                0.017453292519943296, ProjectionMath.DTR, 0.0);
        assertEquals("PROJ's RAD_TO_DEG literal must be bit-identical to 180.0 / Math.PI",
                57.295779513082321, ProjectionMath.RTD, 0.0);

        for (int d = -360; d <= 360; d++) {
            assertEquals("toRad must be deg * DEG_TO_RAD at " + d + " deg",
                    d * ProjectionMath.DTR, ProjectionMath.toRad(d), 0.0);
            double r = d * ProjectionMath.DTR;
            assertEquals("toDeg must be rad / DEG_TO_RAD at " + d + " deg",
                    r / ProjectionMath.DTR, ProjectionMath.toDeg(r), 0.0);
        }

        // The degree direction is a divide on purpose: x * (1/s) is not x / s. Measured at 46 of
        // 721 on Temurin 8u502, 11.0.32 (both arches) and 21.0.11. If this reaches 0, toDeg has
        // been "simplified" to an RTD multiply and fidelity to gnom.cpp/aeqd.cpp was lost.
        int degDiff = 0;
        for (int d = -360; d <= 360; d++) {
            double r = d * ProjectionMath.DTR;
            if (ProjectionMath.toDeg(r) != r * ProjectionMath.RTD) {
                degDiff++;
            }
        }
        assertEquals("rad / DTR must differ from rad * RTD on 46 of the 721 whole-degree images; 0 "
                + "means toDeg was rewritten as an RTD multiply, which is not what PROJ does",
                46, degDiff);
    }

    /**
     * The determinism claim itself, stated as a property rather than a measurement: neither helper
     * may depend on a JDK library body, so both must be expressible in plain arithmetic that a
     * different JVM version cannot reinterpret.
     *
     * <p>This is the assertion that would have caught the original defect. On Temurin 8u502
     * {@code Math.toRadians} disagrees with {@code deg * DTR} on 182 of these 721 values; on 11 and
     * 21 it agrees on all of them. Pinning the helpers to the arithmetic makes the JVM version
     * irrelevant, and this test fails on <em>every</em> JVM if either helper is routed back through
     * {@link Math}.
     */
    @Test
    public void helpersAreVersionStableByConstruction() {
        long radDigest = 1125899906842597L;
        long degDigest = 1125899906842597L;
        for (int d = -360; d <= 360; d++) {
            double r = ProjectionMath.toRad(d);
            radDigest = radDigest * 31 + Double.doubleToRawLongBits(r);
            degDigest = degDigest * 31
                    + Double.doubleToRawLongBits(ProjectionMath.toDeg(d * ProjectionMath.DTR));
        }
        // Measured identically on Temurin 8u502 (x86-64), 11.0.32 (aarch64), 11.0.32 (x86-64) and
        // 21.0.11 (aarch64), over an input set whose own digest was 41b0094159485ebb on all four --
        // so a change here is a change of function, not of argument.
        assertEquals("toRad over [-360,360] must produce this exact bit pattern on every JVM",
                0x45fcec414804d99dL, radDigest);
        assertEquals("toDeg over the same inputs must produce this exact bit pattern on every JVM",
                0xd3fe3f71991ba4bdL, degDigest);
    }

    // ------------------------------------------------------------------------------- the scanner

    /** One detected call site. */
    private static final class Occurrence {
        final int line;
        final boolean resultBearing;
        final String text;

        Occurrence(int line, boolean resultBearing, String text) {
            this.line = line;
            this.resultBearing = resultBearing;
            this.text = text;
        }
    }

    /**
     * Finds every {@code Math.toRadians}/{@code Math.toDegrees} in {@code source} and classifies
     * each as result-bearing or cosmetic.
     *
     * <p>The rule: an occurrence is cosmetic if its line is a comment, or if the text preceding it
     * on that line shows it being appended to a string — a string literal already open on the
     * line, or a line that is the continuation of a concatenation. Everything else is
     * result-bearing. Deliberately conservative in the safe direction: a genuine conversion that
     * merely shares a line with a string literal would be misread as cosmetic, so
     * {@link #classifierDistinguishesResultBearingFromCosmetic()} pins exactly that case.
     */
    private static List<Occurrence> scan(String source) {
        List<Occurrence> out = new ArrayList<Occurrence>();
        String[] lines = source.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            boolean comment = trimmed.startsWith("*") || trimmed.startsWith("//")
                    || trimmed.startsWith("/*");
            for (int k = 0; k < FORBIDDEN.length; k++) {
                String needle = FORBIDDEN[k];
                int at = line.indexOf(needle);
                while (at >= 0) {
                    // Must be a call, not a mention inside a longer identifier.
                    int after = at + needle.length();
                    boolean isCall = after < line.length() && line.charAt(after) == '(';
                    if (isCall) {
                        String before = line.substring(0, at);
                        boolean cosmetic = comment || appendsToString(before);
                        out.add(new Occurrence(i + 1, !cosmetic, trimmed));
                    }
                    at = line.indexOf(needle, at + 1);
                }
            }
        }
        return out;
    }

    /**
     * Whether the text preceding an occurrence shows it being concatenated into a string. Either a
     * quote has already appeared on this line, or the line is a continuation whose preceding text
     * is just a {@code +}.
     */
    private static boolean appendsToString(String before) {
        if (before.indexOf('"') >= 0) {
            // A real conversion can share a line with unrelated string arguments, e.g. a
            // constructor call. Treat it as a message only when the quote is followed by a
            // concatenation operator rather than an argument separator.
            int lastQuote = before.lastIndexOf('"');
            String between = before.substring(lastQuote + 1).trim();
            return between.endsWith("+");
        }
        String t = before.trim();
        return t.equals("+") || t.endsWith("+");
    }

    private static boolean isAllowedPackage(String relativePath) {
        for (int i = 0; i < ALLOWED_PACKAGES.length; i++) {
            if (relativePath.startsWith(ALLOWED_PACKAGES[i])) {
                return true;
            }
        }
        return false;
    }

    /** Whether a class file's constant pool names either JDK angle conversion. */
    private static boolean namesJdkAngleConversion(File classFile) throws IOException {
        byte[] bytes = readBytes(classFile);
        return contains(bytes, "toRadians") || contains(bytes, "toDegrees");
    }

    private static boolean contains(byte[] haystack, String needle) {
        int n = needle.length();
        int limit = haystack.length - n;
        outer:
        for (int i = 0; i <= limit; i++) {
            for (int j = 0; j < n; j++) {
                if (haystack[i + j] != (byte) needle.charAt(j)) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    // ---------------------------------------------------------------------------------- plumbing

    /**
     * Locates {@code core/src/main/java} from the compiled classes rather than from a hard-coded
     * path, so this works under Maven and under an IDE.
     */
    private static File coreSourceRoot() {
        File classes = coreClassesDirectory();
        // .../core/target/classes -> .../core
        File module = classes.getParentFile().getParentFile();
        File src = new File(module, "src" + File.separator + "main" + File.separator + "java");
        if (!src.isDirectory()) {
            throw new IllegalStateException("cannot locate core sources; looked in " + src);
        }
        return src;
    }

    private static File coreClassesDirectory() {
        URL url = CoordinateReferenceSystem.class.getResource(
                "/org/locationtech/proj4j/CoordinateReferenceSystem.class");
        if (url == null) {
            throw new IllegalStateException("cannot locate the compiled core classes");
        }
        String path = url.getPath();
        int index = path.indexOf("/org/locationtech/proj4j/CoordinateReferenceSystem.class");
        File dir = new File(path.substring(0, index).replace("%20", " "));
        if (!dir.isDirectory()) {
            throw new IllegalStateException("compiled core classes are not in a directory: " + dir);
        }
        return dir;
    }

    private static void collect(File dir, List<File> out, String suffix) {
        File[] entries = dir.listFiles();
        if (entries == null) {
            return;
        }
        Arrays.sort(entries);
        for (int i = 0; i < entries.length; i++) {
            if (entries[i].isDirectory()) {
                collect(entries[i], out, suffix);
            } else if (entries[i].getName().endsWith(suffix)) {
                out.add(entries[i]);
            }
        }
    }

    private static String relative(File root, File file) {
        String r = root.getAbsolutePath();
        String f = file.getAbsolutePath();
        return f.startsWith(r) ? f.substring(r.length() + 1) : f;
    }

    private static String read(File f) throws IOException {
        byte[] b = readBytes(f);
        StringBuilder sb = new StringBuilder(b.length);
        for (int i = 0; i < b.length; i++) {
            sb.append((char) (b[i] & 0xFF));
        }
        return sb.toString();
    }

    private static byte[] readBytes(File f) throws IOException {
        InputStream in = new FileInputStream(f);
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
}
