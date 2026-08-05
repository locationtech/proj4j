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
 * Eckert III ({@code +proj=eck3}), the first {@code PROJ_HEAD} of
 * {@code 9.8.1:src/projections/eck3.cpp}.
 *
 * <p>{@code A = 1}, so the bounding meridian is a semicircle joined to straight segments
 * rather than a full semi-ellipse. Poles are lines, not points.
 *
 * @see Eckert3FamilyProjection
 */
public class Eckert3Projection extends Eckert3FamilyProjection {

    private static final long serialVersionUID = 863122467110075033L;

    public Eckert3Projection() {
        super(0.42223820031577120149, 0.84447640063154240298, 1.0,
                0.4052847345693510857755);
    }

    public String toString() {
        return "Eckert III";
    }
}
