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
 * What to do when the best-ranked coordinate operation between two CRSs cannot be executed but a
 * worse one can.
 *
 * <p>The classic case is a grid: <i>NAD27 to NAD83 (NADCON5)</i> outranks a three-parameter
 * Helmert, but needs a grid file that is not on the classpath. Silently using the Helmert is a
 * metre-scale change of answer with no signal, so the default refuses.
 *
 * <p>PROJ's equivalent knob is {@code PROJ_ONLY_BEST_DEFAULT} / {@code only_best_default} /
 * {@code ONLY_BEST}. <b>PROJ defaults it off; Proj4J defaults it on</b>, deliberately: PROJ can
 * fetch the missing grid from its CDN, and Proj4J will not, so "degrade quietly" has a very
 * different cost here. Proj4J honours <em>no</em> environment variable for this &mdash; not
 * {@code PROJ_ONLY_BEST_DEFAULT}, not anything else. It is set in code or not at all.
 *
 * <h2>What "degraded" means, precisely</h2>
 *
 * <p>A selection is degraded iff it is <b>strictly less accurate</b> than a candidate that could not
 * be used. That word "strictly" is load-bearing, and the case that forces it is the headline one:
 * {@code EPSG:4267} to {@code EPSG:4269} offers {@code EPSG:1241} (NADCON, 0.15&nbsp;m, which this
 * library can execute) and {@code EPSG:8555} (NADCON&nbsp;5, 0.15&nbsp;m, whose grid feeds the
 * unified {@code +proj=gridshift} operator, which it cannot). They are <b>tied</b>. Refusing a tie
 * would make {@link #REQUIRE_BEST} reject that pair outright, which is a worse answer than either
 * operation &mdash; so a tie is not a degradation, and the skipped candidate is recorded in
 * {@link CrsOperation#warnings()} instead. An <em>unknown</em> accuracy against a known one
 * <em>is</em> a degradation, because "we do not know" cannot be shown to be as good.
 *
 * <p>Two policies have to be conceded to reach the extreme case. Falling all the way back to a
 * ballpark offset is the largest degradation there is, so it needs {@link #ALLOW_DEGRADED}
 * <em>and</em> {@link BallparkPolicy#ALLOW}; either alone still refuses.
 *
 * <h2>Without a database there is nothing to rank</h2>
 *
 * <p>With no {@link ProjContext#database()} there is exactly one candidate operation per CRS pair
 * &mdash; the one the legacy datum model synthesises &mdash; so this enum is recorded and has
 * nothing to act on. {@link ProjContext#describe()} says which of the two states applies rather than
 * letting a caller assume it is being enforced.
 *
 * @since 1.5.0
 */
public enum BestOperationPolicy {

    /**
     * <b>The default.</b> If a more accurate candidate cannot be executed here, fail with
     * {@link ErrorCause#BEST_OPERATION_UNAVAILABLE} rather than quietly selecting a worse one. The
     * message names both operations, quantifies the accuracy gap in metres, and names the grid files
     * that would unlock the better one.
     */
    REQUIRE_BEST,

    /**
     * Select the best candidate that <em>can</em> be executed, recording in
     * {@link CrsOperation#warnings()} which one was skipped, why, and what accuracy was given up.
     */
    ALLOW_DEGRADED
}
