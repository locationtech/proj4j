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
package org.locationtech.proj4j;

/**
 * What a {@link CoordinateTransform} does with a <em>per-coordinate</em> failure: a coordinate
 * outside the operation's input contract, outside the projection's domain, or one whose
 * computation did not converge or did not come back finite.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Proj4J 1.4.3 answered those cases with a plausible coordinate rather than an error — the
 * input unchanged, a single {@code NaN} ordinate, a pole, or the projection's false
 * easting/northing. A caller's {@code isFinite} guard cannot see three of those four. The
 * library now fails closed, which is a <b>behaviour change</b> for any caller that was
 * (knowingly or not) depending on the old silence.
 *
 * <p>This enum is that caller's documented escape. It is deliberately <em>not</em> a way to get
 * the 1.4.3 behaviour back: {@link #RETURN_NAN} substitutes one honest sentinel for four
 * dishonest ones, so a downstream {@code isFinite} check becomes sufficient where before it was
 * not.
 *
 * <h2>Scope: per-coordinate causes only</h2>
 *
 * <p>Only causes for which {@link ErrorCause#isCoordinateError()} is {@code true} are affected.
 * A CRS that cannot be built ({@link ErrorCause#isCrsError()}), an operation that does not
 * exist or has no inverse ({@link ErrorCause#isOperationError()}), and an environment or
 * API-misuse failure ({@link ErrorCause#isEnvironmentError()}) always throw, under every
 * policy: none of them is a property of the coordinate, so returning {@code NaN} once per row
 * would report a planning-time defect four million times.
 *
 * @see CoordinateTransformFactory#CoordinateTransformFactory(DomainErrorPolicy)
 * @see BasicCoordinateTransform#BasicCoordinateTransform(CoordinateReferenceSystem,
 *      CoordinateReferenceSystem, DomainErrorPolicy)
 * @since 1.5.0
 */
public enum DomainErrorPolicy {

    /**
     * Throw {@link CrsTransformException} with the {@link ErrorCause} that explains it. The
     * default, and the only setting under which the fail-closed contract on
     * {@link CrsTransformException} holds.
     */
    THROW,

    /**
     * Write {@code NaN} into every ordinate of the destination coordinate and return normally.
     *
     * <p>Both ordinates, never one: a coordinate with one finite and one {@code NaN} ordinate
     * is the shape that survives a careless range check, and
     * {@link ProjCoordinate#hasValidXandYOrdinates()} is the intended test. {@code z} is
     * cleared too, so a partially-transformed height cannot be mistaken for a result.
     *
     * <p>No exception is thrown, so <b>the reason is lost</b>. Prefer {@link #THROW} and catch,
     * which gives the same skip-this-vertex control flow plus the {@link ErrorCause}.
     */
    RETURN_NAN
}
