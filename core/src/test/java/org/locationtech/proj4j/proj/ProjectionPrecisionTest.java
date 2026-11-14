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
        // aeqd sphere, oblique: the forward lost half the digits near the centre (acos)
        {"+proj=aeqd +lat_0=45 +a=6371000 +b=6371000 +lon_0=0", 2e-06, 45.000001, 0.15725337058322724, 0.11119492830453609, 1, 1.0e-06},
        // moll: near the poles 10 Newton iterations were not enough and the forward snapped to the pole
        {"+proj=moll +a=6371000 +b=6371000 +lon_0=0", 45.0, 89.3, 252308.41375123064, 8995812.5734922552, 1, 1.0e-06},
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
        // initialize() can run more than once; it must not divide M by W again
        p.initialize();
        ProjCoordinate xy = p.project(new ProjCoordinate(-150., 80.), new ProjCoordinate());
        assertTrue("x " + xy.x + ", y " + xy.y,
            Math.hypot(xy.x - -7143380.5118388616, xy.y - 4159277.561056562) < 1e-6);
    }

    @Test
    public void azimuthalEquidistantNearTheAntipode() {
        // 79 m from the antipode: as in PROJ, only points within 1e-14 of cos(c) = -1 are
        // rejected. Expected values from a 50-digit computation; the forward is ill-conditioned
        // there (c / sin(c) is about 2.6e5), hence the tolerance of 1e-4 m
        Projection p = factory.createFromParameters(null, "+proj=aeqd +lat_0=45 +a=6371000 +b=6371000 +lon_0=0").getProjection();
        ProjCoordinate xy = p.project(new ProjCoordinate(179.999, -45.0), new ProjCoordinate());
        assertTrue("x " + xy.x + ", y " + xy.y,
            Math.hypot(xy.x - 20015008.168952851, xy.y - -123.50602542169843) < 1e-4);
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
