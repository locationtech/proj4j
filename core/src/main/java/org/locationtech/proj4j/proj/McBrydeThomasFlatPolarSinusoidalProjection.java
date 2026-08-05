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

/**
 * McBryde-Thomas Flat-Polar Sinusoidal ({@code +proj=mbtfps}),
 * {@code 9.8.1:src/projections/gn_sinu.cpp:157-169}.
 *
 * <p><b>This lives in {@code gn_sinu.cpp}, not with the other McBryde-Thomas projections.</b>
 * The commonly-cited file map places it elsewhere; it is {@code gn_sinu.cpp}'s fourth
 * {@code PROJ_HEAD} and is simply the general sinusoidal series at
 * {@code (m, n) = (0.5, 1.785398163397448309615660845)}. Writing it as its own algorithm
 * would duplicate the Newton solve for nothing.
 *
 * <p>{@code n} is {@code (pi + 4)/4 = 1.7853981633974483096156608458...} written out to 28
 * digits. Only the first 17 survive as a {@code double}, but the literal is transcribed in
 * full as upstream has it.
 *
 * <p>Not to be confused with the four McBryde-Thomas projections proj4j already has:
 * {@code mbt_fps} (Flat-Polar Sine No. 2), {@code mbt_s} (Flat-Polar Sine No. 1, in
 * {@code sts.cpp}), {@code mbtfpp} (Flat-Polar Parabolic) and {@code mbtfpq} (Flat-Polar
 * Quartic). Five of the six are in three different upstream files.
 *
 * @see GeneralSinusoidalSeriesProjection
 */
public class McBrydeThomasFlatPolarSinusoidalProjection
        extends GeneralSinusoidalSeriesProjection {

    private static final long serialVersionUID = 5318177017302017247L;

    public McBrydeThomasFlatPolarSinusoidalProjection() {
        super(0.5, 1.785398163397448309615660845);
        initialize();
    }

    public String toString() {
        return "McBryde-Thomas Flat-Polar Sinusoidal";
    }
}
