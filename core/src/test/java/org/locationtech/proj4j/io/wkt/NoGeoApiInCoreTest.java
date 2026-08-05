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
package org.locationtech.proj4j.io.wkt;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URL;
import java.nio.channels.FileChannel;
import java.nio.MappedByteBuffer;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.Assume;
import org.junit.Test;
import org.locationtech.proj4j.CoordinateReferenceSystem;

/**
 * Asserts that the compiled {@code core} classes contain no reference whatsoever to
 * {@code org.opengis.*}, by scanning their bytes for the string {@code org/opengis/}.
 * <p>
 * This is the mechanical guarantee behind a claim a downstream consumer depends on. That consumer
 * falls back to Apache SIS for WKT this library cannot parse, and SIS drags in GeoAPI; two
 * incompatible copies of {@code org.opengis.util.CodeList} then end up on one classpath —
 * {@code geoapi-3.0.2} has {@code names()}, {@code gt-opengis-29.6} does not — and whichever jar
 * wins decides whether a {@code NoSuchMethodError} is thrown from inside SIS's WKT parser. That is
 * an {@code Error}, not an {@code Exception}: it passes local tests and kills Spark executors. The
 * only way to be rid of it is to stop needing SIS, which means WKT and PROJJSON support must be in
 * core with no GeoAPI anywhere near it.
 * <p>
 * {@code proj4j-geoapi} is a separate, optional module and is itself a participant in that hazard.
 * Core must never reference it, not even through an optional dependency or a soft
 * {@code ServiceLoader} probe. A comment saying so is not enforcement; this test is.
 * <p>
 * If this test fails, do not relax it. The offending class is naming a GeoAPI type, and the fix is
 * to remove that reference from core, not to allow it here.
 *
 * <h2>How this test proves it can fail — and a retraction</h2>
 *
 * <p>A scan without a positive control is a claim, not a measurement, and the failure mode is
 * silent: it reports exactly what you hoped for. The specific instrument failure this guards
 * against is documented, because it happened: an {@code unzip -p | grep -c} over the jars returned
 * <b>0 for core and 0 for the {@code geoapi} jar</b>, which contains 341 references — binary class
 * data defeats a line-oriented matcher, and the clean result had been produced by an instrument
 * that could not detect anything. {@link #contains} is a raw byte search for exactly that reason.
 *
 * <p><b>This class was for a period cited elsewhere as already carrying that control, and it did
 * not.</b> The claim was that it "verified itself by finding the string in 28 of 32 {@code geoapi}
 * classes"; in fact its only guard was {@link #assertScanned}, which checks <em>coverage</em> — did
 * the walk reach package X — and never that the byte matcher can find {@code org/opengis/} when it
 * is present. The 28-of-32 was an external measurement attributed to a test that never ran it. It
 * is now actually run, by {@link #theGeoApiModuleIsFullOfTheNeedleThisScanLooksFor()}, and the four
 * classes without the needle are the package-info and the two service-descriptor holders that name
 * no GeoAPI type.
 *
 * <p>Three controls, in increasing order of how much they depend on the environment:
 * <ol>
 * <li>{@link #theByteMatcherDetectsAnInjectedViolation()} — always runs. Fabricates a class file
 *     carrying the needle and requires the production collector and matcher to report it, and a
 *     needle-free twin not to be.</li>
 * <li>{@link #theByteMatcherFindsRealPackagePathsInRealClasses()} — always runs. Requires the
 *     matcher to find {@code org/locationtech/proj4j/} in the real core classes, so its ability to
 *     read binary constant pools is proved on the actual corpus.</li>
 * <li>{@link #theGeoApiModuleIsFullOfTheNeedleThisScanLooksFor()} and
 *     {@link #theShippedJarContainsNoGeoApi()} — <b>skipped, not passed</b>, when their input does
 *     not exist. Both are ordering artefacts of the build rather than of this code: surefire runs
 *     in the {@code test} phase, before {@code package} writes the jar, and the reactor builds
 *     {@code core} before {@code geoapi}, so on a clean {@code mvn install} neither input is there
 *     yet. They run on any second build, and locally. See {@link org.junit.Assume}.</li>
 * </ol>
 */
public class NoGeoApiInCoreTest {

    /** The byte sequence a class file would contain if it named any GeoAPI type. */
    private static final byte[] FORBIDDEN = toBytes("org/opengis/");

    /** The dotted form, which would appear in a {@code Class.forName} string constant. */
    private static final byte[] FORBIDDEN_DOTTED = toBytes("org.opengis.");

    @Test
    public void coreClassesDoNotReferenceGeoApi() throws IOException {
        File classes = coreClassesDirectory();
        List<File> classFiles = new ArrayList<File>();
        collect(classes, classFiles);
        assertTrue("found no compiled classes to scan under " + classes, classFiles.size() > 100);

        List<String> offenders = new ArrayList<String>();
        for (int i = 0; i < classFiles.size(); i++) {
            File f = classFiles.get(i);
            if (contains(f, FORBIDDEN) || contains(f, FORBIDDEN_DOTTED)) {
                offenders.add(relative(classes, f));
            }
        }
        if (!offenders.isEmpty()) {
            fail("core must contain no reference to org.opengis.* — the duplicate CodeList hazard "
                    + "is why WKT support lives in core at all. Offending classes: " + offenders);
        }
        assertScanned(classes, classFiles, "org/locationtech/proj4j/io/wkt");
        assertScanned(classes, classFiles, "org/locationtech/proj4j/io/projjson");
        // The public facade is the surface a consumer actually holds, and the claim it makes -- that
        // deleting Apache SIS is possible -- is only true if every signature on it uses java.* and
        // org.locationtech.proj4j.* alone. The scan above already covers it, because it walks the
        // whole classes tree; this line makes that coverage a stated requirement rather than an
        // accident of where the tree root happens to be, so that a future packaging change which
        // excluded the package would fail here instead of silently weakening the guarantee.
        assertScanned(classes, classFiles, "org/locationtech/proj4j/api");
    }

    /**
     * Asserts that the scan actually reached a package. A scan that silently covered nothing would
     * pass the offender check trivially, which is the one way this test could give a false negative.
     */
    private static void assertScanned(File root, List<File> scanned, String packagePath) {
        String needle = packagePath.replace('/', File.separatorChar) + File.separatorChar;
        for (int i = 0; i < scanned.size(); i++) {
            if (relative(root, scanned.get(i)).startsWith(needle)) {
                return;
            }
        }
        fail("the org.opengis scan did not reach " + packagePath + " under " + root
                + ", so it proves nothing about that package");
    }

    /**
     * The same constraint stated at the class-loading level: core must be usable with no GeoAPI on
     * the classpath at all, so nothing core needs may resolve to a GeoAPI type.
     *
     * <h4>This test was vacuous, and this is what it used to be</h4>
     *
     * <p>{@code assertFalse(isPresent("org.opengis.util.CodeList") && wktPackageUsesIt())}. Core's
     * test classpath is {@code proj4j-epsg} plus {@code junit} and nothing else, so
     * {@code isPresent} is {@code false}, {@code &&} never evaluates its right operand,
     * {@code wktPackageUsesIt()} never ran, and {@code assertFalse(false)} passed unconditionally.
     * It reported a guarantee it never evaluated.
     *
     * <p>The two halves are now separate assertions, and both mean something:
     * <ul>
     * <li><b>GeoAPI really is absent from the classpath this ran on.</b> Without that fact the
     *     second assertion proves nothing, because WKT could be working <em>via</em> GeoAPI. If
     *     GeoAPI is ever added to core's test scope this fails, and correctly so — the guarantee is
     *     that core needs no GeoAPI, and the only way to keep testing that is to keep it off the
     *     classpath.</li>
     * <li><b>The whole WKT round trip runs anyway.</b> Read, parse to a definition, and write it
     *     back. Any GeoAPI type on a code path this touches would be a
     *     {@link NoClassDefFoundError} at link time, not a silently skipped branch.</li>
     * </ul>
     */
    @Test
    public void wktSupportLinksAndRunsWithNoGeoApiOnTheClasspath() {
        assertFalse("GeoAPI is on core's test classpath, so the round trip below could be "
                        + "succeeding through it; the point of this test is that it cannot",
                isPresent("org.opengis.util.CodeList"));
        // Not a boolean handed to an && that may never evaluate it: called, and its result checked.
        assertTrue("the WKT round trip must complete with no GeoAPI present", wktRoundTripWorks());
    }

    private boolean wktRoundTripWorks() {
        // Reading and writing WKT exercises the whole package; if any of it needed GeoAPI, this
        // would fail to link rather than return.
        String wkt = "GEOGCS[\"WGS 84\",DATUM[\"WGS_1984\",SPHEROID[\"WGS 84\",6378137,"
                + "298.257223563]],PRIMEM[\"Greenwich\",0],UNIT[\"degree\",0.0174532925199433]]";
        CoordinateReferenceSystem crs = new WktReader().read(wkt);
        CrsDefinition def = new WktReader().readDefinition(wkt);
        String written = new WktWriter().write(def);
        return crs != null && written != null && written.length() > 0;
    }

    // ------------------------------------------------------------------- positive controls

    /**
     * Always runs. Fabricates two class files from the same real core class bytes, one with
     * {@code org/opengis/util/CodeList} appended, and requires the production collector and byte
     * matcher to report exactly the one carrying the needle.
     *
     * <p>The carrier is real binary class content rather than a text file, because that is the
     * distinction that broke the previous instrument.
     */
    @Test
    public void theByteMatcherDetectsAnInjectedViolation() throws IOException {
        File dir = createTempDirectory("proj4j-geoapi-injection");
        try {
            byte[] carrier = readAll(new File(coreClassesDirectory(),
                    "org/locationtech/proj4j/CoordinateReferenceSystem.class"
                            .replace('/', File.separatorChar)));
            File clean = writeWithSuffix(new File(dir, "Clean.class"), carrier, null);
            File dirty = writeWithSuffix(new File(dir, "Dirty.class"), carrier,
                    "org/opengis/util/CodeList");
            File dotted = writeWithSuffix(new File(dir, "Dotted.class"), carrier,
                    "org.opengis.util.CodeList");

            List<File> collected = new ArrayList<File>();
            collect(dir, collected);
            assertTrue("the collector must see all three fabricated class files",
                    collected.size() == 3);

            assertFalse("the needle-free control must not be reported",
                    contains(clean, FORBIDDEN) || contains(clean, FORBIDDEN_DOTTED));
            assertTrue("the slash form must be detected", contains(dirty, FORBIDDEN));
            assertTrue("the dotted form, which is how a Class.forName constant would look, must be "
                    + "detected too", contains(dotted, FORBIDDEN_DOTTED));
        } finally {
            deleteRecursively(dir);
        }
    }

    /**
     * Always runs. The matcher's ability to find a package path inside a real, compiled constant
     * pool, proved against the corpus actually being scanned rather than against a fabrication.
     */
    @Test
    public void theByteMatcherFindsRealPackagePathsInRealClasses() throws IOException {
        File classes = coreClassesDirectory();
        List<File> classFiles = new ArrayList<File>();
        collect(classes, classFiles);
        assertTrue("found no compiled classes under " + classes, classFiles.size() > 100);

        byte[] ownPackage = toBytes("org/locationtech/proj4j/");
        int found = 0;
        for (int i = 0; i < classFiles.size(); i++) {
            if (contains(classFiles.get(i), ownPackage)) {
                found++;
            }
        }
        assertTrue("the matcher found its own package path in only " + found + " of "
                + classFiles.size() + " core classes; essentially all of them contain it, so a low "
                + "number means the matcher cannot read binary constant pools and every clean "
                + "result above is meaningless", found > classFiles.size() / 2);
    }

    /**
     * The control the skill claimed this class already had. Points the very same matcher at the
     * {@code proj4j-geoapi} module, which exists to reference GeoAPI, and requires it to find the
     * needle in the great majority of its classes.
     *
     * <p>Measured at the time of writing: <b>28 of 32</b>, identically in {@code target/classes}
     * and in the packaged jar. A floor rather than an exact count, so an added class does not break
     * it, but a floor high enough that a matcher which had stopped working cannot clear it.
     *
     * <p><b>Skipped rather than passed when the module has not been built.</b> The reactor builds
     * {@code core} before {@code geoapi}, so on a clean {@code mvn install} this input does not
     * exist while core's tests run. It runs on any subsequent build and in any IDE.
     */
    @Test
    public void theGeoApiModuleIsFullOfTheNeedleThisScanLooksFor() throws IOException {
        File geoapiClasses = new File(repositoryRoot(),
                ("geoapi" + File.separator + "target" + File.separator + "classes"));
        Assume.assumeTrue("proj4j-geoapi has not been compiled yet (" + geoapiClasses
                + "); the reactor builds core first, so this control runs on a second build",
                geoapiClasses.isDirectory());

        List<File> classFiles = new ArrayList<File>();
        collect(geoapiClasses, classFiles);
        assertTrue("the geoapi module compiled to only " + classFiles.size() + " classes",
                classFiles.size() >= 20);

        int hits = 0;
        for (int i = 0; i < classFiles.size(); i++) {
            if (contains(classFiles.get(i), FORBIDDEN) || contains(classFiles.get(i),
                    FORBIDDEN_DOTTED)) {
                hits++;
            }
        }
        assertTrue("the matcher found org/opengis/ in only " + hits + " of " + classFiles.size()
                + " proj4j-geoapi classes; that module exists to reference GeoAPI, so anything "
                + "below three quarters means the matcher has stopped detecting and core's clean "
                + "result proves nothing", hits * 4 >= classFiles.size() * 3);
    }

    /**
     * The claim is about the <b>shipped artifact</b>, and everything above is about
     * {@code target/classes}. This closes that gap by reading the jar itself.
     *
     * <p><b>Skipped rather than passed when the jar has not been built.</b> Surefire runs in the
     * {@code test} phase and {@code maven-jar-plugin} in {@code package}, so during the build that
     * runs this test the jar is not there yet. Making it unconditional needs a {@code pom.xml}
     * change — either binding {@code jar:jar} to {@code process-test-classes}, or moving this class
     * to {@code maven-failsafe-plugin} — which is out of scope here.
     */
    @Test
    public void theShippedJarContainsNoGeoApi() throws IOException {
        File jar = coreJar();
        Assume.assumeTrue("core's jar has not been packaged yet; surefire runs before package, so "
                + "this leg needs a pom change to be unconditional", jar != null);

        List<String> offenders = new ArrayList<String>();
        int classesSeen = 0;
        ZipFile zip = new ZipFile(jar);
        try {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (e.isDirectory() || !e.getName().endsWith(".class")) {
                    continue;
                }
                classesSeen++;
                byte[] bytes = readAll(zip.getInputStream(e));
                if (indexOf(bytes, FORBIDDEN) >= 0 || indexOf(bytes, FORBIDDEN_DOTTED) >= 0) {
                    offenders.add(e.getName());
                }
            }
        } finally {
            zip.close();
        }
        assertTrue("the jar at " + jar + " holds only " + classesSeen + " classes, so it is not "
                + "the artifact this claim is about", classesSeen > 100);
        if (!offenders.isEmpty()) {
            fail("the shipped jar names org.opengis.*: " + offenders);
        }
    }

    /** The most recently written {@code proj4j-*.jar} in {@code core/target}, or null. */
    private static File coreJar() {
        File target = coreClassesDirectory().getParentFile();
        File[] candidates = target.listFiles();
        if (candidates == null) {
            return null;
        }
        File best = null;
        for (int i = 0; i < candidates.length; i++) {
            String n = candidates[i].getName();
            if (!n.endsWith(".jar") || n.endsWith("-sources.jar") || n.endsWith("-javadoc.jar")) {
                continue;
            }
            if (best == null || candidates[i].lastModified() > best.lastModified()) {
                best = candidates[i];
            }
        }
        return best;
    }

    /** {@code .../core/target/classes} to the repository root. */
    private static File repositoryRoot() {
        return coreClassesDirectory().getParentFile().getParentFile().getParentFile();
    }

    private static File createTempDirectory(String prefix) throws IOException {
        File f = File.createTempFile(prefix, "");
        if (!f.delete() || !f.mkdir()) {
            throw new IOException("cannot create a temporary directory at " + f);
        }
        return f;
    }

    private static File writeWithSuffix(File target, byte[] carrier, String suffix)
            throws IOException {
        FileOutputStream out = new FileOutputStream(target);
        try {
            out.write(carrier);
            if (suffix != null) {
                out.write(toBytes(suffix));
            }
        } finally {
            out.close();
        }
        return target;
    }

    private static void deleteRecursively(File dir) {
        File[] entries = dir.listFiles();
        if (entries != null) {
            for (int i = 0; i < entries.length; i++) {
                if (entries[i].isDirectory()) {
                    deleteRecursively(entries[i]);
                } else if (!entries[i].delete()) {
                    entries[i].deleteOnExit();
                }
            }
        }
        if (!dir.delete()) {
            dir.deleteOnExit();
        }
    }

    private static byte[] readAll(File f) throws IOException {
        return readAll(new java.io.FileInputStream(f));
    }

    private static byte[] readAll(java.io.InputStream in) throws IOException {
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
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

    /** The same raw byte search as {@link #contains(File, byte[])}, over an array. */
    private static int indexOf(byte[] haystack, byte[] needle) {
        int limit = haystack.length - needle.length;
        outer:
        for (int i = 0; i <= limit; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static boolean isPresent(String className) {
        try {
            Class.forName(className, false, NoGeoApiInCoreTest.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Locates the directory holding this module's compiled main classes, from the location of a
     * core class rather than from a hard-coded path, so the test works under Maven and under an
     * IDE.
     */
    private static File coreClassesDirectory() {
        URL url = CoordinateReferenceSystem.class.getResource(
                "/org/locationtech/proj4j/CoordinateReferenceSystem.class");
        if (url == null) {
            throw new IllegalStateException("cannot locate the compiled core classes");
        }
        String path = url.getPath();
        int index = path.indexOf("/org/locationtech/proj4j/CoordinateReferenceSystem.class");
        File dir = new File(decode(path.substring(0, index)));
        if (!dir.isDirectory()) {
            throw new IllegalStateException("compiled core classes are not in a directory: " + dir);
        }
        return dir;
    }

    private static String decode(String path) {
        // Enough of URL decoding for a build path: spaces are the only realistic escape.
        return path.replace("%20", " ");
    }

    private static void collect(File dir, List<File> out) {
        File[] entries = dir.listFiles();
        if (entries == null) {
            return;
        }
        for (int i = 0; i < entries.length; i++) {
            if (entries[i].isDirectory()) {
                collect(entries[i], out);
            } else if (entries[i].getName().endsWith(".class")) {
                out.add(entries[i]);
            }
        }
    }

    private static String relative(File root, File file) {
        String r = root.getAbsolutePath();
        String f = file.getAbsolutePath();
        return f.startsWith(r) ? f.substring(r.length() + 1) : f;
    }

    private static boolean contains(File file, byte[] needle) throws IOException {
        RandomAccessFile raf = new RandomAccessFile(file, "r");
        try {
            FileChannel channel = raf.getChannel();
            MappedByteBuffer buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0,
                    channel.size());
            int limit = buffer.limit() - needle.length;
            outer:
            for (int i = 0; i <= limit; i++) {
                for (int j = 0; j < needle.length; j++) {
                    if (buffer.get(i + j) != needle[j]) {
                        continue outer;
                    }
                }
                return true;
            }
            return false;
        } finally {
            raf.close();
        }
    }

    private static byte[] toBytes(String s) {
        byte[] b = new byte[s.length()];
        for (int i = 0; i < s.length(); i++) {
            b[i] = (byte) s.charAt(i);
        }
        return b;
    }
}
