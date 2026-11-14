/*
 * Copyright 2026 PROJ4J contributors
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
 */
package org.locationtech.proj4j;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class AreaOfUseTest {

    @Test
    public void epsgAreaOfUseIsAvailable() {
        CoordinateReferenceSystem crs = new CRSFactory().createFromName("EPSG:32633");
        AreaOfUse area = crs.getAreaOfUse();

        assertEquals(12, area.getWestLongitude(), 0);
        assertEquals(0, area.getSouthLatitude(), 0);
        assertEquals(18, area.getEastLongitude(), 0);
        assertEquals(84, area.getNorthLatitude(), 0);
        assertTrue(area.getName().contains("Between 12°E and 18°E"));
    }

    @Test
    public void globalAndAntimeridianAreasRetainTheirBounds() {
        CRSFactory factory = new CRSFactory();
        AreaOfUse world = factory.createFromName("EPSG:4326").getAreaOfUse();
        assertEquals(-180, world.getWestLongitude(), 0);
        assertEquals(-90, world.getSouthLatitude(), 0);
        assertEquals(180, world.getEastLongitude(), 0);
        assertEquals(90, world.getNorthLatitude(), 0);

        AreaOfUse americas = factory.createFromName("EPSG:8858").getAreaOfUse();
        assertTrue(americas.getWestLongitude() > americas.getEastLongitude());
    }

    @Test
    public void areaOfUseIsUnavailableForCustomCrs() {
        CoordinateReferenceSystem crs = new CRSFactory().createFromParameters(
                "custom", "+proj=longlat +datum=WGS84 +no_defs");

        assertNull(crs.getAreaOfUse());
    }
}
