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
package org.locationtech.proj4j.resource;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;

import org.junit.Test;
import org.locationtech.proj4j.CoordinateReferenceSystem;

/**
 * Enforces the two ambient-input guarantees {@code core} makes to a downstream consumer:
 * <b>it reads no environment variable</b>, and <b>it contains no network code</b>.
 *
 * <h2>What this replaces, and why</h2>
 *
 * <p>Both claims used to be asserted by {@code GridResolutionTest.theChainNamesNoEnvironmentVariable},
 * which checked that {@link ResourceResolvers#describeResolution()} <em>contains the strings</em>
 * {@code "environment variables: NONE READ"} and {@code "working directory: NEVER CONSULTED"}.
 * Those are string literals at {@code ResourceResolvers.java:259-260}, so the test asserted two
 * constants against themselves. Its javadoc said "Core must not read {@code PROJ_DATA} or any other
 * environment variable" and <b>nothing enforced that</b>: adding a {@code System.getenv("PROJ_DATA")}
 * tomorrow would have kept it green. The description test still exists and is still worth having —
 * it pins the operator-facing output format — but it is no longer where the guarantee lives.
 *
 * <p>The working-directory half of that claim was never vacuous and is deliberately left where it
 * is: {@code GridResolutionTest.workingDirectoryIsNeverConsulted} writes a real, structurally valid
 * CTABLE V2 grid into {@code user.dir} under a name proj4j is about to resolve and asserts it is
 * ignored. That is the model — a behavioural test with a decoy. Neither of the claims here can be
 * made that way from inside one JVM (a process cannot portably add to its own environment, and
 * proving the absence of a socket by not observing one is not proof), so both are made
 * structurally, over sources <em>and</em> bytecode.
 *
 * <h2>The traps, both of which a naive grep falls into</h2>
 *
 * <ol>
 * <li>{@code DirectoryResourceResolver:37} contains {@code System.getenv("PROJ_DATA")} <b>inside a
 *     javadoc {@code <pre>{@code ...}</pre>} block</b>, showing an application how to opt in to
 *     {@code PROJ_DATA} semantics deliberately. A grep for {@code getenv} over {@code core/src/main}
 *     therefore returns exactly one hit and it is not a violation. The scan must tell code from
 *     comment — and that occurrence is this test's proof that the scanner reaches and detects,
 *     rather than a nuisance to be excluded.</li>
 * <li><b>The bytecode names {@code java.net} types the source never writes.</b>
 *     {@code ClasspathResourceResolver} calls {@code url.openConnection()}, so
 *     {@code java/net/URLConnection} is in its constant pool; {@code DirectoryResourceResolver}
 *     calls {@code Path.toUri()}, so {@code java/net/URI} is in its. Neither string appears
 *     anywhere in {@code core/src/main}. A source-only audit of "which classes touch
 *     {@code java.net}" undercounts by two, which is why the inventory below is pinned from the
 *     class files.</li>
 * </ol>
 *
 * <h2>Why these scans can fail</h2>
 *
 * <p>A scan without a positive control is a claim, not a measurement, so every scan here is proved
 * able to fail before it is trusted to pass:
 *
 * <ul>
 * <li>{@link #theClassifierTellsCodeFromComment()} feeds the classifier crafted lines of both
 *     kinds, including the block-comment continuation and the javadoc snippet shapes that actually
 *     occur.</li>
 * <li>{@link #theSourceScanFindsTheKnownJavadocOccurrence()} requires the production source scan to
 *     find the {@code DirectoryResourceResolver} occurrence and to classify it as a comment. If the
 *     walk reached nothing, the zero-violations result would be vacuous; this makes that
 *     impossible.</li>
 * <li>{@link #theByteScannerDetectsAnInjectedViolation()} writes a synthetic class file containing
 *     {@code getenv} and another containing {@code java/net/Socket} into a temporary directory and
 *     requires the very same collector and byte matcher to report both — and requires a
 *     needle-free control file in the same directory not to be reported.</li>
 * <li>{@link #theByteScannerFindsRealNeedlesInRealCoreClasses()} requires the matcher to find
 *     {@code java/net/URL} in the real core classes that genuinely contain it. This is the control
 *     that a line-oriented grep would have failed: binary class data defeats it, and it silently
 *     returns the clean answer.</li>
 * </ul>
 *
 * <p><b>If a scan here fails, do not add the offender to an allow-list.</b> Both guarantees are
 * load-bearing for a consumer that runs proj4j inside Spark executors, where an environment
 * variable is per-cluster ambient state and a network fetch is a per-row latency cliff.
 */
public class NoAmbientInputInCoreTest {

    // ------------------------------------------------------------------------------- needles

    /** Every way a JVM program can read the process environment. */
    private static final String[] ENV_READERS = {
        "System.getenv", "ProcessBuilder", "ProcessEnvironment"
    };

    /** The bytecode forms of the same. {@code getenv} is the method name in the constant pool. */
    private static final String[] ENV_READERS_BYTECODE = {
        "getenv", "java/lang/ProcessBuilder", "java/lang/ProcessEnvironment"
    };

    /**
     * Types that exist only to talk to a network. None of these may appear anywhere in core, at
     * any level, ever. Substring matches are intentional: {@code java/net/Socket} also catches
     * {@code SocketException} and {@code SocketAddress}, all of which are equally forbidden.
     */
    private static final String[] NETWORK_TYPES = {
        "java/net/Socket", "java/net/ServerSocket", "java/net/DatagramSocket",
        "java/net/MulticastSocket", "java/net/DatagramPacket", "java/net/InetAddress",
        "java/net/InetSocketAddress", "java/net/HttpURLConnection", "java/net/Proxy",
        "java/net/CookieHandler", "java/net/CookieManager", "java/net/Authenticator",
        "java/net/NetworkInterface", "java/net/URLStreamHandler", "java/net/http/",
        "javax/net/", "java/rmi/", "java/nio/channels/SocketChannel",
        "java/nio/channels/ServerSocketChannel", "java/nio/channels/DatagramChannel",
    };

    /**
     * The complete inventory of core classes permitted to name <em>any</em> {@code java.net} type,
     * as outer-class binary names. Measured from the class files, not from the sources — see trap
     * (2) in the class comment.
     *
     * <table border="1">
     * <caption>What each one names, and why it is not network access</caption>
     * <tr><th>class</th><th>types</th><th>why</th></tr>
     * <tr><td>{@code api/Proj}</td><td>{@code URL}</td>
     *     <td>{@code Proj.class.getResource("Proj.class")} to find the jar this class came from,
     *     then {@code openStream()} on a {@code jar:} URL derived from it — guarded by
     *     {@code "jar".equals(self.getProtocol())} at {@code Proj.java:534}, so no other protocol
     *     is ever opened.</td></tr>
     * <tr><td>{@code api/DatabaseInfo}</td><td>{@code URL}</td>
     *     <td>{@code getClassLoader().getResource("proj4/nad/epsg")}, probed for presence only;
     *     never opened.</td></tr>
     * <tr><td>{@code resource/ClasspathResourceResolver}</td><td>{@code URL}, {@code URLConnection}</td>
     *     <td>The return type of {@code ClassLoader.getResource}, never built from a string, and
     *     refused unless its protocol is one of {@code file jar jrt resource bundleresource bundle
     *     vfs} ({@code ClasspathResourceResolver.java:172-193}). {@code URLConnection} is the
     *     inferred type of {@code url.openConnection()} at {@code :198}, used only for
     *     {@code getContentLengthLong()}.</td></tr>
     * <tr><td>{@code resource/DirectoryResourceResolver}</td><td>{@code URI}</td>
     *     <td>The inferred return type of {@code Path.toUri()} at {@code :99}, used to build a
     *     {@code file:} provenance string.</td></tr>
     * </table>
     *
     * <p>This list is exact, not a floor. A new class touching {@code java.net} fails here even if
     * what it does is harmless, because "core contains no network code" is a claim about the whole
     * artifact and the cheapest way to keep it true is to keep the surface closed.
     */
    private static final String[] CLASSES_ALLOWED_TO_NAME_JAVA_NET = {
        "org/locationtech/proj4j/api/DatabaseInfo",
        "org/locationtech/proj4j/api/Proj",
        "org/locationtech/proj4j/resource/ClasspathResourceResolver",
        "org/locationtech/proj4j/resource/DirectoryResourceResolver",
    };

    // ------------------------------------------------------- guard 1: no environment variables

    /**
     * The guarantee {@code ResourceResolvers.describeResolution()} prints and never checked:
     * nothing in {@code core/src/main} reads the process environment.
     */
    @Test
    public void noCoreSourceReadsAnEnvironmentVariable() throws IOException {
        File root = coreSourceRoot();
        List<File> sources = collect(root, ".java");
        assertTrue("found only " + sources.size() + " core sources under " + root
                + ", so this scan would prove nothing", sources.size() > 100);

        List<String> violations = new ArrayList<String>();
        int inComments = 0;
        for (int i = 0; i < sources.size(); i++) {
            String rel = relative(root, sources.get(i));
            List<Occurrence> found = scan(read(sources.get(i)), ENV_READERS);
            for (int j = 0; j < found.size(); j++) {
                Occurrence o = found.get(j);
                if (o.inComment) {
                    inComments++;
                } else {
                    violations.add(rel + ":" + o.line + "  " + o.text);
                }
            }
        }

        assertTrue("the scan found no occurrence of any environment reader at all, not even the "
                + "known javadoc one in DirectoryResourceResolver -- the walk or the matcher is "
                + "broken and a zero-violation result would be meaningless", inComments > 0);

        if (!violations.isEmpty()) {
            fail("core must read no environment variable: PROJ_DATA, PROJ_LIB and PROJ_NETWORK are "
                    + "ambient per-cluster state, and a library that silently honours them gives "
                    + "the same CRS pair different answers on different executors. If an "
                    + "application wants PROJ_DATA semantics it reads the variable itself and hands "
                    + "the path to a DirectoryResourceResolver, which is what "
                    + "DirectoryResourceResolver's javadoc shows. Do NOT allow-list these sites: "
                    + violations);
        }
    }

    /** The same claim in bytecode, so it survives a change of source layout. */
    @Test
    public void noCoreClassCallsGetenv() throws IOException {
        File classes = coreClassesDirectory();
        List<File> all = collect(classes, ".class");
        assertTrue("found only " + all.size() + " compiled classes under " + classes,
                all.size() > 100);

        List<String> offenders = findClassesNaming(classes, all, ENV_READERS_BYTECODE);
        if (!offenders.isEmpty()) {
            fail("no core class may name System.getenv or ProcessBuilder. Offenders: " + offenders);
        }
    }

    // ------------------------------------------------------------------ guard 2: no network code

    /**
     * No core class names a socket, an HTTP client, a name resolver or any other type whose only
     * purpose is a network. This is the claim itself; the inventory test below is what stops the
     * benign {@code java.net} surface from growing into one.
     */
    @Test
    public void noCoreClassNamesANetworkType() throws IOException {
        File classes = coreClassesDirectory();
        List<File> all = collect(classes, ".class");
        assertTrue("found only " + all.size() + " compiled classes under " + classes,
                all.size() > 100);

        List<String> offenders = findClassesNaming(classes, all, NETWORK_TYPES);
        if (!offenders.isEmpty()) {
            fail("core ships no network code -- GridInfo and DbGridAlternative both state this to "
                    + "callers, and a grid reachable only over the network is reported as missing "
                    + "rather than fetched. Offenders: " + offenders);
        }
    }

    /**
     * The {@code java.net} surface is closed, and closed at exactly four classes. Growing it is
     * how "no network code" would erode: every individual addition looks harmless.
     */
    @Test
    public void onlyTheRecordedClassesNameJavaNetAtAll() throws IOException {
        File classes = coreClassesDirectory();
        List<File> all = collect(classes, ".class");

        TreeSet<String> naming = new TreeSet<String>();
        for (int i = 0; i < all.size(); i++) {
            if (contains(readBytes(all.get(i)), "java/net/")) {
                String rel = relative(classes, all.get(i));
                rel = rel.substring(0, rel.length() - ".class".length()).replace(File.separatorChar,
                        '/');
                int nested = rel.indexOf('$');
                naming.add(nested < 0 ? rel : rel.substring(0, nested));
            }
        }

        // Reached-and-detected: the inventory is not empty, so an empty result cannot be mistaken
        // for a clean one.
        assertFalse("no core class names java/net/ at all -- ClasspathResourceResolver certainly "
                + "does, so the byte matcher has stopped working", naming.isEmpty());

        TreeSet<String> expected = new TreeSet<String>(
                Arrays.asList(CLASSES_ALLOWED_TO_NAME_JAVA_NET));
        assertEquals("the set of core classes naming java.net must match the recorded inventory "
                + "exactly; read this class's CLASSES_ALLOWED_TO_NAME_JAVA_NET table before "
                + "changing it", expected.toString(), naming.toString());
    }

    /**
     * The source-level view of the same surface, kept because it is the one a reviewer reads. It
     * deliberately reports a <em>different</em> number from the bytecode inventory: three sites in
     * three files, against four classes, because {@code URLConnection} and {@code URI} are never
     * written down.
     */
    @Test
    public void theJavaNetSourceSitesAreTheRecordedOnes() throws IOException {
        File root = coreSourceRoot();
        List<File> sources = collect(root, ".java");
        assertTrue("found only " + sources.size() + " core sources", sources.size() > 100);

        List<String> code = new ArrayList<String>();
        for (int i = 0; i < sources.size(); i++) {
            String rel = relative(root, sources.get(i)).replace(File.separatorChar, '/');
            List<Occurrence> found = scan(read(sources.get(i)), new String[] {"java.net."});
            for (int j = 0; j < found.size(); j++) {
                if (!found.get(j).inComment) {
                    code.add(rel + ":" + found.get(j).line);
                }
            }
        }
        assertEquals("the java.net source sites have changed; a new one is a new network surface "
                + "and needs the justification the class comment demands",
                "[org/locationtech/proj4j/api/DatabaseInfo.java:103, "
                + "org/locationtech/proj4j/api/Proj.java:533, "
                + "org/locationtech/proj4j/api/Proj.java:543, "
                + "org/locationtech/proj4j/resource/ClasspathResourceResolver.java:23]",
                sorted(code).toString());
    }

    // ------------------------------------------------- positive control 1: the classifier itself

    /**
     * The classifier is the part that could silently stop working, so it is tested directly. The
     * first two cases are the exact shapes that occur in the tree.
     */
    @Test
    public void theClassifierTellsCodeFromComment() {
        // The real DirectoryResourceResolver:37 shape: a javadoc <pre>{@code ...}</pre> snippet.
        // Given whole, so the block tracker is what classifies it ...
        assertComment("/**\n * <pre>{@code\n * String projData = System.getenv(\"PROJ_DATA\");\n"
                + " * }</pre>\n */", "System.getenv");
        // ... and given as the single line alone, so the star rule is what classifies it. Both
        // must reach the same verdict, because the scan sees whole files and this test sees lines.
        assertComment(" * String projData = System.getenv(\"PROJ_DATA\");   // not ours",
                "System.getenv");
        // A block comment whose continuation line does NOT start with a star: only the tracker can
        // get this one right.
        assertComment("/* disabled:\n   System.getenv(\"PROJ_DATA\");\n*/", "System.getenv");
        assertComment("        // String p = System.getenv(\"PROJ_LIB\");", "System.getenv");
        assertComment("     * {@link java.net.Socket} is never used here", "java.net.");

        // Code.
        assertCode("        String p = System.getenv(\"PROJ_DATA\");", "System.getenv");
        assertCode("import java.net.URL;", "import java.net");
        assertCode("        java.net.URL u = X.class.getResource(\"a\");", "java.net.");
        // A trailing comment on a line whose code half carries the needle is still code.
        assertCode("        String p = System.getenv(\"X\"); // deliberate", "System.getenv");
        // Code after a block comment has closed on an earlier line.
        assertCode("/* preamble */\n        String p = System.getenv(\"X\");", "System.getenv");

        // And a line with neither yields nothing at all, so the matcher is not matching everything.
        assertEquals(0, scan("        int x = 1;", ENV_READERS).size());
    }

    // --------------------------------------- positive control 2: the known needle, in the real tree

    /**
     * Requires the <em>production</em> source scan to find the one occurrence known to exist, in
     * the file known to contain it, and to classify it as a comment. This is the analogue of
     * {@code NoJdkAngleConversionTest.scanFindsTheKnownPositivesInTheVendoredGeodesicPackage}: a
     * control that maintains itself, because the thing it depends on is a deliberate part of the
     * design rather than an accident.
     */
    @Test
    public void theSourceScanFindsTheKnownJavadocOccurrence() throws IOException {
        File f = new File(coreSourceRoot(),
                "org/locationtech/proj4j/resource/DirectoryResourceResolver.java"
                        .replace('/', File.separatorChar));
        assertTrue("DirectoryResourceResolver is missing from " + f, f.isFile());

        List<Occurrence> found = scan(read(f), ENV_READERS);
        assertEquals("DirectoryResourceResolver's javadoc shows an application reading PROJ_DATA "
                + "for itself; exactly one occurrence is expected there", 1, found.size());
        assertTrue("and it must be classified as a comment, or the main scan would report it as a "
                + "violation", found.get(0).inComment);
        assertTrue(found.get(0).text.contains("PROJ_DATA"));
    }

    // ------------------------------------- positive control 3: an injected violation, both needles

    /**
     * Writes two synthetic class files, one carrying {@code getenv} and one carrying
     * {@code java/net/Socket}, plus a needle-free control, and runs the production collector and
     * byte matcher over the directory containing them. Both must be reported and the control must
     * not.
     *
     * <p>The synthetic files are real core class bytes with the needle appended, so the matcher is
     * exercised against binary content rather than against a text file. That is the exact failure
     * mode this control exists for: a line-oriented matcher returns a clean zero on binary class
     * data and looks like a pass.
     */
    @Test
    public void theByteScannerDetectsAnInjectedViolation() throws IOException {
        File dir = createTempDirectory("proj4j-injected-violation");
        try {
            byte[] carrier = readBytes(anyCoreClassFile());
            File clean = write(new File(dir, "Clean.class"), carrier, null);
            File env = write(new File(dir, "Env.class"), carrier, "System.getenv");
            File net = write(new File(dir, "Net.class"), carrier, "java/net/Socket");

            List<File> collected = collect(dir, ".class");
            assertEquals("the collector must see all three files", 3, collected.size());

            List<String> envHits = findClassesNaming(dir, collected, ENV_READERS_BYTECODE);
            assertEquals("[Env.class]", sorted(envHits).toString());

            List<String> netHits = findClassesNaming(dir, collected, NETWORK_TYPES);
            assertEquals("[Net.class]", sorted(netHits).toString());

            // And the control file, byte-identical but for the needle, is reported by neither.
            assertFalse(contains(readBytes(clean), "getenv"));
            assertFalse(contains(readBytes(clean), "java/net/Socket"));
            assertTrue(contains(readBytes(env), "getenv"));
            assertTrue(contains(readBytes(net), "java/net/Socket"));
        } finally {
            deleteRecursively(dir);
        }
    }

    /**
     * And the same matcher against real needles in real core classes, so its sensitivity is proved
     * on the actual corpus and not only on a fabrication.
     */
    @Test
    public void theByteScannerFindsRealNeedlesInRealCoreClasses() throws IOException {
        File classes = coreClassesDirectory();
        List<File> all = collect(classes, ".class");

        int namingJavaNet = 0;
        int namingOwnPackage = 0;
        for (int i = 0; i < all.size(); i++) {
            byte[] b = readBytes(all.get(i));
            if (contains(b, "java/net/")) {
                namingJavaNet++;
            }
            if (contains(b, "org/locationtech/proj4j/")) {
                namingOwnPackage++;
            }
        }
        assertTrue("the matcher found java/net/ in " + namingJavaNet + " core classes; at least "
                + "four genuinely contain it, so a smaller number means it has stopped detecting",
                namingJavaNet >= 4);
        assertTrue("the matcher found its own package path in only " + namingOwnPackage + " of "
                + all.size() + " classes -- essentially all of them contain it, so this is the "
                + "control that the binary scan works at all", namingOwnPackage > all.size() / 2);
    }

    // --------------------------------------------------------------------------- the scanner

    /** One detected occurrence, and whether it is inside a comment. */
    private static final class Occurrence {
        final int line;
        final boolean inComment;
        final String text;

        Occurrence(int line, boolean inComment, String text) {
            this.line = line;
            this.inComment = inComment;
            this.text = text;
        }
    }

    /**
     * Finds every needle in {@code source} and records whether it sits inside a comment.
     *
     * <p>Tracks {@code /* ... *}{@code /} across lines rather than only testing whether a line
     * starts with a star, because a block comment's continuation lines need not, and a needle on
     * such a line is still a comment. A {@code //} is honoured only when it opens <em>before</em>
     * the needle, so a trailing comment on a line of real code does not launder the code.
     */
    private static List<Occurrence> scan(String source, String[] needles) {
        List<Occurrence> out = new ArrayList<Occurrence>();
        String[] lines = source.split("\n", -1);
        boolean inBlock = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            for (int k = 0; k < needles.length; k++) {
                int at = line.indexOf(needles[k]);
                while (at >= 0) {
                    out.add(new Occurrence(i + 1, inBlock || isCommentedAt(line, at), line.trim()));
                    at = line.indexOf(needles[k], at + 1);
                }
            }
            inBlock = blockCommentStateAfter(line, inBlock);
        }
        return out;
    }

    /**
     * Whether the text before {@code index} on this line makes the occurrence a comment: a
     * {@code /*} still open, a {@code //}, or a leading {@code *} continuation marker.
     *
     * <p>The star rule is not redundant with the block tracker. It catches the case where the scan
     * begins part-way through a file — and, more usefully, it is what makes a single crafted line
     * classifiable in isolation, which is how {@link #theClassifierTellsCodeFromComment()} can test
     * the rule without reconstructing a whole file. No Java statement can begin with {@code *}, so
     * it has no false-positive direction.
     */
    private static boolean isCommentedAt(String line, int index) {
        String before = line.substring(0, index);
        if (before.trim().startsWith("*")) {
            return true;
        }
        int block = before.indexOf("/*");
        if (block >= 0 && before.indexOf("*/", block) < 0) {
            return true;
        }
        return before.indexOf("//") >= 0;
    }

    /** The block-comment state carried into the next line. */
    private static boolean blockCommentStateAfter(String line, boolean inBlock) {
        int i = 0;
        while (i < line.length()) {
            if (inBlock) {
                int close = line.indexOf("*/", i);
                if (close < 0) {
                    return true;
                }
                inBlock = false;
                i = close + 2;
            } else {
                int lineComment = line.indexOf("//", i);
                int open = line.indexOf("/*", i);
                if (open < 0 || (lineComment >= 0 && lineComment < open)) {
                    return false;
                }
                inBlock = true;
                i = open + 2;
            }
        }
        return inBlock;
    }

    private static void assertCode(String source, String needle) {
        List<Occurrence> found = scan(source, new String[] {needle});
        assertEquals("expected exactly one occurrence in: " + source, 1, found.size());
        assertFalse("should have been classified as code: " + source, found.get(0).inComment);
    }

    private static void assertComment(String source, String needle) {
        List<Occurrence> found = scan(source, new String[] {needle});
        assertEquals("expected exactly one occurrence in: " + source, 1, found.size());
        assertTrue("should have been classified as a comment: " + source, found.get(0).inComment);
    }

    // ---------------------------------------------------------------------------- byte matching

    private static List<String> findClassesNaming(File root, List<File> classFiles,
            String[] needles) throws IOException {
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < classFiles.size(); i++) {
            byte[] bytes = readBytes(classFiles.get(i));
            for (int k = 0; k < needles.length; k++) {
                if (contains(bytes, needles[k])) {
                    out.add(relative(root, classFiles.get(i)).replace(File.separatorChar, '/'));
                    break;
                }
            }
        }
        return out;
    }

    /**
     * A plain byte-for-byte search. Deliberately not a line-oriented one: class files are binary,
     * and a {@code grep} over them returns a confident zero.
     */
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

    private static File coreSourceRoot() {
        File module = coreClassesDirectory().getParentFile().getParentFile();
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

    private static File anyCoreClassFile() {
        return new File(coreClassesDirectory(),
                "org/locationtech/proj4j/CoordinateReferenceSystem.class".replace('/',
                        File.separatorChar));
    }

    private static List<File> collect(File dir, String suffix) {
        List<File> out = new ArrayList<File>();
        collect(dir, out, suffix);
        return out;
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

    private static List<String> sorted(List<String> in) {
        List<String> copy = new ArrayList<String>(in);
        java.util.Collections.sort(copy);
        return copy;
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

    private static File write(File target, byte[] carrier, String needle) throws IOException {
        FileOutputStream out = new FileOutputStream(target);
        try {
            out.write(carrier);
            if (needle != null) {
                for (int i = 0; i < needle.length(); i++) {
                    out.write((byte) needle.charAt(i));
                }
            }
        } finally {
            out.close();
        }
        return target;
    }

    private static File createTempDirectory(String prefix) throws IOException {
        File f = File.createTempFile(prefix, "");
        if (!f.delete() || !f.mkdir()) {
            throw new IOException("cannot create a temporary directory at " + f);
        }
        return f;
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
}
