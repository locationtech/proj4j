/*******************************************************************************
 * Copyright 2026 Proj4J contributors
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
package org.locationtech.proj4j.domain;

import org.junit.Test;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.CrsTransformException;
import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.ProjectionException;
import org.locationtech.proj4j.proj.AiryProjection;
import org.locationtech.proj4j.proj.AugustProjection;
import org.locationtech.proj4j.proj.DenoyerProjection;
import org.locationtech.proj4j.proj.LinearProjection;
import org.locationtech.proj4j.proj.MercatorProjection;
import org.locationtech.proj4j.proj.PlateCarreeProjection;
import org.locationtech.proj4j.proj.Projection;
import org.locationtech.proj4j.proj.LarriveeProjection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The inverse-availability gate.
 *
 * <h2>The defect</h2>
 *
 * <p>{@code Projection.projectInverse} was an unconditional identity
 * ({@code dst.x = x; dst.y = y;}), and nothing in {@code core/src/main} read
 * {@code hasInverse()} — a method declared 66 times across 102 classes. So using any of 33
 * forward-only projections as a <em>source</em> CRS returned the projected metres back as if they
 * were lon/lat radians, which was then datum-shifted and re-projected. The answer is finite, in
 * range, and wrong by continental distances.
 *
 * <h2>Why the gate does not key on {@code hasInverse()} alone</h2>
 *
 * <p>Because that boolean, having gone unread for the library's whole life, is wrong in
 * <b>both</b> directions — and both directions were found by a test rather than by reading the
 * code:
 *
 * <ul>
 * <li>{@code KrovakProjection} and {@code NewZealandMapGridProjection} implement
 *     {@code projectInverse} and never declare {@code hasInverse()}. Gating on the boolean alone
 *     broke EPSG:2065, EPSG:5514 and EPSG:27200, all of which round-trip correctly — the same
 *     class of wrong answer the gate exists to prevent, only louder.</li>
 * <li>{@code LandsatProjection} declares {@code hasInverse()} but its {@code projectInverse}
 *     takes {@code Point2D.Double} and therefore overrides nothing.</li>
 * <li>{@code LongLatProjection} declares nothing, so inherits {@code false}, yet its inverse is
 *     real: the {@code DTR} multiply in {@code inverseProjectRadians}.</li>
 * <li>{@code PlateCarreeProjection} and {@code LinearProjection} declare {@code hasInverse()} and
 *     the base identity genuinely <em>is</em> their inverse, because their forward is the base
 *     identity too.</li>
 * </ul>
 *
 * <p>So the gate asks "is there an implementation", and keeps the boolean only as an affirmative
 * shortcut.
 */
public class NoInverseGateTest {

    private final CRSFactory csFactory = new CRSFactory();
    private final CoordinateTransformFactory ctFactory = new CoordinateTransformFactory();

    // ------------------------------------------------ the base class no longer echoes input

    /**
     * {@code Projection.projectInverse} raises for a forward-only projection instead of returning
     * the identity. Asserted through the public {@code inverseProject}, which is the only way in.
     *
     * <p><b>{@code aitoff} and {@code lagrng} used to be on this list and have been removed, because
     * they both gained the inverse upstream has.</b> {@code aitoff.cpp} has carried a
     * Newton&ndash;Raphson inverse since 2015 and {@code lagrng.cpp}'s is closed form; between them
     * that was 16 {@code builtins.gie} assertions, and the four {@code aitoff} inverse rows that
     * passed beforehand passed <em>by accident</em>, sitting 200 m from the origin where Aitoff is
     * the identity to nine decimals. {@code AiryProjection} and {@code LarriveeProjection} take
     * their place: both are declared {@code "no inv"} upstream and neither overrides
     * {@code projectInverse}.
     */
    @Test
    public void baseProjectInverseRaisesForForwardOnlyProjections() {
        Projection[] forwardOnly = {
                new AiryProjection(),
                new AugustProjection(),
                new DenoyerProjection(),
                new LarriveeProjection(),
        };
        for (Projection p : forwardOnly) {
            p.initialize();
            assertTrue(p + " must declare no inverse for this test to mean anything",
                    !p.hasInverse());
            try {
                ProjCoordinate out =
                        p.inverseProject(new ProjCoordinate(1e6, 2e6), new ProjCoordinate());
                fail(p + " has no inverse and must raise, got " + out);
            } catch (ProjectionException e) {
                assertEquals(p.toString(), ErrorCause.NO_INVERSE_AVAILABLE, e.cause());
                assertTrue(e.getMessage(), e.getMessage().contains("no inverse"));
            }
        }
    }

    /**
     * The identity is <b>opt-in</b>, not removed: two projections legitimately depend on it,
     * because their forward is the base identity too. Removing the identity outright would break
     * {@code +proj=eqc}.
     */
    @Test
    public void theBaseIdentityIsStillAvailableToProjectionsThatDeclareIt() {
        Projection[] identityIsTheirInverse = {
                new PlateCarreeProjection(),
                new LinearProjection(),
        };
        for (Projection p : identityIsTheirInverse) {
            p.setRadius(1.0);
            p.initialize();
            assertTrue(p + " must declare an inverse", p.hasInverse());
            ProjCoordinate out =
                    p.inverseProject(new ProjCoordinate(0.5, 0.25), new ProjCoordinate());
            assertNotNull(out);
            assertTrue(out.toString(), out.hasValidXandYOrdinates());
        }
    }

    // --------------------------------------------------------------- the transform-level gate

    /** A forward-only projection as a source CRS raises rather than inventing lon/lat. */
    @Test
    public void forwardOnlyProjectionAsSourceCrsRaises() {
        CoordinateReferenceSystem src =
                csFactory.createFromParameters("august", "+proj=august +ellps=WGS84 +units=m");
        CoordinateReferenceSystem tgt = csFactory.createFromName("EPSG:4326");
        try {
            ProjCoordinate out = ctFactory.createTransform(src, tgt)
                    .transform(new ProjCoordinate(1e6, 2e6), new ProjCoordinate());
            fail("august cannot be a source CRS, got " + out);
        } catch (CrsTransformException e) {
            assertEquals(ErrorCause.NO_INVERSE_AVAILABLE, e.cause());
            assertTrue(e.getMessage(), e.getMessage().contains("no inverse"));
        }
    }

    /** The same projection remains perfectly usable as a <em>target</em>. */
    @Test
    public void forwardOnlyProjectionIsStillUsableAsATarget() {
        CoordinateReferenceSystem src = csFactory.createFromName("EPSG:4326");
        CoordinateReferenceSystem tgt =
                csFactory.createFromParameters("august", "+proj=august +ellps=WGS84 +units=m");
        ProjCoordinate out = ctFactory.createTransform(src, tgt)
                .transform(new ProjCoordinate(10.0, 45.0), new ProjCoordinate());
        assertTrue(out.toString(), out.hasValidXandYOrdinates());
    }

    /**
     * The three CRSs that keying on {@code hasInverse()} alone rejected. Each has a working
     * {@code projectInverse} and no {@code hasInverse()} declaration, and each is a real EPSG
     * code in production use. This test is the regression witness for that mistake.
     */
    @Test
    public void krovakAndNzmgAreInvertibleDespiteNotDeclaringIt() {
        // Each probed inside its own area of use. A projection evaluated 12,000 km outside its
        // domain can legitimately fail to invert, which would make this test assert the wrong
        // thing -- Krovak is central Europe, NZMG is New Zealand.
        String[][] cases = {
                {"EPSG:2065", "15.0", "49.0"},    // S-JTSK / Krovak, Czechia
                {"EPSG:5514", "15.0", "49.0"},    // S-JTSK / Krovak East North, Czechia
                {"EPSG:27200", "174.0", "-41.0"}, // NZGD49 / New Zealand Map Grid
        };
        CoordinateReferenceSystem wgs84 = csFactory.createFromName("EPSG:4326");
        for (String[] c : cases) {
            String code = c[0];
            double lon = Double.parseDouble(c[1]);
            double lat = Double.parseDouble(c[2]);
            CoordinateReferenceSystem crs = csFactory.createFromName(code);
            assertTrue(code + " must not declare hasInverse(), or this test is vacuous",
                    !crs.getProjection().hasInverse());

            // Forward, then back: the inverse must actually compute, not raise and not echo.
            ProjCoordinate projected = ctFactory.createTransform(wgs84, crs)
                    .transform(new ProjCoordinate(lon, lat), new ProjCoordinate());
            ProjCoordinate back = ctFactory.createTransform(crs, wgs84)
                    .transform(projected, new ProjCoordinate());
            assertEquals(code + " longitude must round-trip", lon, back.x, 1e-6);
            assertEquals(code + " latitude must round-trip", lat, back.y, 1e-6);
        }
    }

    /** A geographic source CRS is not caught by the gate, though it declares no inverse. */
    @Test
    public void geographicSourceIsNotGatedOut() {
        CoordinateReferenceSystem wgs84 = csFactory.createFromName("EPSG:4326");
        assertTrue("LongLatProjection is expected to inherit hasInverse() == false",
                !wgs84.getProjection().hasInverse());
        assertTrue(wgs84.getProjection().isGeographic());

        CoordinateReferenceSystem merc = csFactory.createFromParameters(
                "merc", "+proj=merc +ellps=WGS84 +units=m +no_defs");
        ProjCoordinate out = ctFactory.createTransform(wgs84, merc)
                .transform(new ProjCoordinate(10.0, 45.0), new ProjCoordinate());
        assertTrue(out.toString(), out.hasValidXandYOrdinates());
    }

    /**
     * {@link ErrorCause#NO_INVERSE_AVAILABLE} is an <em>operation</em> error, not a coordinate
     * one — so it is not eligible for the lenient policy. A missing inverse is a property of the
     * CRS pair, and answering it with {@code NaN} once per row would report a planning-time
     * defect on every row of the dataset.
     */
    @Test
    public void noInverseIsAnOperationErrorSoLenientModeStillRaises() {
        assertTrue(ErrorCause.NO_INVERSE_AVAILABLE.isOperationError());
        assertTrue(!ErrorCause.NO_INVERSE_AVAILABLE.isCoordinateError());

        CoordinateTransformFactory lenient =
                new CoordinateTransformFactory(org.locationtech.proj4j.DomainErrorPolicy.RETURN_NAN);
        CoordinateReferenceSystem src =
                csFactory.createFromParameters("august", "+proj=august +ellps=WGS84 +units=m");
        CoordinateReferenceSystem tgt = csFactory.createFromName("EPSG:4326");
        try {
            ProjCoordinate out = lenient.createTransform(src, tgt)
                    .transform(new ProjCoordinate(1e6, 2e6), new ProjCoordinate());
            fail("lenient mode must not swallow an operation error, got " + out);
        } catch (CrsTransformException e) {
            assertEquals(ErrorCause.NO_INVERSE_AVAILABLE, e.cause());
        }
    }

    /** An ordinary invertible projection is unaffected, which is the bulk of the library. */
    @Test
    public void ordinaryInvertibleProjectionIsUnaffected() {
        MercatorProjection merc = new MercatorProjection();
        merc.setRadius(6378137.0);
        merc.initialize();
        assertTrue(merc.hasInverse());
        ProjCoordinate out =
                merc.inverseProject(new ProjCoordinate(1113194.9, 0.0), new ProjCoordinate());
        assertEquals(10.0, out.x, 1e-6);
    }
}
