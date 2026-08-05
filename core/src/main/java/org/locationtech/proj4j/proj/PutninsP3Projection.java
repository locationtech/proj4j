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
 * Putnins P3 ({@code +proj=putp3}), {@code 9.8.1:src/projections/putp3.cpp:41-55}.
 * {@code A = 4/pi^2}.
 *
 * @see PutninsP3FamilyProjection
 */
public class PutninsP3Projection extends PutninsP3FamilyProjection {

    private static final long serialVersionUID = -5360425661839927691L;

    public PutninsP3Projection() {
        super(4.0 * RPISQ);
    }

    public String toString() {
        return "Putnins P3";
    }
}
