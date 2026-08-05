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
package org.locationtech.proj4j.api;

import org.locationtech.proj4j.ErrorCause;

/**
 * What to do about a grid file a CRS declares and no configured resolver can find.
 *
 * <h2>The {@code @} wart, and this library's position on it</h2>
 *
 * <p>PROJ's {@code +nadgrids=} accepts an {@code @} prefix per token, meaning <em>optional</em>:
 * if the file is absent, PROJ clears the error and carries on with one fewer grid
 * ({@code 9.8.1:src/grids.cpp}, {@code getListOfGridSets}). {@code +datum=NAD27} expands to
 * {@code +nadgrids=@conus,@alaska,@ntv2_0.gsb,@ntv1_can.dat} &mdash; every token optional &mdash;
 * so on a deployment with none of those files a NAD27 transformation reports success and applies
 * no shift at all.
 *
 * <p>That is not a hypothetical. It is the worst measured defect this library has had: at San
 * Francisco, {@code EPSG:4267} to {@code EPSG:4326} was out by <b>95.573&nbsp;m</b>, with no
 * exception, no warning, no log line, and a perfectly plausible coordinate.
 *
 * <p><b>The position: {@code @} is parsed, recorded, and then reported. It is never a licence to
 * be silent.</b> The token &mdash; including its {@code @} &mdash; round-trips through
 * {@link Crs#toProjString()}, appears in {@link Crs#describe()} with the reason it could not be
 * resolved, and is listed by {@link Crs#missingGrids()}.
 *
 * <h2>The legacy API is not affected by this enum</h2>
 *
 * <p>{@link org.locationtech.proj4j.CoordinateTransformFactory} keeps 1.4.3's behaviour exactly,
 * whatever is set here: it skips the missing grid and returns the unshifted coordinate. That is
 * the frozen-API promise, and it is not conditional on a policy object the legacy caller has never
 * heard of. The new facade is where the strict default lives.
 *
 * @see Crs#missingGrids()
 * @see org.locationtech.proj4j.datum.Grid#describeNadGrids(String)
 * @since 1.5.0
 */
public enum GridPolicy {

    /**
     * <b>The default on the new facade.</b> A declared grid that cannot be resolved, and for which
     * the datum offers no non-grid fallback, makes the operation <em>ballpark</em> &mdash; because
     * that is exactly what it is: a datum change that will not be performed. What happens next is
     * {@link BallparkPolicy}'s decision, and it is taken <b>once, at creation time</b>: the default
     * {@link BallparkPolicy#REJECT} throws {@link ErrorCause#BALLPARK_REJECTED} from
     * {@link Proj#createCrsToCrs(String, String)}, naming the files.
     *
     * <p>Note what this deliberately does <em>not</em> do: it does not throw per coordinate. A
     * missing grid is a property of the deployment, not of row 4,000,000, and discovering it four
     * million times is worse than discovering it once. If the operation was built at all, it
     * transforms.
     */
    REQUIRE_ALL,

    /**
     * Proceed. The missing grid does not make the operation ballpark, so it is built and it
     * transforms, but every unresolved file is recorded in {@link CrsOperation#warnings()} and
     * {@link CrsOperation#missingGrids()} and named by {@link CrsOperation#describe()}.
     *
     * <p>This is the setting for a caller who has decided the residual datum error is acceptable
     * and wants it on the record rather than discovered later.
     */
    WARN,

    /**
     * Behaves as {@link #WARN} for the operation, and additionally emits nothing to any log sink
     * &mdash; the silence of proj4j 1.4.3 and of PROJ itself for an {@code @}-prefixed token.
     *
     * <p>Must be asked for by name. {@link Crs#missingGrids()} and
     * {@link CrsOperation#missingGrids()} still report the file: the introspection channel is never
     * switched off, whatever the policy.
     */
    PROJ4_COMPAT
}
