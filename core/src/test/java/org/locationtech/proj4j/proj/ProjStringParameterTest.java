/*******************************************************************************
 * Copyright 2026 Space Ocean Corp
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

import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.ProjCoordinate;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * PROJ string parameters that must mean what they mean in PROJ: +R gives a sphere, polar
 * stereographic without +lat_ts has its true scale k_0 at the pole, and the azimuthal
 * projections are centred on lat_0 = lon_0 = 0 unless told otherwise.
 */
public class ProjStringParameterTest {

    private static final CRSFactory factory = new CRSFactory();

    private static ProjCoordinate project(String def, double lon, double lat) {
        Projection p = factory.createFromParameters(null, def).getProjection();
        return p.project(new ProjCoordinate(lon, lat), new ProjCoordinate());
    }

    @Test
    public void radiusGivesASphere() {
        // spherical Mercator: x = R * lon, y = R * asinh(tan(lat)); with the WGS84
        // eccentricity left in place y came out 0.6% smaller
        ProjCoordinate xy = project("+proj=merc +R=6371000", 10, 45);
        assertEquals(1111949.2664455874, xy.x, 1e-6);
        assertEquals(5615231.122901508, xy.y, 1e-6);
    }

    @Test
    public void polarStereographicWithoutLatTs() {
        // UPS north written with k_0 and without +lat_ts, as PROJ accepts it: the same
        // coordinates as with +lat_ts=90 (they were half of them). Expected values from a
        // 50-digit computation.
        String def = "+proj=stere +lat_0=90 +k=0.994 +ellps=WGS84";
        ProjCoordinate xy = project(def, 0, 85);
        assertEquals(0.0, xy.x, 1e-6);
        assertEquals(-555457.3913826781, xy.y, 1e-6);
        xy = project(def, 45, 87);
        assertEquals(235568.72477391988, xy.x, 1e-6);
        assertEquals(-235568.72477391988, xy.y, 1e-6);
    }

    @Test
    public void azimuthalDefaultCentre() {
        // without +lat_0 and +lon_0 the centre is (0, 0), as in PROJ; it was (45, 45) for
        // ortho and the north pole for gnom, aeqd and stere
        ProjCoordinate xy = project("+proj=ortho +R=1", 30, 0);
        assertEquals(0.5, xy.x, 1e-12);
        assertEquals(0.0, xy.y, 1e-12);
        xy = project("+proj=gnom +R=1", 45, 0);
        assertEquals(1.0, xy.x, 1e-12);
        assertEquals(0.0, xy.y, 1e-12);
        xy = project("+proj=aeqd +R=1", 0, 30);
        assertEquals(0.0, xy.x, 1e-12);
        assertEquals(Math.PI / 6, xy.y, 1e-12);
    }
}
