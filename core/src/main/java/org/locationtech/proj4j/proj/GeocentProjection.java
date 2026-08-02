/*
 * Copyright (c) 2021 PROJ4J contributors
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
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.locationtech.proj4j.proj;

import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.datum.GeocentricConverter;

public class GeocentProjection extends Projection {

  @Override
  public ProjCoordinate projectRadians(ProjCoordinate src, ProjCoordinate dst) {
    GeocentricConverter geocentricConverter = new GeocentricConverter(this.ellipsoid);
    geocentricConverter.convertGeodeticToGeocentric(dst);
    return dst;
  }
  
  @Override
  public ProjCoordinate inverseProjectRadians(ProjCoordinate src, ProjCoordinate dst) {
    GeocentricConverter geocentricConverter = new GeocentricConverter(this.ellipsoid);
    geocentricConverter.convertGeocentricToGeodetic(dst);
    return dst;
  }
}
