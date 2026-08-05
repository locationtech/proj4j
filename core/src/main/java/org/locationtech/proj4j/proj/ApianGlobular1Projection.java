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
 * Apian Globular I ({@code +proj=apian}), {@code 9.8.1:src/projections/bacon.cpp:57-68}.
 * Both flags clear, so the northing is the latitude itself.
 *
 * <p>Replaces one of the three registrations that bound a {@code +proj=} name to the
 * abstract {@link Projection} class. See {@link BaconFamilyProjection}.
 *
 * @see BaconFamilyProjection
 */
public class ApianGlobular1Projection extends BaconFamilyProjection {

    private static final long serialVersionUID = 1924854499889847619L;

    public ApianGlobular1Projection() {
        super(false, false);
    }

    public String toString() {
        return "Apian Globular I";
    }
}
