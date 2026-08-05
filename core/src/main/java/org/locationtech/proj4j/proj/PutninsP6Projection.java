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
 * Putnins P6 ({@code +proj=putp6}), {@code 9.8.1:src/projections/putp6.cpp:67-85}.
 *
 * @see PutninsP6FamilyProjection
 */
public class PutninsP6Projection extends PutninsP6FamilyProjection {

    private static final long serialVersionUID = -4186016765979614472L;

    public PutninsP6Projection() {
        super(1.01346, 0.91910, 4.0, 2.1471437182129378784, 2.0);
    }

    public String toString() {
        return "Putnins P6";
    }
}
