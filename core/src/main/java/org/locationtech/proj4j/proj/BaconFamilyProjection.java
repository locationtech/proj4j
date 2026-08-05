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

import java.util.Objects;

import org.locationtech.proj4j.ProjCoordinate;

/**
 * The three globular projections PROJ 9.8.1 implements in one file,
 * {@code src/projections/bacon.cpp}: {@code apian} (Apian Globular I), {@code bacon}
 * (Bacon Globular) and {@code ortel} (Ortelius Oval).
 *
 * <p>All three draw the parallels as circular arcs through the poles and differ by two
 * boolean flags:
 *
 * <table>
 * <caption>{@code bacon.cpp:43-82}</caption>
 * <tr><th>{@code +proj=}</th><th>{@code bacn}</th><th>{@code ortl}</th>
 *     <th>northing</th></tr>
 * <tr><td>{@code apian}</td><td>0</td><td>0</td><td>{@code phi}</td></tr>
 * <tr><td>{@code bacon}</td><td>1</td><td>0</td>
 *     <td>{@code (pi/2) sin(phi)}</td></tr>
 * <tr><td>{@code ortel}</td><td>0</td><td>1</td><td>{@code phi}</td></tr>
 * </table>
 *
 * <p>{@code ortl} adds a second easting branch, used only past {@code |lam| >= pi/2}, which
 * is what gives the Ortelius Oval its straight outer meridians.
 *
 * <p><b>None of the three has an inverse</b>, upstream or here — {@code P->inv} is never
 * assigned ({@code bacon.cpp:53, 66, 80}), and the doc string says {@code no inv} for all
 * three. That mark is accurate. {@link #hasInverse()} returns {@code false} and
 * {@link Projection#projectInverse} throws rather than echoing the input, so an inverse
 * request fails closed.
 *
 * <h2>Two of these were among the three broken registrations</h2>
 *
 * <p>{@code apian} and {@code bacon} were bound to the <b>abstract</b> {@link Projection}
 * class itself in {@code Registry}, alongside {@code alsk}. Until that binding was made to
 * report {@link org.locationtech.proj4j.ErrorCause#PROJECTION_NOT_IMPLEMENTED}, resolving
 * either name printed an {@code InstantiationException} stack trace to {@code System.err}
 * and then reported <i>"Unknown projection: apian"</i> — a message that was false, since the
 * name was in the registry. These classes replace those bindings with real implementations.
 * {@code alsk} needs {@code mod_ster}, which is out of this batch's scope, and so remains
 * bound to the abstract class where it fails closed with an accurate message.
 *
 * <h2>The {@code + EPS} inside the {@code ortel} square root is deliberate</h2>
 *
 * <p>{@code bacon.cpp:31} computes
 * {@code sqrt(HLFPI2 - phi*phi + EPS)} with {@code EPS = 1e-10} added <em>inside</em> the
 * radicand. At {@code |phi| = pi/2} the exact radicand is zero, and the epsilon is there so
 * that rounding cannot make it negative. Keep it: removing it is a correctness change at the
 * poles, and adding it outside the root changes the value everywhere.
 */
abstract class BaconFamilyProjection extends Projection {

    private static final long serialVersionUID = 6742930538699377977L;

    /** {@code (pi/2)^2}, as the literal at {@code bacon.cpp:8}. */
    private static final double HLFPI2 = 2.46740110027233965467;

    private static final double EPS = 1e-10;

    private static final double HALF_PI = Math.PI / 2.0;

    private final boolean bacn;
    private final boolean ortl;

    protected BaconFamilyProjection(boolean bacn, boolean ortl) {
        this.bacn = bacn;
        this.ortl = ortl;
        es = 0.0;
        initialize();
    }

    /** {@code bacon_s_forward}, {@code bacon.cpp:22-41}. */
    protected ProjCoordinate project(double lam, double phi, ProjCoordinate dst) {
        dst.y = bacn ? HALF_PI * StrictMath.sin(phi) : phi;
        final double ax = Math.abs(lam);
        if (ax >= EPS) {
            double x;
            if (ortl && ax >= HALF_PI) {
                x = Math.sqrt(HLFPI2 - phi * phi + EPS) + ax - HALF_PI;
            } else {
                final double f = 0.5 * (HLFPI2 / ax + ax);
                x = ax - f + Math.sqrt(f * f - dst.y * dst.y);
            }
            dst.x = lam < 0.0 ? -x : x;
        } else {
            dst.x = 0.0;
        }
        return dst;
    }

    /** {@code false}: {@code bacon.cpp} never assigns {@code P->inv} for any of the three. */
    public boolean hasInverse() {
        return false;
    }

    @Override
    public boolean equals(Object that) {
        if (this == that) {
            return true;
        }
        if (that != null && getClass() == that.getClass()) {
            BaconFamilyProjection p = (BaconFamilyProjection) that;
            return bacn == p.bacn && ortl == p.ortl && super.equals(that);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hash(bacn, ortl, super.hashCode());
    }
}
