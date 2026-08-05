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
package org.locationtech.proj4j.failopen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.locationtech.proj4j.datum.Datum;
import org.locationtech.proj4j.datum.GridCache;
import org.locationtech.proj4j.resource.DirectoryResourceResolver;
import org.locationtech.proj4j.resource.ResourceResolver;
import org.locationtech.proj4j.resource.ResourceResolvers;

/**
 * {@code Datum.POTSDAM.getTransformType()} must not depend on when {@code Datum} was loaded.
 *
 * <h2>The defect</h2>
 *
 * <p>{@code Datum.POTSDAM} and {@code Datum.NAD27} resolved their {@code +nadgrids=} lists in
 * {@code Datum}'s <em>static initialiser</em>, which snapshots whatever
 * {@link ResourceResolvers#resolver()} returned at that instant. That chain is mutable —
 * {@link ResourceResolvers#addResolver} discards the memoised chain — so the two constants reported
 * {@code TYPE_GRIDSHIFT} or {@code TYPE_7PARAM} according to whether an application had registered
 * its grid directory before or after something first touched the class.
 *
 * <p>Measured: {@code DHDN_ETRS89.gie} scored <b>64/64</b> from a driver that built the grid bridge
 * first and <b>32/32</b> under surefire, where an earlier unrelated test had already loaded
 * {@code Datum} — the same code, the same grid file, the same classpath, two different answers. The
 * consumer runs per-vertex in Spark executors and requires bit-reproducible output; a transform type
 * that varies with class-loading is worse than one that is simply wrong, because it is untestable.
 *
 * <h2>What is asserted, and why one load order proves nothing</h2>
 *
 * <p>The whole defect is a difference <em>between</em> load orders, so each case here is run twice:
 * once against the {@link Datum} this JVM already loaded (grid registered <b>after</b> load) and
 * once against a {@link Datum} defined freshly by {@link DatumReloader} while the grid is already
 * registered (grid registered <b>before</b> load). A test that checked only one would have passed
 * against the defect.
 *
 * <p>{@link DatumReloader} redefines <em>only</em> {@code Datum} and its nested classes, delegating
 * everything else — {@code Grid}, {@code GridCache}, {@code Ellipsoid},
 * {@code ResourceResolvers} — to the parent loader. So the fresh class runs its static initialiser
 * again against the <em>same</em> resolver registry, which is exactly the variable under test and
 * nothing else.
 *
 * <h2>The choice this pins</h2>
 *
 * <p><b>{@code getTransformType()} is a pure function of the resolver chain in force when it is
 * asked.</b> Grid reachable &rarr; {@code TYPE_GRIDSHIFT}; grid not reachable &rarr; the declared
 * fallback, which for {@code potsdam} is {@code TYPE_7PARAM}. That fallback is not a defect: it is
 * what {@code cs2cs +datum=potsdam} does, measured by hiding {@code de_adv_BETA2007.tif} from
 * {@code PROJ_DATA}, and following {@code datums.cpp}'s uncommented string alone — grid or nothing —
 * would cost <b>74.921 m easting and 127.698 m northing</b> at (9, 50). See {@link Datum#POTSDAM}.
 * Class-load time does not appear in that function, and this class is what says so.
 */
public class DatumGridLoadOrderTest {

    /** Any parseable horizontal grid will do; only <em>reachability</em> is under test here. */
    private static final String DONOR_GRID = "/proj4j-data/grids/conus";

    /** The file name {@code Datum.POTSDAM} declares, {@code 9.8.1:src/datums.cpp:49-50}. */
    private static final String POTSDAM_GRID = "BETA2007.gsb";

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    /**
     * The explicit resolvers in force when this class started. Restored exactly in
     * {@link #restoreChain()}, because {@link ResourceResolvers} is process-wide and surefire may
     * be sharing this JVM with tests that registered their own.
     */
    private List<ResourceResolver> preexisting;

    @Before
    public void rememberChain() {
        preexisting = new ArrayList<ResourceResolver>(ResourceResolvers.explicitResolvers());
    }

    @After
    public void restoreChain() {
        ResourceResolvers.clearResolvers();
        for (ResourceResolver r : preexisting) {
            ResourceResolvers.addResolver(r);
        }
        GridCache.instance().clear();
    }

    // ------------------------------------------------------------------
    // The defect itself
    // ------------------------------------------------------------------

    /**
     * The measured defect, in both directions, on the datum it was reported against.
     * <p>
     * Four observations, and the pairs are what matter: {@code (1, 2)} shows that registering the
     * grid <em>after</em> the class loaded is honoured, which is the half the static initialiser got
     * wrong; {@code (2, 3)} shows the two load orders agree; {@code (3, 4)} shows the answer goes
     * back when the grid goes away, i.e. that it is genuinely being re-derived and not merely
     * latched the other way.
     */
    @Test
    public void potsdamTransformTypeIsTheSameUnderBothLoadOrders() throws Exception {
        // (1) grid absent, class already loaded long ago.
        assertEquals("potsdam with no reachable BETA2007.gsb must report the declared Helmert"
                        + " fallback, which is what cs2cs does",
                Datum.TYPE_7PARAM, transformType(Datum.POTSDAM));
        assertEquals("... and identically for a Datum class loaded fresh with the grid absent",
                Datum.TYPE_7PARAM, freshPotsdamTransformType());

        registerGridDirectoryContaining(POTSDAM_GRID);

        // (2) grid registered AFTER Datum was loaded. This is the case the static initialiser
        //     could not see, and the one that scored 32/32 instead of 64/64.
        assertEquals("potsdam must pick up BETA2007.gsb registered after Datum loaded",
                Datum.TYPE_GRIDSHIFT, transformType(Datum.POTSDAM));

        // (3) grid registered BEFORE Datum was loaded. Same configuration, other load order.
        assertEquals("potsdam must report the same type when Datum loads with the grid already"
                        + " registered -- this is the assertion the defect failed",
                Datum.TYPE_GRIDSHIFT, freshPotsdamTransformType());

        // (4) and it must go back, so that (2) and (3) are re-derivations and not a one-way latch.
        ResourceResolvers.clearResolvers();
        assertEquals("removing the resolver must return potsdam to the Helmert fallback",
                Datum.TYPE_7PARAM, transformType(Datum.POTSDAM));
        assertEquals(Datum.TYPE_7PARAM, freshPotsdamTransformType());
    }

    /**
     * The same shape on {@code Datum.NAD27}, whose fallback is different: it declares no
     * {@code towgs84}, so with none of {@code @conus,@alaska,@ntv2_0.gsb,@ntv1_can.dat} reachable
     * it is {@code TYPE_UNKNOWN} rather than {@code TYPE_7PARAM}.
     * <p>
     * Written against a <em>fresh</em> loader on both sides rather than against the already-loaded
     * singleton, because {@code conus} ships in this module's own test resources: the classpath
     * resolver finds it whatever this test does, so the already-loaded constant is
     * {@code TYPE_GRIDSHIFT} throughout and cannot distinguish the two orders. What is asserted is
     * therefore the property that does distinguish them — <b>the two loaders agree</b> — plus that
     * both agree with the resolver chain.
     */
    @Test
    public void nad27TransformTypeIsTheSameUnderBothLoadOrders() throws Exception {
        int already = transformType(Datum.NAD27);
        assertEquals("fixture: conus ships in core's test resources, so NAD27 must be a grid shift"
                        + " here; if this fails the grid data is missing, not the datum code",
                Datum.TYPE_GRIDSHIFT, already);
        assertEquals("a freshly loaded Datum must agree with the one already loaded",
                already, freshTransformType("NAD27"));

        registerGridDirectoryContaining("alaska");
        assertEquals("adding a second reachable grid must not change the classification",
                Datum.TYPE_GRIDSHIFT, transformType(Datum.NAD27));
        assertEquals(Datum.TYPE_GRIDSHIFT, freshTransformType("NAD27"));
    }

    /**
     * The two orders must agree on <em>every</em> {@code static Datum} constant, not only the two
     * known to be spec-backed, so that a constant added later is covered the day it appears rather
     * than the day someone remembers this test.
     */
    @Test
    public void everyStaticDatumAgreesAcrossLoadOrders() throws Exception {
        registerGridDirectoryContaining(POTSDAM_GRID);
        Class<?> fresh = new DatumReloader().reload();
        List<String> disagreed = new ArrayList<String>();
        for (Field constant : Datum.class.getDeclaredFields()) {
            if (constant.getType() != Datum.class) {
                continue;
            }
            constant.setAccessible(true);
            int mine = transformType((Datum) constant.get(null));
            int theirs = transformType(fresh, constant.getName());
            if (mine != theirs) {
                disagreed.add(constant.getName() + ": already-loaded " + mine + ", freshly loaded "
                        + theirs);
            }
        }
        assertTrue("transform type depends on class-load order for: " + disagreed,
                disagreed.isEmpty());
    }

    // ------------------------------------------------------------------
    // ... without making the singletons mutable
    // ------------------------------------------------------------------

    /**
     * The constraint the fix had to respect. A parser bug once called
     * {@code Datum.NAD27.setGrids(null)} while parsing {@code EPSG:4267} — a definition with no
     * {@code +nadgrids} token — which destroyed the shared grid list for the life of the JVM and
     * flipped every NAD27 transform in the process, cached ones included. Determinism cannot be
     * bought by making these writable, so a spec-backed datum refuses the write outright.
     */
    @Test
    public void theSharedSingletonsRefuseToBeMutated() {
        for (Datum shared : new Datum[]{Datum.NAD27, Datum.POTSDAM}) {
            int before = transformType(shared);
            try {
                shared.setGrids(null);
                fail("Datum." + shared.getCode() + ".setGrids(null) must be refused, not silently"
                        + " accepted: it used to destroy the shared grid list process-wide");
            } catch (UnsupportedOperationException expected) {
                assertTrue("the refusal must name the datum, got: " + expected.getMessage(),
                        expected.getMessage().contains(shared.getCode()));
            }
            assertEquals("Datum." + shared.getCode() + " changed type after a refused mutation",
                        before, transformType(shared));
        }
    }

    /** A datum built with an explicit grid list is unaffected: only the two singletons refuse. */
    @Test
    public void anOrdinaryDatumStillAcceptsSetGrids() {
        Datum ordinary = new Datum("mine", new double[]{1, 2, 3}, null,
                Datum.NAD27.getEllipsoid(), "mine");
        assertEquals(Datum.TYPE_3PARAM, transformType(ordinary));
        ordinary.setGrids(null);
        assertEquals(Datum.TYPE_3PARAM, transformType(ordinary));
    }

    /**
     * Repeated queries against an unchanged chain must be free of surprises: the same value, and
     * the memo derived once. Asserted through {@code isEqual}, which is the hot-path consumer of
     * the grid list and whose {@code TYPE_GRIDSHIFT} branch descends into {@code Arrays.equals}
     * over every node of a loaded table — so it had better be short-circuiting on identity.
     */
    @Test
    public void repeatedQueriesAgreeAndTheIdentityShortCircuitSurvives() throws Exception {
        registerGridDirectoryContaining(POTSDAM_GRID);
        int first = transformType(Datum.POTSDAM);
        for (int i = 0; i < 50; i++) {
            assertEquals("query " + i, first, transformType(Datum.POTSDAM));
        }
        assertTrue("a datum must equal itself without a deep grid compare",
                Datum.POTSDAM.isEqual(Datum.POTSDAM));
        assertTrue("NAD27 must still equal itself", Datum.NAD27.isEqual(Datum.NAD27));
    }

    /**
     * The requirement behind the defect, stated as a test: the consumer runs per-vertex in Spark
     * executors and needs bit-reproducible output, so concurrent readers must not be able to
     * observe two different transform types.
     * <p>
     * The memo is filled without a lock, deliberately — no monitor is held across grid I/O — so
     * several threads may resolve at once. That is safe here for a reason worth naming: the memo
     * can only ever hold the one answer {@code Grid.fromNadGrids} gives for the declared spec
     * against that chain, so a lost update costs a repeated lookup and never a different value.
     * This asserts that rather than assuming it, in both configurations.
     */
    @Test
    public void concurrentReadersNeverSeeTwoDifferentTypes() throws Exception {
        assertSingleValuedUnderLoad("grid absent");
        registerGridDirectoryContaining(POTSDAM_GRID);
        assertSingleValuedUnderLoad("grid present");
    }

    private static void assertSingleValuedUnderLoad(String configuration) throws Exception {
        final int threads = 8;
        final int iterations = 500;
        final java.util.Set<String> observed =
                java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<String>());
        final java.util.concurrent.CountDownLatch start =
                new java.util.concurrent.CountDownLatch(1);
        Thread[] workers = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            workers[t] = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    for (int i = 0; i < iterations; i++) {
                        observed.add(transformType(Datum.POTSDAM) + "/" + transformType(Datum.NAD27));
                    }
                }
            });
            workers[t].start();
        }
        start.countDown();
        for (Thread w : workers) {
            w.join();
        }
        assertEquals(configuration + ": " + threads * iterations + " concurrent reads must agree,"
                + " observed " + observed, 1, observed.size());
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static int transformType(Datum d) {
        return d.getTransformType();
    }

    /**
     * Copies a known-parseable grid into a fresh directory under the name {@code fileName} and
     * registers that directory. Only reachability is under test, so the donor bytes need only be a
     * grid {@code Grid.parse} accepts; {@code Grid} sniffs the format from the bytes, not the name.
     *
     * @param fileName the name the resolver should answer to
     */
    private void registerGridDirectoryContaining(String fileName) throws IOException {
        Path root = folder.newFolder(fileName.replace('.', '_')).toPath();
        Files.write(root.resolve(fileName), donorGridBytes());
        ResourceResolvers.addResolver(new DirectoryResourceResolver(root));
        GridCache.instance().clear();
    }

    private static byte[] donorGridBytes() throws IOException {
        InputStream in = DatumGridLoadOrderTest.class.getResourceAsStream(DONOR_GRID);
        assertNotNull("fixture: " + DONOR_GRID + " must be on the test classpath", in);
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

    private static int freshPotsdamTransformType() throws Exception {
        return freshTransformType("POTSDAM");
    }

    private static int freshTransformType(String constantName) throws Exception {
        return transformType(new DatumReloader().reload(), constantName);
    }

    private static int transformType(Class<?> datumClass, String constantName) throws Exception {
        Object datum = datumClass.getField(constantName).get(null);
        Object type = datumClass.getMethod("getTransformType").invoke(datum);
        return ((Integer) type).intValue();
    }

    /**
     * A loader that defines a brand-new {@code Datum} class — and only that class — so its static
     * initialiser runs again against the resolver registry as it stands now.
     * <p>
     * Everything else delegates to the parent, which is the point: the fresh {@code Datum} calls
     * the <em>same</em> {@code Grid.fromNadGrids}, consults the <em>same</em>
     * {@link ResourceResolvers} statics and shares the <em>same</em> {@link GridCache}. The only
     * variable between the two copies is the moment their static initialisers ran, which is the
     * variable the defect was sensitive to.
     */
    private static final class DatumReloader extends ClassLoader {

        private static final String TARGET = "org.locationtech.proj4j.datum.Datum";

        DatumReloader() {
            super(Datum.class.getClassLoader());
        }

        Class<?> reload() throws ClassNotFoundException {
            Class<?> fresh = loadClass(TARGET);
            assertNotSame("fixture: the reloader must define a genuinely new class, otherwise this"
                    + " test degenerates into checking one load order twice", Datum.class, fresh);
            return fresh;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.equals(TARGET) && !name.startsWith(TARGET + "$")) {
                return super.loadClass(name, resolve);
            }
            Class<?> already = findLoadedClass(name);
            if (already == null) {
                byte[] bytes = classBytes(name);
                already = defineClass(name, bytes, 0, bytes.length);
            }
            if (resolve) {
                resolveClass(already);
            }
            return already;
        }

        private byte[] classBytes(String name) throws ClassNotFoundException {
            String path = name.replace('.', '/') + ".class";
            InputStream in = getParent().getResourceAsStream(path);
            if (in == null) {
                throw new ClassNotFoundException(path + " is not readable from the parent loader");
            }
            try {
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
            } catch (IOException e) {
                throw new ClassNotFoundException(path, e);
            }
        }
    }
}
