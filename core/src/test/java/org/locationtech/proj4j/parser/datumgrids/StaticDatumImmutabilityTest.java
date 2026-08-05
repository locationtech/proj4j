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
 */
package org.locationtech.proj4j.parser.datumgrids;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.datum.Datum;

/**
 * A regression net for the whole class of defect in which <b>parsing a definition
 * mutates a shared static {@link Datum} singleton, process-wide</b>.
 *
 * <h2>What this is guarding</h2>
 *
 * <p>{@code Proj4Parser.parse} used to do this:
 *
 * <pre>
 * Datum datum = datumParam.getDatum();      // may BE Datum.NAD27, the singleton
 * datum.setGrids(datumParam.getGrids());    // mutates it, for the whole JVM
 * </pre>
 *
 * <p>{@code EPSG:4267} is {@code +proj=longlat +datum=NAD27 +no_defs} — no
 * {@code +nadgrids} token — so {@code getGrids()} was {@code null} and the first parse
 * of that one code executed {@code Datum.NAD27.setGrids(null)} permanently. Its
 * transform type flipped {@code TYPE_GRIDSHIFT -> TYPE_UNKNOWN}, and thereafter
 * {@code EPSG:4267 -> EPSG:4269} returned <i>the input unchanged</i>: a finite,
 * plausible, unflagged answer roughly 95 m wrong at San Francisco. The mirror image was
 * reachable from untrusted input too — {@code +datum=WGS84 +nadgrids=@null} gave
 * {@code Datum.WGS84} a non-empty grid list JVM-wide, flipping every WGS84 code to
 * {@code TYPE_GRIDSHIFT} including transforms already cached.
 *
 * <h2>Why it is written reflectively</h2>
 *
 * <p>The point is not that {@code NAD27.grids} specifically survives; it is that
 * <i>nothing about any singleton</i> changes. So rather than assert on one field
 * through one getter — {@link Datum} exposes no {@code getGrids()} at all — this
 * enumerates every {@code static Datum} constant {@link Datum} declares, snapshots
 * every instance field of each, runs a battery of parses, and diffs. A newly added
 * singleton, or a newly added mutable field, is covered the day it appears rather than
 * the day someone remembers to extend a test.
 *
 * <p>Aliasing counts as mutation here: for a {@link List} or an array the snapshot
 * records the container's <i>identity</i> and its elements' identities, so handing a
 * singleton's own grid list or {@code towgs84} array out to something that rescales it
 * in place is caught as well.
 */
public class StaticDatumImmutabilityTest {

    private final CRSFactory crsFactory = new CRSFactory();

    /**
     * Definitions that between them reach every path in {@code Proj4Parser.parseDatum}
     * and {@code DatumParameters.getDatum} that has a named datum, a grid list, or
     * both.
     */
    private static final String[] DEFINITIONS = {
            // The original defect: a named grid-shift datum with no +nadgrids token.
            "+proj=longlat +datum=NAD27 +no_defs",
            // +nadgrids alongside a named datum: must derive, not overwrite.
            "+proj=longlat +datum=NAD27 +nadgrids=@conus +no_defs",
            "+proj=longlat +datum=NAD27 +nadgrids=@ntv1_can.dat +no_defs",
            // The untrusted-input mirror image: @null onto a Helmert datum.
            "+proj=longlat +datum=WGS84 +nadgrids=@null +no_defs",
            "+proj=longlat +datum=NAD83 +nadgrids=@null +no_defs",
            // A grid list with no datum at all: must not land on Datum.WGS84.
            "+proj=longlat +nadgrids=@conus +no_defs",
            // Named Helmert datums, with and without a competing +towgs84.
            "+proj=longlat +datum=potsdam +no_defs",
            "+proj=tmerc +lat_0=0 +lon_0=9 +k=1 +x_0=500000 +y_0=0 +datum=potsdam"
                    + " +towgs84=598.1,73.7,418.2,0.202,0.045,-2.455,6.7 +units=m +no_defs",
            "+proj=longlat +datum=OSGB36 +no_defs",
            "+proj=longlat +datum=GGRS87 +no_defs",
            // An explicit ellipsoid alongside a named datum.
            "+proj=merc +datum=WGS84 +R=6400000",
            "+proj=merc +datum=NAD27 +a=6400000 +rf=297",
    };

    @Test
    public void noParseMutatesAnyStaticDatumSingleton() {
        Map<String, Object> before = snapshotAllSingletons();
        assertFalse("fixture: Datum declares no static Datum constants?", before.isEmpty());

        for (int pass = 0; pass < 2; pass++) {
            for (String def : DEFINITIONS) {
                crsFactory.createFromParameters("test", def);
            }
        }
        crsFactory.createFromName("EPSG:4267");
        crsFactory.createFromName("EPSG:4269");
        crsFactory.createFromName("EPSG:4267");

        Map<String, Object> after = snapshotAllSingletons();
        List<String> drifted = new ArrayList<String>();
        for (Map.Entry<String, Object> entry : before.entrySet()) {
            Object now = after.get(entry.getKey());
            if (!equal(entry.getValue(), now)) {
                drifted.add(entry.getKey() + ": was " + entry.getValue() + ", now " + now);
            }
        }
        assertTrue("parsing mutated shared Datum state: " + drifted, drifted.isEmpty());
    }

    /**
     * The specific consequence the defect had, stated in the terms a caller sees.
     * {@code Datum.NAD27} must still claim a grid shift after EPSG:4267 has been
     * parsed, because that claim is what makes {@code BasicCoordinateTransform} apply
     * the grid at all.
     */
    @Test
    public void nad27KeepsClaimingAGridShiftAfterParsingEpsg4267() {
        assertEquals("fixture: Datum.NAD27 must ship with grids; if this fails the grid"
                        + " data is missing from the classpath, not the parser",
                Datum.TYPE_GRIDSHIFT, Datum.NAD27.getTransformType());

        for (int i = 0; i < 3; i++) {
            crsFactory.createFromName("EPSG:4267");
            assertEquals("Datum.NAD27 lost its grids on parse " + i,
                    Datum.TYPE_GRIDSHIFT, Datum.NAD27.getTransformType());
            crsFactory.createFromParameters("t", "+proj=longlat +datum=NAD27 +no_defs");
            assertEquals("Datum.NAD27 lost its grids on +datum=NAD27 parse " + i,
                    Datum.TYPE_GRIDSHIFT, Datum.NAD27.getTransformType());
        }
    }

    /** {@code +nadgrids=@null} from untrusted input must not reach the WGS84 singleton. */
    @Test
    public void nadgridsNullDoesNotTurnWgs84IntoAGridShiftDatum() {
        assertEquals(Datum.TYPE_WGS84, Datum.WGS84.getTransformType());
        assertEquals(Datum.TYPE_WGS84, Datum.NAD83.getTransformType());

        crsFactory.createFromParameters("t", "+proj=longlat +datum=WGS84 +nadgrids=@null +no_defs");
        crsFactory.createFromParameters("t", "+proj=longlat +datum=NAD83 +nadgrids=@null +no_defs");

        assertEquals("Datum.WGS84 was flipped to TYPE_GRIDSHIFT process-wide",
                Datum.TYPE_WGS84, Datum.WGS84.getTransformType());
        assertEquals("Datum.NAD83 was flipped to TYPE_GRIDSHIFT process-wide",
                Datum.TYPE_WGS84, Datum.NAD83.getTransformType());
    }

    /**
     * {@code Datum.setGrids} is the only way to mutate a {@link Datum} after
     * construction, and after this change nothing in main code calls it. This test
     * states that as an expectation so that the day it is deprecated and removed, the
     * reason is recorded here rather than rediscovered.
     */
    @Test
    public void aDerivedDatumIsUsedRatherThanMutatingTheNamedOne() {
        Datum shared = crsFactory
                .createFromParameters("t", "+proj=longlat +datum=NAD27 +no_defs").getDatum();
        assertTrue("with no +nadgrids the singleton itself is safe to reuse",
                shared == Datum.NAD27);

        Datum derived = crsFactory
                .createFromParameters("t", "+proj=longlat +datum=NAD27 +nadgrids=@conus +no_defs")
                .getDatum();
        assertFalse("+nadgrids alongside +datum must derive a new Datum, not mutate NAD27",
                derived == Datum.NAD27);
        assertEquals("the derived datum must keep the named datum's ellipsoid",
                Datum.NAD27.getEllipsoid(), derived.getEllipsoid());
        assertEquals(Datum.TYPE_GRIDSHIFT, derived.getTransformType());
    }

    // ------------------------------------------------------------------
    // Reflective snapshot
    // ------------------------------------------------------------------

    /**
     * @return one entry per (singleton, instance field) pair, keyed
     *         {@code "NAD27.grids"}, valued by a comparable description of the field's
     *         contents <i>and</i> the identity of any container it holds
     */
    private static Map<String, Object> snapshotAllSingletons() {
        Map<String, Object> snapshot = new LinkedHashMap<String, Object>();
        for (Field constant : Datum.class.getDeclaredFields()) {
            if (!Modifier.isStatic(constant.getModifiers()) || constant.getType() != Datum.class) {
                continue;
            }
            Datum singleton;
            try {
                constant.setAccessible(true);
                singleton = (Datum) constant.get(null);
            } catch (Exception e) {
                throw new AssertionError("cannot read Datum." + constant.getName() + ": " + e);
            }
            if (singleton == null) {
                continue;
            }
            snapshot.put(constant.getName() + ".<identity>", identity(singleton));
            snapshot.put(constant.getName() + ".<transformType>",
                    Integer.valueOf(singleton.getTransformType()));
            for (Field field : Datum.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    snapshot.put(constant.getName() + "." + field.getName(),
                            describe(field.get(singleton)));
                } catch (Exception e) {
                    throw new AssertionError("cannot read Datum." + constant.getName()
                            + "." + field.getName() + ": " + e);
                }
            }
        }
        return snapshot;
    }

    /**
     * Renders a field value so that a change of contents <i>or</i> a change of
     * container identity both show up as inequality. Element identities are recorded
     * rather than element values because a {@code Grid} handed out and mutated in place
     * would otherwise be invisible.
     */
    private static Object describe(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof double[]) {
            double[] array = (double[]) value;
            StringBuilder sb = new StringBuilder("double[]@").append(identity(array)).append('[');
            for (int i = 0; i < array.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                // Double.toString, not the raw double, so that -0.0 and NaN compare
                // by their textual form rather than by ==.
                sb.append(Double.toString(array[i]));
            }
            return sb.append(']').toString();
        }
        if (value instanceof Collection<?>) {
            Collection<?> collection = (Collection<?>) value;
            StringBuilder sb = new StringBuilder("collection@").append(identity(value))
                    .append("size=").append(collection.size()).append('[');
            for (Object element : collection) {
                sb.append(identity(element)).append(',');
            }
            return sb.append(']').toString();
        }
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        // Ellipsoid and friends: compare by identity. Nothing in the parser is
        // expected to swap one out, and their own immutability is tested elsewhere.
        return value.getClass().getName() + "@" + identity(value);
    }

    private static String identity(Object o) {
        return o == null ? "null" : Integer.toHexString(System.identityHashCode(o));
    }

    private static boolean equal(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }
}
