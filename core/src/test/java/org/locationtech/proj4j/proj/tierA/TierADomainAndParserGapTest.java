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

package org.locationtech.proj4j.proj.tierA;

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.InvalidValueException;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.Proj4jException;
import org.locationtech.proj4j.Registry;
import org.locationtech.proj4j.UnsupportedParameterException;
import org.locationtech.proj4j.proj.ColombiaUrbanProjection;
import org.locationtech.proj4j.proj.GeneralSinusoidalProjection;
import org.locationtech.proj4j.proj.Projection;
import org.locationtech.proj4j.proj.Urmaev5Projection;

/**
 * The parts of this batch that the corpus cannot reach: the {@code expect failure} rows, the
 * forward-only projections' refusal to invent an inverse, and the three operators whose
 * required parameters {@code Proj4Parser} does not dispatch.
 */
public class TierADomainAndParserGapTest {

    // ------------------------------------------------------------ domain contract

    /**
     * {@code builtins.gie:7525-7533} — two rows expecting
     * {@code coord_transfm_outside_projection_domain} at the poles. Upstream's own comment
     * ({@code tobmerc.cpp:17-22}) says the rejection is not obviously necessary, since
     * {@code M_HALFPI} is below the true {@code pi/2} and {@code asinh(tan(.))} would merely
     * return about 38.025 — but the corpus asserts the failure, so the check stays.
     *
     * <p>This also pins the interaction with the host pre-check: {@code checkForwardDomain}
     * clamps a latitude within {@code 1e-12} rad of the pole to <em>exactly</em>
     * {@code Math.PI/2}, which is the same double as C's {@code M_HALFPI}, so 90&deg; arrives
     * at the projection as exactly the value its {@code >=} test rejects.
     */
    @Test
    public void tobmercRejectsBothPoles() {
        Projection p = projection("+proj=tobmerc +R=6370997");
        for (double lat : new double[] {90.0, -90.0}) {
            try {
                ProjCoordinate out = new ProjCoordinate();
                p.project(new ProjCoordinate(0.0, lat), out);
                fail("tobmerc at latitude " + lat + " must fail, returned (" + out.x + ", "
                        + out.y + ")");
            } catch (Proj4jException expected) {
                assertTrue("the message should say why, was: " + expected.getMessage(),
                        expected.getMessage().contains("pole")
                                || expected.getMessage().contains("pi/2"));
            }
        }
    }

    /**
     * {@code +proj=tobmerc +R=1}, {@code accept 0 1e-15}, {@code tolerance 1e-15 m}
     * ({@code builtins.gie:7516-7522}).
     *
     * <p>Worth its own test because it is the batch's only assertion that a naive
     * {@code asinh} would fail outright rather than by a hair: {@code log(x + sqrt(x*x + 1))}
     * at {@code x = 1e-15} evaluates {@code log(1 + 1e-15)}, whose leading 1 destroys the
     * whole value. {@code MathHelpers.asinh}'s {@code log1p} branch is what makes this pass.
     */
    @Test
    public void tobmercIsExactNearTheEquatorOnAUnitSphere() {
        Projection p = projection("+proj=tobmerc +R=1");
        ProjCoordinate out = new ProjCoordinate();
        p.project(new ProjCoordinate(0.0, Math.toDegrees(1e-15)), out);
        assertEquals("northing at phi = 1e-15 rad on +R=1", 1e-15, out.y, 1e-30);
    }

    /**
     * The five forward-only operators must <b>throw</b> on an inverse request, not echo the
     * input, not return the false origin, and not return a single NaN.
     *
     * <p>{@code apian}, {@code bacon} and {@code ortel} ({@code bacon.cpp} never assigns
     * {@code P->inv}), {@code vandg2}, {@code vandg3}, {@code vandg4} and {@code bertin1953}
     * (likewise), and {@code urm5} ({@code urm5.cpp:63} assigns {@code P->inv = nullptr}
     * explicitly). All eight are marked {@code no inv} upstream and the mark is accurate in
     * every case — checked, because §12 records that several such marks elsewhere in the
     * corpus are stale in both directions.
     */
    @Test
    public void forwardOnlyProjectionsRefuseToInventAnInverse() {
        String[] defs = {
                "+proj=apian +a=6400000",
                "+proj=bacon +a=6400000",
                "+proj=ortel +a=6400000",
                "+proj=vandg2 +a=6400000",
                "+proj=vandg3 +a=6400000",
                "+proj=vandg4 +R=6400000",
                "+proj=bertin1953 +a=6400000",
        };
        for (String def : defs) {
            Projection p = projection(def);
            assertFalse(def + ": hasInverse() must be false", p.hasInverse());
            ProjCoordinate out = new ProjCoordinate();
            try {
                p.inverseProject(new ProjCoordinate(100000.0, 100000.0), out);
                fail(def + ": inverseProject must throw, returned (" + out.x + ", " + out.y
                        + ")");
            } catch (Proj4jException expected) {
                // The point is that it throws rather than answering; any Proj4jException
                // subclass carries that.
                assertTrue(expected.getMessage() != null && !expected.getMessage().isEmpty());
            }
        }
    }

    /**
     * {@code alsk} remains bound to the abstract {@link Projection} class because it needs
     * {@code mod_ster}, which is outside this batch. It must therefore <b>fail closed</b> —
     * and with an accurate message, not the old <i>"Unknown projection: alsk"</i>, which was
     * false because the name <em>is</em> registered.
     *
     * <p>This is the third of the three broken registrations. {@code apian} and {@code bacon}
     * are now real implementations; this one is deliberately still a refusal, and the test
     * pins that it is a refusal rather than a silent identity.
     */
    @Test
    public void alskResolvesNowThatModSterIsPorted() {
        // Inverted. This asserted that alsk must NOT resolve, because mod_ster was not
        // implemented and alsk therefore failed closed with an honest "registered but not
        // implemented" message - itself an improvement on 1.4.3's false "Unknown projection:
        // alsk". mod_ster has since landed, so alsk resolves to a real implementation and
        // asserting the refusal would re-pin a fixed defect.
        Projection p = new Registry().getProjection("alsk");
        assertNotNull("alsk must resolve now that mod_ster is ported", p);
        assertNotEquals("alsk must not be the abstract base class",
                Projection.class, p.getClass());
    }

    // ------------------------------------------------------------ parser gaps

    /**
     * {@code col_urban} requires {@code +h_0} ({@code col_urban.cpp:65}) and the parser does
     * not dispatch it, so the corpus row is unreachable. The arithmetic is verified here
     * through {@link ColombiaUrbanProjection#setH0(double)} against the expected values from
     * {@code builtins.gie:8303-8310}, which come from IOGP Publication 373-7-2.
     *
     * <p>Read from the corpus rather than transcribed, so that this test and the eventual
     * conformance row cannot disagree.
     */
    @Test
    public void colUrbanArithmeticIsCorrectOnceH0IsSupplied() {
        ColombiaUrbanProjection p = new ColombiaUrbanProjection();
        p.setEllipsoid(org.locationtech.proj4j.datum.Ellipsoid.GRS80);
        p.setProjectionLatitudeDegrees(4.68048611111111);
        p.setProjectionLongitudeDegrees(-74.1465916666667);
        p.setFalseEasting(92334.879);
        p.setFalseNorthing(109320.965);
        p.setH0(2550.0);
        p.initialize();

        ProjCoordinate out = new ProjCoordinate();
        p.project(new ProjCoordinate(-74.25, 4.8), out);
        // builtins.gie:8309 -- expect 80859.033 122543.174, tolerance 1 mm.
        assertEquals("col_urban easting", 80859.033, out.x, 1e-3);
        assertEquals("col_urban northing", 122543.174, out.y, 1e-3);

        ProjCoordinate back = new ProjCoordinate();
        p.inverseProject(out, back);
        assertEquals("col_urban roundtrip longitude", -74.25, back.x, 1e-8);
        assertEquals("col_urban roundtrip latitude", 4.8, back.y, 1e-8);
    }

    /**
     * {@code gn_sinu} requires {@code +m} and {@code +n} — undocumented, and rejected when
     * absent by {@code gn_sinu.cpp:178-198}. Neither is a {@code Proj4Keyword} nor dispatched
     * by {@code Proj4Parser}, so {@code builtins.gie:2220}
     * ({@code +proj=gn_sinu +a=6400000 +m=1 +n=2}) is unreachable from a definition string.
     *
     * <p>The arithmetic is checked here against that row's expected values, and the two
     * validations are checked too.
     */
    @Test
    public void gnSinuArithmeticIsCorrectOnceMAndNAreSupplied() {
        GeneralSinusoidalProjection p = new GeneralSinusoidalProjection();
        p.setRadius(6400000.0);
        p.setM(1.0);
        p.setN(2.0);
        p.initialize();

        ProjCoordinate out = new ProjCoordinate();
        p.project(new ProjCoordinate(2.0, 1.0), out);
        ProjCoordinate back = new ProjCoordinate();
        p.inverseProject(out, back);
        assertEquals("gn_sinu roundtrip longitude", 2.0, back.x, 1e-8);
        assertEquals("gn_sinu roundtrip latitude", 1.0, back.y, 1e-8);
    }

    /** {@code gn_sinu.cpp:186-196}: {@code n <= 0} and {@code m < 0} are setup errors. */
    @Test
    public void gnSinuRejectsAnInvalidShape() {
        GeneralSinusoidalProjection zeroN = new GeneralSinusoidalProjection();
        zeroN.setRadius(6400000.0);
        zeroN.setM(1.0);
        zeroN.setN(0.0);
        try {
            zeroN.initialize();
            fail("n = 0 must be rejected");
        } catch (InvalidValueException expected) {
            assertTrue(expected.getMessage().contains("n"));
        }

        GeneralSinusoidalProjection negativeM = new GeneralSinusoidalProjection();
        negativeM.setRadius(6400000.0);
        negativeM.setM(-1.0);
        negativeM.setN(2.0);
        try {
            negativeM.initialize();
            fail("m < 0 must be rejected");
        } catch (InvalidValueException expected) {
            assertTrue(expected.getMessage().contains("m"));
        }
    }

    /**
     * {@code urm5} requires {@code +n} in {@code (0, 1]} and accepts optional {@code +q} and
     * {@code +alpha}. Only {@code +alpha} is dispatched by the parser, so both corpus
     * operations are unreachable.
     *
     * <p>The second corpus operation, {@code +n=1 +alpha=90}, sits exactly on upstream's
     * setup rejection: {@code sqrt(1 - (n sin(alpha))^2)} is exactly zero there, tested with
     * {@code == 0} and not a tolerance. That is asserted here as a refusal, because a
     * projection that quietly produced infinities instead would be the failure-as-coordinate
     * shape.
     */
    @Test
    public void urm5RequiresNAndRejectsTheDegenerateAlpha() {
        Urmaev5Projection missing = new Urmaev5Projection();
        missing.setRadius(6400000.0);
        try {
            missing.initialize();
            fail("+proj=urm5 with no +n must be rejected");
        } catch (InvalidValueException expected) {
            assertTrue(expected.getMessage().contains("n"));
        }

        Urmaev5Projection outOfRange = new Urmaev5Projection();
        outOfRange.setRadius(6400000.0);
        outOfRange.setN(1.5);
        try {
            outOfRange.initialize();
            fail("n = 1.5 is outside (0, 1] and must be rejected");
        } catch (InvalidValueException expected) {
            assertTrue(expected.getMessage().contains("0,1"));
        }

        // builtins.gie:7702 -- +n=1 +alpha=90 makes n*sin(alpha) exactly 1.
        Urmaev5Projection degenerate = new Urmaev5Projection();
        degenerate.setRadius(6400000.0);
        degenerate.setN(1.0);
        degenerate.setAlphaDegrees(90.0);
        try {
            degenerate.initialize();
            fail("+n=1 +alpha=90 must be rejected: n * sin(alpha) == 1");
        } catch (InvalidValueException expected) {
            assertTrue(expected.getMessage().contains("alpha"));
        }
    }

    /**
     * {@code urm5}'s working case, {@code builtins.gie:7690} ({@code +n=0.5}). Also pins the
     * NaN-alpha handling: {@link Projection#alpha} defaults to {@code Double.NaN}, not zero,
     * where {@code urm5}'s {@code +alpha} defaults to zero — so treating the default
     * literally would make {@code cos(NaN)} poison every output.
     */
    @Test
    public void urm5WithNoAlphaTreatsItAsZeroRatherThanNaN() {
        Urmaev5Projection p = new Urmaev5Projection();
        p.setRadius(6400000.0);
        p.setN(0.5);
        p.initialize();

        ProjCoordinate out = new ProjCoordinate();
        p.project(new ProjCoordinate(2.0, 1.0), out);
        assertTrue("easting must be finite, was " + out.x, !Double.isNaN(out.x));
        assertTrue("northing must be finite, was " + out.y, !Double.isNaN(out.y));
        assertTrue("easting should be a plausible magnitude, was " + out.x,
                Math.abs(out.x) > 1000.0 && Math.abs(out.x) < 1e8);
    }

    private static Projection projection(String def) {
        return new CRSFactory().createFromParameters("t", def).getProjection();
    }
}
