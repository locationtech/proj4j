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
 * Kavrayskiy VII ({@code +proj=kav7}), the second {@code PROJ_HEAD} of
 * {@code 9.8.1:src/projections/eck3.cpp}.
 *
 * <p>{@code A = 0}, so the bounding meridian is a full semi-ellipse, and {@code C_y = 1}
 * makes the parallel spacing equal to the latitude itself. Widely used for Soviet-era world
 * maps; it is Wagner VI horizontally compressed by {@code sqrt(3)/2}.
 *
 * <p><b>{@code C_x} is the truncated {@code 0.8660254037844}, not {@code sqrt(3)/2}.</b>
 * That is upstream's live constant at {@code eck3.cpp:75} and is retained deliberately —
 * see {@link Eckert3FamilyProjection} for why.
 *
 * <p>Distinct from proj4j's existing {@link KavraiskyVProjection} ({@code kav5}), which is
 * a member of the {@code sts.cpp} sine/tangent family and unrelated.
 *
 * @see Eckert3FamilyProjection
 */
public class Kavrayskiy7Projection extends Eckert3FamilyProjection {

    private static final long serialVersionUID = -6898043903574542869L;

    public Kavrayskiy7Projection() {
        super(0.8660254037844, 1.0, 0.0, 0.30396355092701331433);
    }

    public String toString() {
        return "Kavrayskiy VII";
    }
}
