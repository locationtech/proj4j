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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.After;
import org.junit.Test;
import org.locationtech.proj4j.datum.Grid;
import org.locationtech.proj4j.datum.GridCache;
import org.locationtech.proj4j.resource.ChainedResourceResolver;
import org.locationtech.proj4j.resource.ClasspathResourceResolver;
import org.locationtech.proj4j.resource.DirectoryResourceResolver;
import org.locationtech.proj4j.resource.ResourceHandle;
import org.locationtech.proj4j.resource.ResourceResolver;
import org.locationtech.proj4j.resource.ResourceResolvers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The resolver chain: determinism, the absence of the working directory, and the visibility of PROJ's
 * {@code @}-optional silent skip.
 *
 * <h2>Why the working directory mattered</h2>
 * <p>1.4.3's {@code Grid.resolveGridDefinition} did {@code new File(gridName)} <strong>first</strong>. On
 * a Spark or Flink executor the working directory is a framework-chosen container work dir, shared
 * between tasks in the same container and writable by {@code --files} staging, shuffle spill and
 * arbitrary user UDF code. A file named {@code conus} or {@code ntv2_0.gsb} landing there outranked the
 * packaged grid, so the same CRS pair and the same coordinate could produce a different number on
 * different executors and on different runs of the same job — with no diagnostic anywhere. It was also
 * an untrusted-input file open: {@code gridName} comes from a {@code +nadgrids=} token in a per-row CRS
 * string, and {@code new File} accepts {@code ../} and absolute paths.
 */
public class GridResolutionTest {

    @After
    public void resetChain() {
        ResourceResolvers.clearResolvers();
        GridCache.instance().clear();
    }

    // --- the working directory is never consulted ---------------------------------------------

    /**
     * Writes a valid CTABLE V2 grid into the process working directory under a name proj4j is about to
     * resolve, and proves it is ignored. The file has a deliberately different extent, so if it were
     * picked up the coordinate would move.
     */
    @Test
    public void workingDirectoryIsNeverConsulted() throws IOException {
        String cwd = System.getProperty("user.dir");
        File decoy = new File(cwd, "proj4j-cwd-decoy-grid");
        FileOutputStream out = new FileOutputStream(decoy);
        try {
            out.write(syntheticCtable2());
        } finally {
            out.close();
        }
        try {
            List<Grid> list = new ArrayList<Grid>();
            try {
                Grid.mergeGridFile("proj4j-cwd-decoy-grid", list);
                fail("a grid present only in the working directory must not resolve; it did, from "
                        + list.get(0).getOrigin());
            } catch (IOException expected) {
                assertTrue("the message should say the working directory is not searched: "
                                + expected.getMessage(),
                        expected.getMessage().contains("working directory"));
            }
        } finally {
            assertTrue(decoy.delete() || !decoy.exists());
        }
    }

    /**
     * The other half of the same defect: a traversal or an absolute path in a {@code +nadgrids=} token
     * must not open a file. In 1.4.3, {@code +nadgrids=/etc/passwd} opened and read the file, then
     * failed all three format tests and produced a {@code format = "missing"} no-op grid rather than
     * throwing — a file-existence and readability oracle driven by row data.
     *
     * <h4>This list changed shape when interior path segments were permitted</h4>
     *
     * <p>The guard used to reject any name containing {@code /}, which also refused
     * {@code tests/us_noaa_nadcon5_conus.tif} — a spelling PROJ supports (it appends the whole token
     * to a search directory) and one the conformance corpus depends on. Permitting it means
     * {@code subdir/conus}, which used to be in this list, is now a <em>legal</em> name that happens
     * not to exist; it moved to {@link #aLegalNameThatDoesNotExistFailsForADifferentReason()}.
     *
     * <p>That split is deliberate. It <em>used</em> to be weak: end-to-end both cases produced the
     * same {@code IOException} saying "Unknown grid", so this list would have kept passing with the
     * guard deleted — it was really only asserting that the names are not present. They are now
     * distinguishable, because {@code Grid.resolveAndLoad} applies {@code ResourceNames} before the
     * resolver chain and reports <em>which</em> clause refused the name. So this asserts the
     * refusal, by rule and by name, and {@link #aLegalNameThatDoesNotExistFailsForADifferentReason()}
     * asserts that a legal-but-absent name still reaches the chain and fails differently. Deleting
     * the guard now fails this test rather than passing it.
     *
     * <p>The rule itself is pinned exhaustively in
     * {@code org.locationtech.proj4j.resource.ResourceNameSafetyTest}; what is asserted here is that
     * it is actually wired into the path a {@code +nadgrids=} token travels.
     */
    @Test
    public void pathsInAGridNameAreRejectedRatherThanOpened() {
        String[] hostile = {
                // traversal
                "../../../etc/passwd",
                "tests/../../etc/passwd",
                "a/./b",
                "..",
                ".",
                // absolute
                "/etc/passwd",
                "/tests/foo.tif",
                "\\tests\\foo.tif",
                "..\\..\\windows\\win.ini",
                // empty segments
                "a//b",
                "tests/",
                // whitespace, and a URL escape that spells "../" without using '.' or '/'
                "dir with space/myconus",
                "%2e%2e%2fetc/passwd",
                // a drive letter and a URL scheme
                "C:/windows/win.ini",
                "file:///etc/passwd",
        };
        for (String name : hostile) {
            org.locationtech.proj4j.resource.ResourceNames.Rule rule =
                    org.locationtech.proj4j.resource.ResourceNames.violation(name);
            assertNotNull("this list is only evidence if every name really does break the rule; '"
                    + name + "' does not", rule);

            List<Grid> list = new ArrayList<Grid>();
            try {
                Grid.mergeGridFile(name, list);
                fail("'" + name + "' must not resolve to anything");
            } catch (IOException expected) {
                assertTrue("'" + name + "' must be refused by name, not merely not found; got: "
                                + expected.getMessage(),
                        expected.getMessage().contains("Refusing grid name"));
                assertTrue("the refusal must name the clause that fired (" + rule + ") for '"
                                + name + "'; got: " + expected.getMessage(),
                        expected.getMessage().contains(rule.toString()));
                assertFalse("a refused name must NOT be reported as a lookup miss, or this test "
                                + "cannot tell the guard from the absence of the file: " + name,
                        expected.getMessage().contains("Unknown grid"));
            }
        }
    }

    /**
     * The control for the test above. {@code subdir/conus} is now accepted by the name rule and
     * simply is not present, so it fails at lookup rather than at validation — which is what makes
     * the hostile list above evidence about the guard rather than about the filesystem.
     *
     * <p>And the unlock itself, positively: a grid really does resolve through an interior path
     * segment. Without this the relaxation could have been a no-op and every other test here would
     * still be green.
     */
    @Test
    public void aLegalNameThatDoesNotExistFailsForADifferentReason() throws IOException {
        assertTrue("an interior path segment is a legal grid name now",
                org.locationtech.proj4j.resource.ResourceResolvers.resolver()
                        .resolve("subdir/conus") == null);

        List<Grid> list = new ArrayList<Grid>();
        try {
            Grid.mergeGridFile("subdir/conus", list);
            fail("'subdir/conus' is legal but absent, so it must still fail");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("Unknown grid"));
        }

        Path root = Files.createTempDirectory("proj4j-subdir-grid");
        try {
            Files.createDirectory(root.resolve("subdir"));
            Files.write(root.resolve("subdir").resolve("conus"),
                    Files.readAllBytes(conusOnClasspath()));
            ResourceResolvers.addResolver(new DirectoryResourceResolver(root));
            GridCache.instance().clear();

            List<Grid> found = new ArrayList<Grid>();
            Grid.mergeGridFile("subdir/conus", found);
            assertEquals(1, found.size());
            assertEquals("subdir/conus", found.get(0).getGridName());
            assertEquals("ctable2", found.get(0).getFormat());
        } finally {
            deleteRecursively(root);
        }
    }

    /**
     * The operator-facing description states all three positions, and the network one is true.
     *
     * <h4>What this test is, and what it is not</h4>
     *
     * <p><b>It is an output-format test.</b> {@code describeResolution()} is meant to be logged
     * once per JVM so that an empty grid list is never misread as "nothing installed", and the
     * three lines below are the ones an operator looks for. Asserting they are present is worth
     * doing.
     *
     * <p><b>It is not enforcement, and it used to claim to be.</b> Until this was rewritten its
     * javadoc read "Core must not read {@code PROJ_DATA} or any other environment variable" while
     * the body checked only that {@code describeResolution()} <em>contains the strings</em>
     * {@code "environment variables: NONE READ"} and {@code "working directory: NEVER CONSULTED"}
     * — which are literals at {@code ResourceResolvers.java:259-260}. It asserted two string
     * constants against themselves, and adding a {@code System.getenv("PROJ_DATA")} to core would
     * have left it green.
     *
     * <p>The two claims are now enforced where they can actually fail:
     * <ul>
     * <li>the environment-variable claim, and the "no network code" claim with it, by
     *     {@code org.locationtech.proj4j.resource.NoAmbientInputInCoreTest}, which scans
     *     {@code core/src/main} and the compiled classes and proves its own scanners can detect an
     *     injected violation;</li>
     * <li>the working-directory claim by {@link #workingDirectoryIsNeverConsulted()} in this class,
     *     which was always behavioural — it writes a real decoy grid into {@code user.dir} and
     *     asserts it is ignored. That is the model the other two now follow.</li>
     * </ul>
     *
     * <p>{@link ResourceResolvers#isNetworkEnabled()} below is the one assertion here that is not
     * about the text: it interrogates the assembled chain, so a network-backed resolver appearing
     * on the classpath would flip it.
     */
    @Test
    public void describeResolutionStatesTheAmbientInputPosition() {
        String description = ResourceResolvers.describeResolution();
        assertTrue("describeResolution() must state that no environment variables are read: "
                + description, description.contains("environment variables: NONE READ"));
        assertTrue("describeResolution() must state that the CWD is never consulted: " + description,
                description.contains("working directory: NEVER CONSULTED"));
        assertTrue("describeResolution() must state the network position: " + description,
                description.contains("network: ABSENT"));
        assertFalse("core ships no network code, so nothing can be network-enabled",
                ResourceResolvers.isNetworkEnabled());
    }

    // --- ordering -----------------------------------------------------------------------------

    @Test
    public void builtInsAreGridPackFirstThenTheLegacyPrefix() {
        List<ResourceResolver> built = ResourceResolvers.builtInResolvers();
        assertEquals(2, built.size());
        assertTrue(built.get(0).name().contains("proj4j-data/grids"));
        assertTrue(built.get(1).name().contains("proj4/nad"));
        assertTrue("the grid pack resolver must sort before the legacy one",
                built.get(0).priority() < built.get(1).priority());
    }

    @Test
    public void explicitResolversRunBeforeEverythingElseInCallOrder() {
        ResourceResolvers.addResolver(new NamedNullResolver("first", 900));
        ResourceResolvers.addResolver(new NamedNullResolver("second", 1));
        ChainedResourceResolver chain = ResourceResolvers.resolver();
        List<ResourceResolver> delegates = chain.delegates();
        assertTrue("explicit resolvers keep call order regardless of priority",
                delegates.get(0).name().contains("first"));
        assertTrue(delegates.get(1).name().contains("second"));
    }

    /**
     * {@code ServiceLoader} iteration order follows classpath order, which differs between an IDE, a
     * shaded jar and a Spark executor with {@code --jars}. Sorting by {@code (priority, name)} is what
     * makes discovery order irrelevant. The comparator is asserted directly here because there is no
     * portable way to reorder a real classpath inside a unit test.
     */
    @Test
    public void serviceLoaderOrderingIsTotalAndIndependentOfDiscoveryOrder() {
        List<ResourceResolver> a = new ArrayList<ResourceResolver>();
        a.add(new NamedNullResolver("zeta", 10));
        a.add(new NamedNullResolver("alpha", 10));
        a.add(new NamedNullResolver("beta", 5));

        List<ResourceResolver> b = new ArrayList<ResourceResolver>(a);
        Collections.reverse(b);

        assertEquals("two different discovery orders must sort identically",
                names(sortLikeTheChain(a)), names(sortLikeTheChain(b)));
        assertEquals("[beta, alpha, zeta]", names(sortLikeTheChain(a)).toString());
    }

    /** Whatever ServiceLoader providers exist on the test classpath, the discovered list is sorted. */
    @Test
    public void discoveredResolversComeBackSorted() {
        List<ResourceResolver> discovered = ResourceResolvers.serviceLoaderResolvers();
        for (int i = 1; i < discovered.size(); i++) {
            ResourceResolver prev = discovered.get(i - 1);
            ResourceResolver cur = discovered.get(i);
            assertTrue("discovered resolvers must be sorted by (priority, name)",
                    prev.priority() < cur.priority()
                            || (prev.priority() == cur.priority()
                            && prev.name().compareTo(cur.name()) < 0));
        }
    }

    // --- a directory resolver, and its escape check -------------------------------------------

    @Test
    public void aDirectoryResolverResolvesAndRefusesToEscapeItsRoot() throws IOException {
        Path root = Files.createTempDirectory("proj4j-grid-root");
        Path outside = Files.createTempDirectory("proj4j-grid-outside");
        try {
            Files.write(root.resolve("synthetic"), syntheticCtable2());
            Files.write(outside.resolve("secret"), syntheticCtable2());

            DirectoryResourceResolver r = new DirectoryResourceResolver(root);
            assertNotNull("a file in the root resolves", r.resolve("synthetic"));
            assertNull("a traversal out of the root does not", r.resolve("../"
                    + outside.getFileName() + "/secret"));
            assertTrue("a directory resolver is enumerable", r.isEnumerable());
            assertTrue(r.listAvailable().contains("synthetic"));
        } finally {
            deleteRecursively(root);
            deleteRecursively(outside);
        }
    }

    @Test
    public void anAddedDirectoryResolverOutranksTheBuiltInClasspathPack() throws IOException {
        Path root = Files.createTempDirectory("proj4j-grid-override");
        try {
            Files.write(root.resolve("conus"), Files.readAllBytes(conusOnClasspath()));
            ResourceResolvers.addResolver(new DirectoryResourceResolver(root));
            GridCache.instance().clear();

            List<Grid> list = new ArrayList<Grid>();
            Grid.mergeGridFile("conus", list);
            assertTrue("the explicitly added directory must win, got " + list.get(0).getOrigin(),
                    list.get(0).getOrigin().startsWith("file:"));
        } finally {
            deleteRecursively(root);
        }
    }

    // --- the classpath pack is enumerable via its generated INDEX -----------------------------

    @Test
    public void theGridPackIsEnumerableThroughItsIndex() {
        ClasspathResourceResolver r = new ClasspathResourceResolver(
                GridResolutionTest.class.getClassLoader(),
                ResourceResolvers.GRID_PREFIX, ResourceResolvers.GRID_INDEX);
        assertTrue("an indexed classpath resolver reports itself enumerable", r.isEnumerable());
        assertTrue("the test grid pack index lists conus: " + r.listAvailable(),
                r.listAvailable().contains("conus"));
    }

    @Test
    public void aClasspathResolverWithoutAnIndexSaysItCannotEnumerate() {
        ClasspathResourceResolver r = new ClasspathResourceResolver(
                GridResolutionTest.class.getClassLoader(), ResourceResolvers.LEGACY_GRID_PREFIX);
        assertFalse("no index means not enumerable", r.isEnumerable());
        assertTrue("and an empty list, which must not be read as 'nothing installed'",
                r.listAvailable().isEmpty());
        // ... yet the grid is still resolvable and still reported by name.
        assertNotNull(safeResolve(r, "ntv1_can.dat"));
    }

    // --- PROJ's @-optional wart, made visible -------------------------------------------------

    /**
     * PROJ's {@code @} prefix means <em>optional</em>, and silently skipping a missing optional grid is
     * correct PROJ behaviour ({@code grids.cpp}, {@code getListOfGridSets}: any failure on an
     * {@code @}-prefixed grid clears the errno and continues). That is reproduced, not "fixed" — but it
     * is now <em>visible</em>: {@link Grid#describeNadGrids} reports every token, whether it resolved,
     * from where, and if not, why not.
     */
    @Test
    public void anOptionalMissingGridIsSkippedSilentlyButReportedByIntrospection() throws IOException {
        List<Grid> grids = Grid.fromNadGrids("@definitely_not_a_grid,conus");
        assertEquals("the missing optional grid is skipped, exactly as PROJ does", 1, grids.size());
        assertEquals("conus", grids.get(0).getGridName());

        List<Grid.GridRef> refs = Grid.describeNadGrids("@definitely_not_a_grid,conus");
        assertEquals("both tokens are reported", 2, refs.size());

        Grid.GridRef missing = refs.get(0);
        assertEquals("definitely_not_a_grid", missing.name());
        assertTrue(missing.isOptional());
        assertFalse(missing.isAvailable());
        assertNotNull("a skipped grid must carry a reason", missing.skipReason());
        assertTrue(missing.skipReason().contains("Unknown grid"));

        Grid.GridRef present = refs.get(1);
        assertEquals("conus", present.name());
        assertFalse(present.isOptional());
        assertTrue(present.isAvailable());
        assertEquals("ctable2", present.grid().getFormat());
        assertTrue("provenance names the resolver that answered: " + present.grid().getResolverName(),
                present.grid().getResolverName().contains("proj4j-data/grids"));
    }

    /** A required (unprefixed) missing grid must throw, not be skipped. */
    @Test
    public void aRequiredMissingGridThrows() {
        try {
            Grid.fromNadGrids("definitely_not_a_grid,conus");
            fail("a required missing grid must not be skipped");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("Unknown grid"));
        }
    }

    /** All four of NAD27's declared grids, as {@code Datum} asks for them. */
    @Test
    public void nad27sFourDeclaredGridsAreReportedIndividually() {
        List<Grid.GridRef> refs = Grid.describeNadGrids("@conus,@alaska,@ntv2_0.gsb,@ntv1_can.dat");
        assertEquals(4, refs.size());
        for (Grid.GridRef ref : refs) {
            assertTrue("every token is @-optional, so every failure is silent", ref.isOptional());
        }
        assertEquals("conus", refs.get(0).name());
        assertTrue("conus is now reachable", refs.get(0).isAvailable());
        assertTrue("ntv1_can.dat ships in proj4j-epsg", refs.get(3).isAvailable());
        assertEquals("ntv1", refs.get(3).grid().getFormat());
    }

    /** An unrecognised file must be an error, not a silently no-op grid. */
    @Test
    public void anUnrecognisedFileIsAnErrorRatherThanANoOpGrid() throws IOException {
        Path root = Files.createTempDirectory("proj4j-bad-grid");
        try {
            Files.write(root.resolve("notagrid"), "this is not a grid file at all".getBytes("US-ASCII"));
            ResourceResolvers.addResolver(new DirectoryResourceResolver(root));
            GridCache.instance().clear();
            try {
                Grid.fromNadGrids("notagrid");
                fail("an unrecognised file must throw rather than yield a format=\"missing\" grid");
            } catch (IOException expected) {
                assertTrue(expected.getMessage(),
                        expected.getMessage().contains("Unrecognised horizontal grid format"));
            }
        } finally {
            deleteRecursively(root);
        }
    }

    /** {@code null} stays a legal grid that shifts nothing, as in PROJ. */
    @Test
    public void theNullGridStillWorks() throws IOException {
        List<Grid> grids = Grid.fromNadGrids("null");
        assertEquals(1, grids.size());
        double[] got = GridReferenceValues.shiftDegrees(grids, false, -122.4, 37.8);
        assertEquals(-122.4, got[0], 1e-12);
        assertEquals(37.8, got[1], 1e-12);
    }

    // --- helpers ------------------------------------------------------------------------------

    private static ResourceHandle safeResolve(ResourceResolver r, String name) {
        try {
            return r.resolve(name);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static Path conusOnClasspath() {
        try {
            return Paths.get(GridResolutionTest.class.getResource(
                    "/" + ResourceResolvers.GRID_PREFIX + "/conus").toURI());
        } catch (Exception e) {
            throw new AssertionError("the test grid pack must be on the test classpath", e);
        }
    }

    private static List<ResourceResolver> sortLikeTheChain(List<ResourceResolver> in) {
        List<ResourceResolver> copy = new ArrayList<ResourceResolver>(in);
        Collections.sort(copy, new java.util.Comparator<ResourceResolver>() {
            @Override
            public int compare(ResourceResolver a, ResourceResolver b) {
                int p = Integer.compare(a.priority(), b.priority());
                return p != 0 ? p : a.name().compareTo(b.name());
            }
        });
        return copy;
    }

    private static List<String> names(List<ResourceResolver> rs) {
        List<String> out = new ArrayList<String>();
        for (ResourceResolver r : rs) {
            out.add(r.name());
        }
        return out;
    }

    /** A minimal but structurally valid CTABLE V2 grid: 3x3 nodes, all shifts zero. */
    static byte[] syntheticCtable2() {
        int cols = 3;
        int rows = 3;
        byte[] b = new byte[160 + cols * rows * 8];
        byte[] magic = "CTABLE V2.0     ".getBytes(java.nio.charset.Charset.forName("US-ASCII"));
        System.arraycopy(magic, 0, b, 0, magic.length);
        byte[] id = "synthetic\n".getBytes(java.nio.charset.Charset.forName("US-ASCII"));
        System.arraycopy(id, 0, b, 16, id.length);
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        buf.putDouble(96, Math.toRadians(-10.0));  // ll.lam
        buf.putDouble(104, Math.toRadians(-10.0)); // ll.phi
        buf.putDouble(112, Math.toRadians(1.0));   // del.lam
        buf.putDouble(120, Math.toRadians(1.0));   // del.phi
        buf.putInt(128, cols);
        buf.putInt(132, rows);
        return b;
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(root);
        try {
            for (Path p : stream) {
                if (Files.isDirectory(p)) {
                    deleteRecursively(p);
                } else {
                    Files.deleteIfExists(p);
                }
            }
        } finally {
            stream.close();
        }
        Files.deleteIfExists(root);
    }

    /** A resolver that never resolves anything; used only to observe chain ordering. */
    private static final class NamedNullResolver implements ResourceResolver {
        private final String name;
        private final int priority;

        NamedNullResolver(String name, int priority) {
            this.name = name;
            this.priority = priority;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public int priority() {
            return priority;
        }

        @Override
        public ResourceHandle resolve(String resourceName) {
            return null;
        }
    }
}
