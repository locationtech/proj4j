/*******************************************************************************
 * Copyright 2026 the original author or authors.
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

import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.ProjectionException;
import org.locationtech.proj4j.util.ProjectionMath;

/**
 * Equal Earth projection of Savric, Patterson and Jenny (2018),
 * <a href="https://doi.org/10.1080/13658816.2018.1504949">The Equal Earth map projection</a>.
 * Ellipsoidal coordinates are mapped through the authalic latitude and radius.
 */
public class EqualEarthProjection extends Projection {

    private static final double A1 = 1.340264;
    private static final double A2 = -0.081106;
    private static final double A3 = 0.000893;
    private static final double A4 = 0.003796;
    private static final double SQRT3 = Math.sqrt(3.0);
    private static final double SQRT3_OVER_2 = SQRT3 / 2.0;
    private static final double THETA_MAX = Math.asin(SQRT3_OVER_2);
    private static final double MAX_Y = THETA_MAX * (A1 + A2 * THETA_MAX * THETA_MAX
            + A3 * Math.pow(THETA_MAX, 6) + A4 * Math.pow(THETA_MAX, 8));
    private static final double EPS = 1e-12;
    private static final int MAX_ITER = 12;

    private double qp;
    private double radiusRatio;
    private double[] apa;

    @Override
    public void initialize() {
        super.initialize();
        qp = ProjectionMath.qsfn(1.0, e, one_es);
        radiusRatio = Math.sqrt(qp / 2.0);
        if (!spherical) {
            apa = ProjectionMath.authset(es);
        }
    }

    @Override
    public ProjCoordinate project(double lam, double phi, ProjCoordinate xy) {
        double beta = spherical
                ? phi
                : ProjectionMath.asin(ProjectionMath.qsfn(Math.sin(phi), e, one_es) / qp);
        double theta = Math.asin(SQRT3_OVER_2 * Math.sin(beta));
        double theta2 = theta * theta;
        double theta6 = theta2 * theta2 * theta2;
        double theta8 = theta6 * theta2;
        double denominator = 3.0 * (A1 + 3.0 * A2 * theta2 + 7.0 * A3 * theta6 + 9.0 * A4 * theta8);

        xy.x = radiusRatio * (2.0 * SQRT3 * lam * Math.cos(theta) / denominator);
        xy.y = radiusRatio * theta * (A1 + A2 * theta2 + A3 * theta6 + A4 * theta8);
        return xy;
    }

    @Override
    public ProjCoordinate projectInverse(double x, double y, ProjCoordinate lp) {
        x /= radiusRatio;
        y /= radiusRatio;
        if (Math.abs(y) > MAX_Y + EPS) {
            throw new ProjectionException(this, "Northing outside the projection domain");
        }

        double theta = y / A1;
        int i;
        for (i = 0; i < MAX_ITER; i++) {
            double theta2 = theta * theta;
            double theta6 = theta2 * theta2 * theta2;
            double theta8 = theta6 * theta2;
            double value = theta * (A1 + A2 * theta2 + A3 * theta6 + A4 * theta8);
            double derivative = A1 + 3.0 * A2 * theta2 + 7.0 * A3 * theta6 + 9.0 * A4 * theta8;
            double delta = (value - y) / derivative;
            theta -= delta;
            if (Math.abs(delta) < EPS) {
                break;
            }
        }
        if (i == MAX_ITER) {
            throw new ProjectionException(this, "Inverse projection did not converge");
        }

        double theta2 = theta * theta;
        double theta6 = theta2 * theta2 * theta2;
        double theta8 = theta6 * theta2;
        double beta = ProjectionMath.asin(Math.sin(theta) / SQRT3_OVER_2);
        lp.x = x * 3.0 * (A1 + 3.0 * A2 * theta2 + 7.0 * A3 * theta6 + 9.0 * A4 * theta8)
                / (2.0 * SQRT3 * Math.cos(theta));
        lp.y = spherical ? beta : ProjectionMath.authlat(beta, apa);
        return lp;
    }

    @Override
    public boolean hasInverse() {
        return true;
    }

    @Override
    public boolean isEqualArea() {
        return true;
    }

    @Override
    public String toString() {
        return "Equal Earth";
    }
}
