/*
 * Copyright 2026 The Proj4J Contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.locationtech.proj4j.conformance.bridge;

/**
 * Why the bridge could not produce a coordinate.
 *
 * <p>The whole point of this taxonomy is that the conformance number must
 * distinguish <em>"we computed the wrong answer"</em> from <em>"we have not
 * implemented this"</em>. A run that silently lumps the two together is
 * worthless: it neither measures parity nor tracks progress.
 *
 * <p>The single most important boundary is between {@link #INVALID_DEFINITION}
 * and {@link #NOT_IMPLEMENTED}, because gie's {@code expect failure} rows
 * assert that PROJ <em>itself</em> rejects something:
 *
 * <blockquote>
 * <b>{@link #INVALID_DEFINITION} is a statement about PROJ.</b> It means PROJ
 * 9.8.1 would also have refused this definition — an unknown {@code +proj},
 * {@code +ellps}, {@code +datum}, {@code +units} or {@code +pm}, a
 * {@code |lat_0| > 90}, a non-positive {@code +k}/{@code +k_0}/{@code +to_meter},
 * a malformed {@code +axis}, an out-of-range eccentricity, a {@code +towgs84}
 * that is neither 3 nor 7 values, a {@code +no_defs} with no ellipsoid at all.
 * <br><br>
 * <b>{@link #NOT_IMPLEMENTED} is a statement about proj4j.</b> The definition is
 * legal PROJ; we simply lack the operator, the parameter, or the value grammar.
 * <br><br>
 * <b>When both apply, PROJ's verdict wins</b> — because that is what the corpus
 * asserts.
 * </blockquote>
 *
 * @see GieFailure
 */
public enum GieFailureKind {

    /**
     * proj4j has no implementation of this operation, parameter or value form.
     * The definition itself is legal PROJ 9.8.1. This is the conservative
     * default: anything the bridge is not certain it can execute faithfully
     * lands here, because a wrong number that looks like a pass is the worst
     * outcome available.
     */
    NOT_IMPLEMENTED,

    /**
     * PROJ 9.8.1 would reject this definition too. Some {@code expect failure}
     * rows in the corpus assert exactly this, so it must not be conflated with
     * {@link #NOT_IMPLEMENTED}.
     */
    INVALID_DEFINITION,

    /**
     * The input coordinate is outside the operation's area of validity — a
     * projection-specific rejection, distinct from {@link #INVALID_COORD}.
     */
    COORD_OUT_OF_DOMAIN,

    /**
     * The host-level pre-check rejected the coordinate before any projection
     * formula ran: {@code |phi| - pi/2 > 1e-12} or {@code |lam| > 10} radians
     * ({@code 9.8.1:src/fwd.cpp:56-70}, {@code PROJ_ERR_COORD_TRANSFM_INVALID_COORD}
     * = 2049). 244 of the corpus's {@code expect failure} assertions come from
     * this check and from no projection's own logic.
     */
    INVALID_COORD,

    /** The operation has no inverse. */
    NO_INVERSE,

    /**
     * The computation produced a non-finite or non-convergent result. Includes
     * the case where proj4j returns {@code NaN}/{@code Infinity} silently
     * instead of raising — see {@link GieOperation#transform}.
     */
    NUMERICAL,

    /** A required grid file is absent. */
    MISSING_GRID,

    /** Anything not covered above; always accompanied by a message. */
    OTHER
}
