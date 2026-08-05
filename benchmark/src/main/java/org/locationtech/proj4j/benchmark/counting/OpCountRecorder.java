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
package org.locationtech.proj4j.benchmark.counting;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import org.locationtech.proj4j.benchmark.CrsPair;

/**
 * Runs one transform per {@link CrsPair} under {@link CountingClassLoader} and returns the
 * transcendental tallies. This is the measurement half of Tier 2; the comparison half is
 * {@code GateChecker}.
 *
 * <p><b>Determinism, which is the entire value proposition.</b> One transform, one pinned input point,
 * counters zeroed immediately before. Nothing here samples, times, or averages, so two runs on two
 * machines produce byte-identical output or one of them has a bug. That is what makes Tier 2 safe to
 * block a PR on when Tier 3 is not.
 *
 * <p>Three specific things are done to keep it that way:
 * <ol>
 *   <li><b>One loader for the whole run.</b> A fresh loader per pair would re-run {@code core}'s static
 *       initialisers each time and charge them to whichever pair went first.</li>
 *   <li><b>A throwaway transform before every measurement.</b> Absorbs class initialisation, the
 *       init-file scan, grid loading and any first-call lazy work. Without it the counts would depend
 *       on enum declaration order, which is an absurd thing for a gate to depend on.</li>
 *   <li><b>The transform is built before the counters are reset.</b> CRS construction calls
 *       transcendentals (ellipsoid derivation, {@code initialize()}); those are per-transform, not
 *       per-point, and folding them in would mask a per-point regression behind a large constant.</li>
 * </ol>
 */
public final class OpCountRecorder {

    private OpCountRecorder() {
    }

    /** Per-pair tallies, insertion-ordered by {@link CrsPair} declaration order. */
    public static final class Result {
        private final Map<String, long[]> countsByPair;
        private final int rewrittenClasses;
        private final int rewrittenReferences;

        Result(Map<String, long[]> countsByPair, int rewrittenClasses, int rewrittenReferences) {
            this.countsByPair = countsByPair;
            this.rewrittenClasses = rewrittenClasses;
            this.rewrittenReferences = rewrittenReferences;
        }

        /** Keyed by {@link CrsPair#name()}; values indexed by {@link OpCounters.Op#ordinal()}. */
        public Map<String, long[]> countsByPair() {
            return countsByPair;
        }

        public int rewrittenClasses() {
            return rewrittenClasses;
        }

        public int rewrittenReferences() {
            return rewrittenReferences;
        }

        public long total() {
            long sum = 0;
            for (long[] counts : countsByPair.values()) {
                for (long c : counts) {
                    sum += c;
                }
            }
            return sum;
        }
    }

    public static Result recordAll() {
        CountingClassLoader loader = CountingClassLoader.create();
        Map<String, long[]> byPair = new LinkedHashMap<>();

        try {
            Class<?> crsFactoryClass = loader.loadClass("org.locationtech.proj4j.CRSFactory");
            Class<?> transformFactoryClass =
                    loader.loadClass("org.locationtech.proj4j.CoordinateTransformFactory");
            Class<?> crsClass = loader.loadClass("org.locationtech.proj4j.CoordinateReferenceSystem");
            Class<?> transformClass = loader.loadClass("org.locationtech.proj4j.CoordinateTransform");
            Class<?> coordClass = loader.loadClass("org.locationtech.proj4j.ProjCoordinate");

            Object crsFactory = crsFactoryClass.getDeclaredConstructor().newInstance();
            Object transformFactory = transformFactoryClass.getDeclaredConstructor().newInstance();

            Method createFromName = crsFactoryClass.getMethod("createFromName", String.class);
            Method createTransform =
                    transformFactoryClass.getMethod("createTransform", crsClass, crsClass);
            Method transform = transformClass.getMethod("transform", coordClass, coordClass);
            Constructor<?> coordCtor =
                    coordClass.getConstructor(double.class, double.class, double.class);
            Constructor<?> emptyCoordCtor = coordClass.getConstructor();

            for (CrsPair pair : CrsPair.values()) {
                Object source = createFromName.invoke(crsFactory, pair.sourceCode());
                Object target = createFromName.invoke(crsFactory, pair.targetCode());
                Object ct = createTransform.invoke(transformFactory, source, target);

                Object in = coordCtor.newInstance(pair.x(), pair.y(), pair.z());
                Object out = emptyCoordCtor.newInstance();

                // Throwaway: absorbs static init, the init-file scan and any first-call lazy work.
                transform.invoke(ct, in, out);

                OpCounters.reset();
                transform.invoke(ct, in, out);
                byPair.put(pair.name(), OpCounters.snapshot());
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "Tier 2 could not drive core through the counting class loader. If this is a "
                    + "NoSuchMethodException on a java.lang.Math method, add a delegating method of "
                    + "that exact name and descriptor to CountingMath (and CountingStrictMath) - see "
                    + "their class comments.", e);
        }

        Result result = new Result(byPair, loader.rewrittenClasses(), loader.rewrittenReferences());

        // The dangerous failure mode is a rewrite that silently did nothing: every count would be
        // zero, every comparison would trivially match, and Tier 2 would pass forever while measuring
        // nothing. Refuse to return such a result.
        if (result.rewrittenReferences() == 0) {
            throw new IllegalStateException(
                    "CountingClassLoader redirected 0 Math/StrictMath references across "
                    + result.rewrittenClasses() + " classes. The bytecode rewrite is not taking "
                    + "effect, so Tier 2 is measuring nothing. Check that core is on the classpath as "
                    + "class files or a jar readable through the parent loader.");
        }
        if (result.total() == 0) {
            throw new IllegalStateException(
                    "CountingClassLoader redirected " + result.rewrittenReferences()
                    + " references but recorded 0 calls across all " + CrsPair.values().length
                    + " pairs. Either core no longer calls Math on the transform path (implausible) or "
                    + "the transform is being resolved from the parent loader.");
        }
        return result;
    }
}
