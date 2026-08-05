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
 * Wagner VI ({@code +proj=wag6}), the third {@code PROJ_HEAD} of
 * {@code 9.8.1:src/projections/eck3.cpp}.
 *
 * <p>{@code C_x = C_y = 1}, {@code A = 0} — the unscaled member of the family, and the one
 * the other three are stretched or squeezed versions of. Equivalent to Kavrayskiy VII with
 * the {@code sqrt(3)/2} horizontal compression removed.
 *
 * <p>Fills the {@code wag6} gap in proj4j's Wagner set: {@code wag1}-{@code wag5} and
 * {@code wag7} were already present, with {@code wag6} the only one commented out. Note
 * that {@code wag4} and {@code wag5} live in {@code moll.cpp} upstream and {@code wag1} in
 * {@code urmfps.cpp} — the Wagner numbering does not correspond to any shared
 * implementation.
 *
 * @see Eckert3FamilyProjection
 */
public class Wagner6Projection extends Eckert3FamilyProjection {

    private static final long serialVersionUID = -5368770966081166656L;

    public Wagner6Projection() {
        super(1.0, 1.0, 0.0, 0.30396355092701331433);
    }

    public String toString() {
        return "Wagner VI";
    }
}
