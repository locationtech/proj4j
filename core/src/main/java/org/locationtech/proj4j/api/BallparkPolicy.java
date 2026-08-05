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
 * What to do when the only coordinate operation available between two CRSs is a
 * <em>ballpark</em> one &mdash; a datum change performed with no parameters and no stated
 * accuracy, which is to say a datum change that is not actually performed.
 *
 * <h2>Why the default rejects</h2>
 *
 * <p>A ballpark transformation produces an entirely plausible coordinate that is wrong by the
 * size of the datum shift &mdash; tens to hundreds of metres. It cannot be detected downstream:
 * the ordinates are finite, they are in the right units, and they are in the right part of the
 * world. The only place it can be caught is at <em>planning</em> time, when the operation is
 * built, which is why {@link #REJECT} is the default for {@link Proj#createCrsToCrs} and why it
 * throws {@link org.locationtech.proj4j.CrsCreationException} with
 * {@link ErrorCause#BALLPARK_REJECTED} rather than failing on row 4,000,000.
 *
 * <p>There is deliberately <strong>no global "allow ballpark" property</strong>. Opting in is
 * done in code, at one of two granularities: per context ({@link ProjContext.Builder#ballparkPolicy})
 * or per call ({@link Proj#createCrsToCrs(String, String, ProjContext)}).
 *
 * <h2>How ballpark is computed here</h2>
 *
 * <p><b>With an authority database</b>, a ballpark candidate is synthesised for a pair whose datums
 * differ and for which the authority publishes nothing usable &mdash; which is exactly what PROJ
 * does, and it is not a gap in the data: there is <b>not one {@code Ballpark geographic offset} row
 * anywhere in the shipped database</b>. It is ranked last of all candidates, carries no accuracy, and
 * is selected only under {@link #ALLOW}. The corollary matters as much: for
 * {@code EPSG:4267 -> EPSG:4269} the authority publishes <b>nine</b> grid transformations and not one
 * of them is ballpark, so with a database this policy has nothing to reject there and
 * {@code EPSG:1241} is selected at 0.15&nbsp;m.
 *
 * <p><b>With no database</b>, Proj4J cannot consult that list at all. It computes the same property
 * two other ways, both of which are properties of what the engine will actually do &mdash; and for
 * {@code EPSG:4267 -> EPSG:4269} they do fire, which is why that pair is rejected in a deployment
 * without {@code proj4j-db} and selected in one with it. Both answers are right for their deployment:
 *
 * <ul>
 * <li><b>Grid-derived.</b> A datum declares a grid shift, and at least one of the grid files it
 * declares is absent from every configured resolver. PROJ's {@code @} prefix makes such a grid
 * optional and PROJ skips it silently; the shift then does not happen and nothing says so. That
 * is the single worst measured defect in this library's history &mdash; 95.573&nbsp;m at San
 * Francisco with no warning &mdash; and it is reported as ballpark here.</li>
 * <li><b>Legacy-engine-derived.</b> The two datums differ, but the legacy engine's
 * {@code datumTransform} would return from one of its early exits for every coordinate, so no
 * shift is applied. In practice this is a bare {@code +ellps=} on one side, which
 * {@code Datum.getTransformType()} reports as {@code TYPE_UNKNOWN}.</li>
 * </ul>
 *
 * @see BestOperationPolicy
 * @see GridPolicy
 * @since 1.5.0
 */
public enum BallparkPolicy {

    /**
     * <b>The default.</b> If every candidate operation is ballpark, refuse to build the
     * operation at all: {@link Proj#createCrsToCrs(String, String)} throws
     * {@link org.locationtech.proj4j.CrsCreationException} carrying
     * {@link ErrorCause#BALLPARK_REJECTED}, and the exception message names the datums and the
     * missing grids.
     */
    REJECT,

    /**
     * Build the operation anyway, and mark it. {@link CrsOperation#isBallparkTransformation()}
     * returns {@code true}, {@link CrsOperation#accuracy()} is empty (a ballpark operation never
     * has a stated accuracy), {@link CrsOperation#warnings()} says why, and
     * {@link CrsOperation#describe()} spells it out.
     *
     * <p>This is what the legacy {@link org.locationtech.proj4j.CoordinateTransformFactory} does
     * unconditionally and silently, and it is what the gie conformance runner needs, because gie
     * exercises ballpark rows explicitly.
     */
    ALLOW
}
