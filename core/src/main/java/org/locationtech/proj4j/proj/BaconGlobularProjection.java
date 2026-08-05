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
 * Bacon Globular ({@code +proj=bacon}), {@code 9.8.1:src/projections/bacon.cpp:43-55}.
 * {@code bacn = 1}, so the northing is {@code (pi/2) sin(phi)} rather than {@code phi} —
 * which is what pulls the parallels together towards the poles.
 *
 * <p>Replaces one of the three registrations that bound a {@code +proj=} name to the
 * abstract {@link Projection} class. See {@link BaconFamilyProjection}.
 *
 * @see BaconFamilyProjection
 */
public class BaconGlobularProjection extends BaconFamilyProjection {

    private static final long serialVersionUID = -7925808189912110327L;

    public BaconGlobularProjection() {
        super(true, false);
    }

    public String toString() {
        return "Bacon Globular";
    }
}
