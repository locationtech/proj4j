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
 * van der Grinten III ({@code +proj=vandg3}),
 * {@code 9.8.1:src/projections/vandg2.cpp:68-80}. Straight parallels, unlike II.
 *
 * @see VanDerGrinten2FamilyProjection
 */
public class VanDerGrinten3Projection extends VanDerGrinten2FamilyProjection {

    private static final long serialVersionUID = 7205426496546974661L;

    public VanDerGrinten3Projection() {
        super(true);
    }

    public String toString() {
        return "van der Grinten III";
    }
}
