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

package org.locationtech.proj4j.numerics;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.proj.Projection;

/**
 * The American Polyconic ellipsoidal branch, which was dead code.
 *
 * <p>{@code PolyconicProjection.initialize()} contained the line
 * <pre>spherical = true;//FIXME</pre>
 * <b>after</b> {@code super.initialize()} had computed the real value, so every ellipsoidal
 * {@code +proj=poly} — which is every US State Plane Polyconic, and EPSG:5472, 5530, 5880,
 * 29100, 29101 — ran the spherical formulae. Measured against
 * {@code builtins.gie:5906-5914}, the forward of (2, 1) on GRS80 was
 * <b>(222605.0588, 111387.2967)</b> where upstream gives (222605.2858, 110642.1946): the
 * northing was out by <b>745.10 m</b>. The inverse of (200, 100) returned latitude
 * 0.00089832 instead of 0.00090437, <b>0.673 m</b>.
 *
 * <p>Turning the branch on exposed three further defects in it, all fixed here:
 * <ul>
 * <li>the forward read the <em>destination's</em> stale {@code out.x} as the multiplicand
 *     ({@code out.x *= sp}) instead of {@code lplam}, and then took the cosine of the
 *     <em>unscaled</em> {@code lplam} — {@code 9.8.1:poly.cpp:37-40} scales first and uses the
 *     scaled value in both;
 * <li>the inverse's Newton denominator used {@code (1.0 / es)} where {@code poly.cpp:88} has
 *     {@code P->one_es}, i.e. {@code (1 - es)} — a factor of 150 out for GRS80;
 * <li>the <em>spherical</em> inverse tested {@code |phi0 + y|} but then seeded from the raw
 *     {@code y}, so {@code lat_0} was ignored ({@code poly.cpp:112} uses the mutated value).
 * </ul>
 */
public class PolyconicEllipsoidalTest {

    /**
     * The ellipsoidal branch must actually be selected. {@code spherical} is not exposed, so the
     * observable consequence is asserted instead: the ellipsoidal and spherical formulae for the
     * same semi-major axis must give different northings, and only the ellipsoidal one may match
     * upstream.
     */
    @Test
    public void ellipsoidalDefinitionIsNotForcedSpherical() {
        Projection poly = new CRSFactory()
                .createFromParameters("p", "+proj=poly +ellps=GRS80 +no_defs")
                .getProjection();
        assertTrue("the ellipsoid must survive initialize()",
                poly.getEllipsoid().getEccentricitySquared() > 0.0);

        double ellipsoidalNorthing = GieRow.grs80("+proj=poly +ellps=GRS80").forward(2, 1).y;
        double sphericalNorthing = GieRow.sphere("+proj=poly +R=6378137", 6378137.0).forward(2, 1).y;
        assertFalse("the spherical formula must no longer be substituted for the ellipsoidal one",
                Math.abs(ellipsoidalNorthing - sphericalNorthing) < 1.0);
    }

    /** {@code builtins.gie:5906-5914}, {@code tolerance 0.1 mm}. */
    @Test
    public void ellipsoidalForwardMatchesGie() {
        GieRow row = GieRow.grs80("+proj=poly +ellps=GRS80");
        row.expectForward(2, 1, 222605.285770237, 110642.194561440, 0.1 * GieRow.MM);
        row.expectForward(2, -1, 222605.285770237, -110642.194561440, 0.1 * GieRow.MM);
        row.expectForward(-2, 1, -222605.285770237, 110642.194561440, 0.1 * GieRow.MM);
        row.expectForward(-2, -1, -222605.285770237, -110642.194561440, 0.1 * GieRow.MM);
    }

    /** {@code builtins.gie:5917-5924}. */
    @Test
    public void ellipsoidalInverseMatchesGie() {
        GieRow row = GieRow.grs80("+proj=poly +ellps=GRS80");
        row.expectInverse(200, 100, 0.001796631, 0.000904369, 0.1 * GieRow.MM);
        row.expectInverse(200, -100, 0.001796631, -0.000904369, 0.1 * GieRow.MM);
        row.expectInverse(-200, 100, -0.001796631, 0.000904369, 0.1 * GieRow.MM);
        row.expectInverse(-200, -100, -0.001796631, -0.000904369, 0.1 * GieRow.MM);
    }

    /**
     * The round trip is what proves the two halves are the same projection; before the fix the
     * forward was spherical and the inverse's Newton step carried the {@code 1/es} factor, so
     * they were inconsistent as well as wrong.
     */
    @Test
    public void ellipsoidalRoundTripCloses() {
        GieRow row = GieRow.grs80("+proj=poly +ellps=GRS80");
        row.expectRoundtrip(2, 1, 1, 0.1 * GieRow.MM);
        row.expectRoundtrip(-2, -1, 1, 0.1 * GieRow.MM);
        row.expectRoundtrip(6, 40, 1, 0.1 * GieRow.MM);
        row.expectRoundtrip(-3, -55, 1, 0.1 * GieRow.MM);
    }

    /** {@code builtins.gie:5929-5947} — the spherical branch must not have moved. */
    @Test
    public void sphericalStillMatchesGie() {
        GieRow row = GieRow.sphere("+proj=poly +R=6400000", 6400000.0);
        row.expectForward(2, 1, 223368.105210219, 111769.110491225, 0.1 * GieRow.MM);
        row.expectForward(2, -1, 223368.105210219, -111769.110491225, 0.1 * GieRow.MM);
        row.expectInverse(200, 100, 0.001790493, 0.000895247, 0.1 * GieRow.MM);
        row.expectInverse(-200, -100, -0.001790493, -0.000895247, 0.1 * GieRow.MM);
        row.expectRoundtrip(2, 1, 1, 0.1 * GieRow.MM);
    }

    /**
     * The spherical inverse now honours {@code lat_0} — {@code poly.cpp:112} seeds Newton from
     * {@code phi0 + y}, where proj4j reverted to the raw {@code y}. There is no corpus row with
     * a spherical {@code +proj=poly +lat_0}, so the assertion is that the round trip closes.
     */
    @Test
    public void sphericalInverseHonoursLatitudeOfOrigin() {
        GieRow row = GieRow.sphere("+proj=poly +R=6400000 +lat_0=40", 6400000.0);
        row.expectRoundtrip(1, 41, 1, 0.1 * GieRow.MM);
        row.expectRoundtrip(-1, 30, 1, 0.1 * GieRow.MM);
        row.expectRoundtrip(2, 55, 1, 0.1 * GieRow.MM);
    }
}
