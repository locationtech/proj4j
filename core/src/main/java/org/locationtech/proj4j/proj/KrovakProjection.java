/*******************************************************************************
 * Copyright 2009, 2017 Martin Davis
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
package org.locationtech.proj4j.proj;

import static java.lang.Math.*;

import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.ProjectionException;
import org.locationtech.proj4j.util.ProjectionMath;

/**
 * The Krovak projection.
 *
 * While Krovak defines parameters for the azimuth and the latitude of the
 * pseudo-standard parallel, these are hardcoded in this implementation.
 *
 * @see <a href="http://www.ihsenergy.com/epsg/guid7.html#1.4.3"> Guidance Note 7 </a>
 */
public class KrovakProjection extends Projection {

    // TODO: should be set on parsing https://github.com/OSGeo/PROJ/blob/e3d7e18f988230973ced5163fa2581b6671c8755/src/projections/krovak.cpp#L219
    boolean czech = false;
    private double s45, alfa, k, ro0, ad, s0, n;

    public KrovakProjection() {
        minLatitude = Math.toRadians(-60);
        maxLatitude = Math.toRadians(60);
        minLongitude = Math.toRadians(-90);
        maxLongitude = Math.toRadians(90);
        initialize();
    }

    @Override
    public void initialize() {
        super.initialize();

        double s90, fi0, e2, uq, u0, g, k1, n0;

        s45 = 0.785398163397448;    /* 45deg */
        s90 = 2 * s45;
        fi0 = projectionLatitude;    /* Latitude of projection centre 49deg 30' */

        /* Ellipsoid is used as Parameter in for.c and inv.c, therefore a must
           be set to 1 here.
           Ellipsoid Bessel 1841 a = 6377397.155m 1/f = 299.1528128,
           e2=0.006674372230614;
           */
        a = 1; /* 6377397.155; */
        /* e2 = P->es; */      /* 0.006674372230614; */
        e2 = 0.006674372230614;
        e = sqrt(e2);

        alfa = sqrt(1. + (e2 * pow(cos(fi0), 4)) / (1. - e2));

        uq = 1.04216856380474;      /* DU(2, 59, 42, 42.69689) */
        u0 = asin(sin(fi0) / alfa);
        g = pow(   (1. + e * sin(fi0)) / (1. - e * sin(fi0)) , alfa * e / 2.  );

        k = tan( u0 / 2. + s45) / pow  (tan(fi0 / 2. + s45) , alfa) * g;

        k1 = scaleFactor;
        n0 = a * sqrt(1. - e2) / (1. - e2 * pow(sin(fi0), 2));
        s0 = 1.37008346281555;       /* Latitude of pseudo standard parallel 78deg 30'00" N */
        n = sin(s0);
        ro0 = k1 * n0 / tan(s0);
        ad = s90 - uq;
    }

    @Override
    public ProjCoordinate project(double lplam, double lpphi, ProjCoordinate out) {
        double gfi, u, deltav, s, d, eps, ro;
        /* Transformation */

        gfi =pow ( ((1. + e * sin(lpphi)) /
                    (1. - e * sin(lpphi))) , (alfa * e / 2.));

        u= 2. * (atan(k * pow( tan(lpphi / 2. + s45), alfa) / gfi)-s45);

        deltav = - lplam * alfa;

        s = asin(cos(ad) * sin(u) + sin(ad) * cos(u) * cos(deltav));
        d = asin(cos(u) * sin(deltav) / cos(s));
        eps = n * d;
        ro = ro0 * pow(tan(s0 / 2. + s45) , n) / pow(tan(s / 2. + s45) , n)   ;

        /* x and y are reverted! */
        out.y = ro * cos(eps) / a;
        out.x = ro * sin(eps) / a;

        if(!czech) {
            out.y *= -1.0;
            out.x *= -1.0;
        }

        return out;
    }

    @Override
    protected ProjCoordinate projectInverse(double x, double y, ProjCoordinate dst) {
        /* calculate lat/lon from xy */

        /* Constants, identisch wie in der Umkehrfunktion */
        double u, deltav, s, d, eps, ro, fi1;
        int ok;

        /* Transformation */
        /* revert y, x*/
        dst.x = y;
        dst.y = x;

        if(!czech) {
          dst.x *= -1.0;
          dst.y *= -1.0;
        }

        ro = sqrt(dst.x * dst.x + dst.y * dst.y);
        eps = atan2(dst.y, dst.x);
        d = eps / sin(s0);
        s = 2. * (atan(  pow(ro0 / ro, 1. / n) * tan(s0 / 2. + s45)) - s45);

        u = asin(cos(ad) * sin(s) - sin(ad) * cos(s) * cos(d));
        deltav = asin(cos(s) * sin(d) / cos(u));

        dst.x = projectionLongitude - deltav / alfa;

        /* ITERATION FOR lp.phi */
        fi1 = u;

        ok = 0;
        do
        {
            dst.y = 2. * ( atan( pow( k, -1. / alfa)  *
                        pow( tan(u / 2. + s45) , 1. / alfa)  *
                        pow( (1. + e * sin(fi1)) / (1. - e * sin(fi1)) , e / 2.)
                        )  - s45);

            if (abs(fi1 - dst.y) < 0.000000000000001) ok=1;
            fi1 = dst.y;

        }
        while (ok==0);

        dst.x -= projectionLongitude;

        return dst;
    }

    public String toString() {
        return "Krovak";
    }
}
