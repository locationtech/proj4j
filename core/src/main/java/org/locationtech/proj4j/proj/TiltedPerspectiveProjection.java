/*******************************************************************************
 * Copyright 2026
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

package org.locationtech.proj4j.proj;

import org.locationtech.proj4j.util.FastStrictTrig;
import org.locationtech.proj4j.util.ProjectionMath;

/**
 * Tilted perspective, {@code +proj=tpers} &mdash; {@code PJ_PROJECTION(tpers)} of
 * {@code 9.8.1:src/projections/nsper.cpp:180-196}.
 *
 * <p>{@link PerspectiveProjection near-sided perspective} with the image plane rotated out of
 * the tangent plane. Upstream shares one struct, one {@code nsper_setup}, one forward and one
 * inverse with {@code nsper}; the only difference is that {@code Q-&gt;tilt} is 1, which
 * enables a four-line homography at the end of the forward and its exact inverse at the start
 * of the inverse. This class is therefore a two-parameter subclass and holds no arithmetic of
 * its own.
 *
 * <h2>Parameters</h2>
 *
 * <ul>
 * <li>{@code +tilt} &mdash; upstream's {@code omega}, read through {@code pj_param}'s
 *     {@code "r"} sigil, so it is an angle in the DMS-capable grammar and defaults to 0.</li>
 * <li>{@code +azi} &mdash; upstream's {@code gamma}, also {@code "r"}, also defaulting to
 *     0.</li>
 * <li>{@code +h} &mdash; inherited, and still mandatory: {@code nsper_setup} rejects
 *     {@code h/a &lt;= 0}.</li>
 * </ul>
 *
 * <p>Neither angle is validated, because upstream validates neither &mdash; any real
 * {@code omega} and {@code gamma} give a well-defined {@code (cg, sg, cw, sw)}. A tilt of
 * exactly &plusmn;90&deg; makes {@code cw} zero and collapses the forward's {@code ba}, but
 * that is upstream's behaviour too and the corpus does not probe it.
 *
 * <h2>Idempotence</h2>
 *
 * <p>{@code initialize()} runs twice (constructor-less here, but {@code Proj4Parser} calls it
 * after setting parameters, and {@link PerspectiveProjection} is reached through
 * {@code super}). The two angles are held as radians in their own fields and the four
 * trigonometric products are recomputed from them on every call, so nothing is read while
 * being written.
 *
 * @see PerspectiveProjection
 */
public class TiltedPerspectiveProjection extends PerspectiveProjection {

    private static final long serialVersionUID = 5218578966823159603L;

    /** Upstream's {@code omega}, {@code +tilt}, in radians. */
    private double tiltRadians = 0.0;

    /** Upstream's {@code gamma}, {@code +azi}, in radians. */
    private double aziRadians = 0.0;

    /**
     * A bare {@code +proj=tpers}. The tilt flag is set here rather than in
     * {@link #initialize()} so that it is true before any inherited method can observe it.
     */
    public TiltedPerspectiveProjection() {
        tilt = true;
    }

    /**
     * {@code +tilt}, the rotation of the image plane out of the tangent plane.
     *
     * @param tiltDegrees the tilt, in degrees
     */
    public void setTiltDegrees(double tiltDegrees) {
        this.tiltRadians = tiltDegrees * ProjectionMath.DTR;
    }

    /**
     * {@code +tilt} in radians, which is the form {@code pj_param}'s {@code "r"} sigil
     * produces.
     *
     * @param tiltRadians the tilt, in radians
     */
    public void setTiltRadians(double tiltRadians) {
        this.tiltRadians = tiltRadians;
    }

    /**
     * The {@code +tilt} in force.
     *
     * @return the tilt, in radians
     */
    public double getTiltRadians() {
        return tiltRadians;
    }

    /**
     * {@code +azi}, the azimuth the tilt is applied about.
     *
     * @param aziDegrees the azimuth, in degrees
     */
    public void setAziDegrees(double aziDegrees) {
        this.aziRadians = aziDegrees * ProjectionMath.DTR;
    }

    /**
     * {@code +azi} in radians.
     *
     * @param aziRadians the azimuth, in radians
     */
    public void setAziRadians(double aziRadians) {
        this.aziRadians = aziRadians;
    }

    /**
     * The {@code +azi} in force.
     *
     * @return the azimuth, in radians
     */
    public double getAziRadians() {
        return aziRadians;
    }

    /**
     * {@code PJ_PROJECTION(tpers)} ({@code nsper.cpp:180-196}), then {@code nsper_setup}.
     */
    @Override
    public void initialize() {
        tilt = true;
        cg = FastStrictTrig.cos(aziRadians);
        sg = FastStrictTrig.sin(aziRadians);
        cw = FastStrictTrig.cos(tiltRadians);
        sw = FastStrictTrig.sin(tiltRadians);
        super.initialize();
    }

    @Override
    public String toString() {
        return "Tilted perspective";
    }
}
