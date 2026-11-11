/*
 * Copyright (c) 2026 PROJ4J contributors
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
package org.locationtech.proj4j.datum;

import org.junit.Test;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;

import static org.junit.Assert.assertTrue;

public class TransformerTypeTest {

    private final CRSFactory crsFactory = new CRSFactory();

    @Test
    public void isTransformerTypeWgs84() {

      String utm32znParameters = "+proj=tmerc +lat_0=0.0 +lon_0=9.0 +k_0=0.9996 +x_0=3.25E7 +y_0=0.0 +a=6378137.0 +rf=298.257222101 +pm=Greenwich +units=m +no_defs";
      CoordinateReferenceSystem utm32znCrs = crsFactory.createFromParameters("Anon", utm32znParameters);

      assertTrue(utm32znCrs.getDatum().getTransformType() == Datum.TYPE_WGS84);
    }
}
