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
package org.locationtech.proj4j.pipeline;

import org.locationtech.proj4j.Registry;
import org.locationtech.proj4j.datum.Ellipsoid;

/**
 * {@code pj_ell_set} ({@code 9.8.1:src/ell_set.cpp:92-133}) reduced to what a
 * non-projection operator needs: the pair {@code (a, es)}.
 *
 * <h2>Why this is not {@code Proj4Parser}</h2>
 *
 * <p>{@link Cs2csOperator} gets its ellipsoid by building a whole
 * {@code CoordinateReferenceSystem}, which is right for a projection because the
 * projection is what it wants. {@code +proj=cart} and {@code +proj=deformation} are
 * not projections and their parameter lists carry keys — {@code xy_grids},
 * {@code z_grids}, {@code dt} — that are outside {@code Proj4Keyword}'s allow-list,
 * so handing the list to {@code Proj4Parser} risks an
 * {@code UnsupportedParameterException} about a key that has nothing to do with the
 * ellipsoid. Upstream has the same separation: {@code deformation} creates
 * {@code +proj=cart +a=1} and then calls {@code pj_inherit_ellipsoid_def}, i.e. it
 * copies {@code (a, es)} across rather than re-parsing.
 *
 * <h2>The order, which is the whole content of the file</h2>
 *
 * <p>Verbatim from {@code ell_set.cpp}, and the first branch short-circuits
 * everything:
 *
 * <pre>
 * if (+R)  -&gt; size only; es = 0; b = a.  ALL shape parameters are IGNORED.
 * else       +ellps seeds a and es
 *            +a (or +R) overrides the size
 *            the FIRST present of rf, f, es, e, b overrides the shape
 * </pre>
 *
 * <p><b>{@code +ellps} plus a shape parameter is not a contradiction.</b>
 * {@code ell_set.cpp}'s own comment calls later shape and size parameters
 * "modifiers for the built in ellipsoid definition", in accordance with historical
 * PROJ behaviour, and there is no contradiction check anywhere in the file. So
 * {@code +ellps=GRS80 +rf=300} is a valid ellipsoid with {@code f = 1/300}.
 *
 * <p>Spherification ({@code R_A}, {@code R_V}, {@code R_a}, {@code R_g},
 * {@code R_h}, {@code R_lat_a}, {@code R_lat_g}, {@code R_C}) is deliberately
 * <em>refused</em> rather than ignored: no operator reached through this class is
 * exercised with one anywhere in the corpus, and silently dropping it would change
 * the radius by up to the flattening — kilometres — while reporting success.
 *
 * <p>Stateless; not instantiable.
 */
final class StepEllipsoid {

    private StepEllipsoid() {
        throw new AssertionError("no instances");
    }

    /** The spherification keys, in {@code ellps_spherification}'s own order. */
    private static final String[] SPHERIFICATION = {
        "R_A", "R_V", "R_a", "R_g", "R_h", "R_lat_a", "R_lat_g", "R_C",
    };

    /**
     * Resolve {@code (a, es)} for one step.
     *
     * @param registry resolves {@code +ellps=} names
     * @param params   the step's fully expanded parameter list
     * @return {@code {a, es}}
     * @throws PipelineDefinitionException on an unknown {@code +ellps}, a physically
     *                                    impossible shape, or a spherification request
     */
    static double[] resolve(final Registry registry, final ProjParams params) {
        for (int i = 0; i < SPHERIFICATION.length; i++) {
            if (params.has(SPHERIFICATION[i])) {
                throw new PipelineDefinitionException(PipelineErrorCode.NOT_IMPLEMENTED_HERE,
                        "+" + SPHERIFICATION[i] + ": spherification of a non-projection "
                                + "operator's ellipsoid is not implemented, and ignoring it would "
                                + "change the radius by up to the flattening");
            }
        }

        // ell_set.cpp:96-103. +R is size-only and wins outright: every shape parameter
        // is ignored, not merged.
        if (params.has("R")) {
            final double r = positive(params, "R");
            return new double[] {r, 0.0};
        }

        double a = Double.NaN;
        double es = Double.NaN;

        final String ellps = params.value("ellps");
        if (ellps != null && !ellps.isEmpty()) {
            final Ellipsoid e = registry.getEllipsoid(ellps);
            if (e == null) {
                throw new PipelineDefinitionException(PipelineErrorCode.ILLEGAL_ARG_VALUE,
                        "unknown +ellps=" + ellps);
            }
            a = e.getEquatorRadius();
            es = e.getEccentricitySquared();
        }

        if (params.has("a")) {
            a = positive(params, "a");
        }

        // ellps_shape: the FIRST present of these five wins, in this order.
        if (params.has("rf")) {
            final double rf = params.doubleValue("rf", Double.NaN);
            if (!(rf > 0)) {
                throw new PipelineDefinitionException(PipelineErrorCode.ILLEGAL_ARG_VALUE,
                        "+rf must be positive, got " + rf);
            }
            es = flatteningToEs(1.0 / rf);
        } else if (params.has("f")) {
            final double f = params.doubleValue("f", Double.NaN);
            if (!(f >= 0)) {
                throw new PipelineDefinitionException(PipelineErrorCode.ILLEGAL_ARG_VALUE,
                        "+f must not be negative, got " + f);
            }
            es = flatteningToEs(f);
        } else if (params.has("es")) {
            es = params.doubleValue("es", Double.NaN);
        } else if (params.has("e")) {
            final double e = params.doubleValue("e", Double.NaN);
            es = e * e;
        } else if (params.has("b")) {
            final double b = positive(params, "b");
            if (Double.isNaN(a)) {
                throw new PipelineDefinitionException(PipelineErrorCode.MISSING_ARG,
                        "+b without a major axis");
            }
            // Two steps, not es = 1 - b*b/(a*a): ell_set.cpp goes via the flattening,
            // and the two differ in the last bits.
            es = flatteningToEs((a - b) / a);
        }

        if (Double.isNaN(a)) {
            throw new PipelineDefinitionException(PipelineErrorCode.MISSING_ARG,
                    "no ellipsoid: none of +ellps, +a, +R was given");
        }
        if (Double.isNaN(es) || es < 0 || es >= 1) {
            throw new PipelineDefinitionException(PipelineErrorCode.ILLEGAL_ARG_VALUE,
                    "invalid eccentricity: es = " + es);
        }
        return new double[] {a, es};
    }

    /** {@code es = 2f - f*f}. */
    private static double flatteningToEs(final double f) {
        return 2 * f - f * f;
    }

    private static double positive(final ProjParams params, final String key) {
        final double v = params.doubleValue(key, Double.NaN);
        if (!(v > 0)) {
            throw new PipelineDefinitionException(PipelineErrorCode.ILLEGAL_ARG_VALUE,
                    "+" + key + " must be positive, got " + v);
        }
        return v;
    }
}
