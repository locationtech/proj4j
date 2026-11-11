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

import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Precision of spherical projections close to the poles, the equator and the projection
 * centres. Expected values are from a 50-digit computation on the same input, on a sphere of
 * radius 6371000 m. The inverse is checked as a great-circle distance in metres, because close
 * to the poles the longitude is ill-conditioned; its tolerance is at least 1e-6 m, more where
 * moving the input by 1 ulp moves the exact inverse by about as much.
 */
public class ProjectionPrecisionTest {

    private static final CRSFactory factory = new CRSFactory();
    private static final double R = 6371000.;

    // definition, lon, lat, x, y, check inverse (1/0), inverse tolerance in metres
    private static final Object[][] CASES = {
        // tmerc sphere: close to the equator acos() and asin() lost half the digits
        {"+proj=tmerc +a=6371000 +b=6371000 +k=0.999 +lon_0=0", 2.0, 1e-7, 222212.59457055129, 0.011115144217327468, 1, 1.0e-06},
        // ortho sphere, oblique: latitude from asin() lost precision near the pole
        {"+proj=ortho +lat_0=45 +a=6371000 +b=6371000 +lon_0=0", 10.0, 89.9999999, 0.0019308795231307598, 4504977.2951962777, 1, 1.0e-06},
        // aeqd sphere, oblique: latitude from asin() lost precision near the pole
        {"+proj=aeqd +lat_0=-30 +a=6371000 +b=6371000 +lon_0=0", -120.0, -89.9999999, -0.011644304801302344, -6671695.6042332702, 1, 1.0e-06},
        // aeqd sphere, oblique: the forward lost half the digits near the centre (acos)
        {"+proj=aeqd +lat_0=45 +a=6371000 +b=6371000 +lon_0=0", 2e-06, 45.000001, 0.15725337058322724, 0.11119492830453609, 1, 1.0e-06},
        // laea sphere, oblique: latitude from asin() lost precision near the pole
        {"+proj=laea +lat_0=45 +a=6371000 +b=6371000 +lon_0=0", 60.0, 89.9999999, 0.01042317987605592, 4876152.2900594383, 1, 1.0e-06},
        // laea sphere, equatorial: points within 1e-10 * R of the centre came back on the equator
        {"+proj=laea +lat_0=0 +a=6371000 +b=6371000 +lon_0=0", 1e-09, -2e-09, 0.00011119492664455874, -0.00022238985328911749, 1, 1.0e-06},
        // gnom sphere, oblique: latitude from asin() lost precision near the pole
        {"+proj=gnom +lat_0=45 +a=6371000 +b=6371000 +lon_0=0", 10.0, 89.9999999, 0.0027306760042264916, 6370999.9780988762, 1, 1.0e-06},
        // gnom sphere, polar: points within 1e-10 * R of the centre came back at the centre
        {"+proj=gnom +lat_0=90 +a=6371000 +b=6371000 +lon_0=0", 30.0, 89.999999999, 0.000055597665444302562, -0.000096297981331748516, 1, 1.0e-06},
        // moll: near the poles the Newton iteration stopped early or gave up
        {"+proj=moll +a=6371000 +b=6371000 +lon_0=0", -179.0, -89.99999, -591.0783918084689, -9009954.6009776294, 1, 2.9e-06},
        // moll: near the poles the Newton iteration stopped early or gave up
        {"+proj=moll +a=6371000 +b=6371000 +lon_0=0", 100.0, 89.99, 33021.103249211675, 9009905.5922611956, 1, 1.0e-06},
        // moll: near the poles the Newton iteration stopped early or gave up
        {"+proj=moll +a=6371000 +b=6371000 +lon_0=0", 45.0, 89.3, 252308.41375123064, 8995812.5734922552, 1, 1.0e-06},
        // eck4: near the poles the Newton iteration stopped early
        {"+proj=eck4 +a=6371000 +b=6371000 +lon_0=0", -150.0, 89.9999, -7042628.2805732957, 8451134.2278927005, 1, 1.8e-03},
        // eck4: near the poles the Newton iteration stopped early
        {"+proj=eck4 +a=6371000 +b=6371000 +lon_0=0", 60.0, -89.999, 2817110.4380865138, -8451134.2256175863, 1, 1.8e-04},
        // putp2: near the poles the Newton iteration stopped early
        {"+proj=putp2 +a=6371000 +b=6371000 +lon_0=0", 100.0, -89.9999, 26.820261630556856, -9481615.7309176723, 1, 1.0e-06},
        // putp2: near the poles the Newton iteration stopped early
        {"+proj=putp2 +a=6371000 +b=6371000 +lon_0=0", -60.0, 89.99, -1609.0354738766009, 9480819.1246850995, 1, 1.0e-06},
        // vandg: close to the origin the formulas lost the digits; within 1e-10 of the equator or the central meridian y or lon was 0
        {"+proj=vandg +a=6371000 +b=6371000 +lon_0=0", 1e-06, 1e-06, 0.11119492664455873, 0.11119492664455874, 1, 1.0e-06},
        // vandg: close to the origin the formulas lost the digits; within 1e-10 of the equator or the central meridian y or lon was 0
        {"+proj=vandg +a=6371000 +b=6371000 +lon_0=0", 10.0, 3e-09, 1111949.2664455874, 0.00033358477993369337, 0, 1.0e-06},
        // vandg: close to the origin the formulas lost the digits; within 1e-10 of the equator or the central meridian y or lon was 0
        {"+proj=vandg +a=6371000 +b=6371000 +lon_0=0", 1e-08, 0.5, 0.0011119406864577287, 55597.892321672304, 1, 1.0e-06},
        // wintri: close to the origin the forward lost half the digits (acos)
        {"+proj=wintri +a=6371000 +b=6371000 +lon_0=0", 1e-06, 4e-07, 0.090991907766723808, 0.044477970657823493, 0, 1.0e-06},
        // wintri: close to the origin the forward lost half the digits (acos)
        {"+proj=wintri +a=6371000 +b=6371000 +lon_0=0", 1e-09, 1e-09, 0.000090991907766723819, 0.00011119492664455874, 0, 1.0e-06},
        // aitoff: close to the origin the forward lost half the digits (acos)
        {"+proj=aitoff +a=6371000 +b=6371000 +lon_0=0", 1e-06, 4e-07, 0.11119492664455873, 0.044477970657823493, 0, 1.0e-06},
        // aitoff: close to the origin the forward lost half the digits (acos)
        {"+proj=aitoff +a=6371000 +b=6371000 +lon_0=0", 1e-09, 1e-09, 0.00011119492664455874, 0.00011119492664455874, 0, 1.0e-06},
    };

    private static double distance(double l1, double p1, double l2, double p2) {
        double a = Math.sin((p2 - p1) / 2), b = Math.sin((l2 - l1) / 2);
        return 2 * R * Math.asin(Math.sqrt(a * a + Math.cos(p1) * Math.cos(p2) * b * b));
    }

    @Test
    public void hammerUsesWAndM() {
        // +proj=hammer +R=6371000 +W=0.25 +M=2 at (-150, 80), from a 50-digit computation
        HammerProjection p = new HammerProjection();
        p.setW(0.25);
        p.setM(2.);
        p.setEllipsoid(new org.locationtech.proj4j.datum.Ellipsoid("sphere", R, R, 0., "sphere"));
        p.initialize();
        ProjCoordinate xy = p.project(new ProjCoordinate(-150., 80.), new ProjCoordinate());
        assertTrue("x " + xy.x + ", y " + xy.y,
            Math.hypot(xy.x - -7143380.5118388616, xy.y - 4159277.561056562) < 1e-6);
    }

    @Test
    public void precisionNearPolesEquatorAndCentres() {
        StringBuilder failures = new StringBuilder();
        for (Object[] c : CASES) {
            String def = (String) c[0];
            double lon = ((Number) c[1]).doubleValue(), lat = ((Number) c[2]).doubleValue();
            double x = ((Number) c[3]).doubleValue(), y = ((Number) c[4]).doubleValue();
            Projection p = factory.createFromParameters(null, def).getProjection();
            ProjCoordinate xy = p.project(new ProjCoordinate(lon, lat), new ProjCoordinate());
            double fwd = Math.hypot(xy.x - x, xy.y - y);
            if (!(fwd < 1e-6))
                failures.append(def).append(" forward (").append(lon).append(", ").append(lat)
                    .append(") is ").append(fwd).append(" m off\n");
            if (((Number) c[5]).intValue() == 1) {
                ProjCoordinate ll = p.inverseProject(new ProjCoordinate(x, y), new ProjCoordinate());
                double inv = distance(Math.toRadians(ll.x), Math.toRadians(ll.y), Math.toRadians(lon), Math.toRadians(lat));
                if (!(inv < ((Number) c[6]).doubleValue()))
                    failures.append(def).append(" inverse (").append(x).append(", ").append(y)
                        .append(") is ").append(inv).append(" m off\n");
            }
        }
        assertTrue(failures.toString(), failures.length() == 0);
    }
}
