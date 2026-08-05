/*******************************************************************************
 * Copyright 2006, 2017 Jerry Huxtable, Martin Davis
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

/*
 * This file was semi-automatically converted from the public-domain USGS PROJ source.
 */
package org.locationtech.proj4j.proj;

import org.locationtech.proj4j.util.ProjectionMath;

/**
 * Lambert Equal Area Conic, {@code +proj=leac}.
 *
 * <p>Upstream this is {@code PJ_PROJECTION(leac)} in the <em>same file</em> as {@code aea}
 * ({@code 9.8.1:src/projections/aea.cpp:214-227}) and differs from it only in how the two standard
 * parallels are obtained:
 *
 * <pre>
 * Q-&gt;phi2 = pj_param(P-&gt;ctx, P-&gt;params, "rlat_1").f;
 * Q-&gt;phi1 = pj_param(P-&gt;ctx, P-&gt;params, "bsouth").i ? -M_HALFPI : M_HALFPI;
 * </pre>
 *
 * <p>So {@code leac} is a one-parallel operator: {@code +lat_1} becomes the <b>second</b> parallel,
 * the first is a pole chosen by {@code +south}, and <b>{@code +lat_2} is not read at all</b>.
 *
 * <h2>What was wrong</h2>
 *
 * <p>The old class set {@code projectionLatitude1} to &plusmn;45&deg; and
 * {@code projectionLatitude2} to &plusmn;90&deg; in its constructor and then inherited
 * {@code AlbersProjection}'s reading of those two fields. Since {@code Proj4Parser} assigns
 * {@code +lat_1} to {@code projectionLatitude1} and {@code +lat_2} to {@code projectionLatitude2}
 * whenever those keywords are present, {@code +proj=leac +lat_1=0 +lat_2=2} ran <em>as
 * {@code aea}</em> with parallels (0&deg;, 2&deg;) where upstream uses (90&deg;, 0&deg;) — two
 * different cones. All 16 of the projection's {@code builtins.gie} assertions failed, the forward
 * ones by about 3 km and the inverse ones by 2.4 mm against a 0.1 mm bar.
 *
 * <p>The fix is the two {@code protected} seams {@link AlbersProjection#firstStandardParallel()}
 * and {@link AlbersProjection#secondStandardParallel()} rather than field mutation, because
 * {@code initialize()} runs twice — once from the constructor and once from the parser — and any
 * scheme that <em>writes</em> the shared fields is not idempotent across those two calls.
 */
public class LambertEqualAreaConicProjection extends AlbersProjection {

    private static final long serialVersionUID = -4045880544360988887L;

    /** Upstream's {@code +south} flag: which pole is the first standard parallel. */
    private boolean south;

    public LambertEqualAreaConicProjection() {
        this( false );
    }

    public LambertEqualAreaConicProjection( boolean south ) {
        this.south = south;
        minLatitude = ProjectionMath.toRad(0);
        maxLatitude = ProjectionMath.toRad(90);
        initialize();
    }

    /**
     * @return {@code -pi/2} under {@code +south}, otherwise {@code +pi/2}. Upstream reads
     *         {@code bsouth}, a boolean parameter, so it is the pole and not {@code +lat_1}.
     */
    @Override
    protected double firstStandardParallel() {
        return south ? -ProjectionMath.HALFPI : ProjectionMath.HALFPI;
    }

    /**
     * @return {@code +lat_1}. Deliberately not {@code +lat_2}: {@code leac} never reads
     *         {@code +lat_2}, so a definition supplying it is silently ignored upstream and is
     *         silently ignored here.
     */
    @Override
    protected double secondStandardParallel() {
        return projectionLatitude1;
    }

    /**
     * @param south whether the first standard parallel is the south pole
     * @since 1.5.0
     */
    public void setSouth(boolean south) {
        this.south = south;
    }

    /**
     * @return whether the first standard parallel is the south pole
     * @since 1.5.0
     */
    public boolean isSouth() {
        return south;
    }

    public String toString() {
        return "Lambert Equal Area Conic";
    }

}
