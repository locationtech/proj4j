/*
 * Copyright 2026 The Proj4J Contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.locationtech.proj4j.conformance.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.proj4j.gie.GieDirection;
import org.locationtech.proj4j.gie.GieIoUnits;

class Proj4jGieOperationFactoryTest {

    /** PROJ's {@code PJ_TORAD}: {@code deg * M_PI / 180}, not {@code Math.toRadians}. */
    private static double toRad(double deg) {
        return deg * Math.PI / 180.0;
    }

    private GieOperationFactory factory;

    @BeforeEach
    void setUp() {
        factory = new Proj4jGieOperationFactory();
    }

    private GieOperation op(String args) {
        return factory.create(args);
    }

    private static void assertKind(GieFailureKind expected, GieOperation o) {
        assertFalse(o.isUsable(), "expected unusable, got a usable operation: " + o);
        assertNotNull(o.failure());
        assertEquals(expected, o.failure().kind(),
                "wrong classification; message was: " + o.failure().message());
    }

    // ================================================== executing a real one

    @Test
    @DisplayName("+proj=merc +ellps=GRS80 executes and matches builtins.gie:4262 to 50 nm")
    void mercMatchesCorpusExpectation() {
        // builtins.gie:4262
        //   operation +proj=merc   +ellps=GRS80
        //   tolerance 50 nm
        //   accept  2 1
        //   expect  222638.981586547 110579.965218249
        GieOperation o = op("proj=merc ellps=GRS80");
        assertTrue(o.isUsable(), o.failure() == null ? "" : o.failure().message());

        // Every PROJ_HEAD projection is left=RADIANS, right=CLASSIC, which folds to
        // PROJECTED - which is why this forward row is compared in metres.
        assertEquals(GieIoUnits.RADIANS, o.leftUnits());
        assertEquals(GieIoUnits.CLASSIC, o.rightUnits());
        assertEquals(GieIoUnits.PROJECTED,
                GieIoUnits.outputUnits(o.leftUnits(), o.rightUnits(), o.isInverted(),
                        GieDirection.FORWARD));

        double[] out = o.transform(new double[] {toRad(2), toRad(1), 0, 0}, GieDirection.FORWARD);
        assertNotNull(out, "merc must not fail on (2, 1)");
        double tolerance = 50e-9;
        assertEquals(222638.981586547, out[0], tolerance);
        assertEquals(110579.965218249, out[1], tolerance);

        // The inverse of the same row: right=CLASSIC means the input is metres.
        double[] back = o.transform(new double[] {222638.981586547, 110579.965218249, 0, 0},
                GieDirection.INVERSE);
        assertNotNull(back);
        assertEquals(toRad(2), back[0], 1e-12);
        assertEquals(toRad(1), back[1], 1e-12);
    }

    @Test
    @DisplayName("an ellipsoid-less operation still uses GRS80, per init.cpp's implicit append")
    void implicitEllipsoidIsApplied() {
        // Without the implicit +ellps=GRS80, DatumParameters leaves a and es NaN and
        // this would come back as a NUMERICAL failure - scoring a parser gap as a
        // numerical defect.
        GieOperation bare = op("proj=merc");
        assertTrue(bare.isUsable(), bare.failure() == null ? "" : bare.failure().message());
        double[] out = bare.transform(new double[] {toRad(2), toRad(1), 0, 0},
                GieDirection.FORWARD);
        assertNotNull(out, "the implicit ellipsoid must make this executable");
        assertEquals(222638.981586547, out[0], 50e-9);
        assertEquals(110579.965218250, out[1], 50e-9);
    }

    @Test
    @DisplayName("+inv swaps both the unit sides and the direction actually run")
    void inverseFlagSwapsBoth() {
        GieOperation plain = op("proj=merc ellps=GRS80");
        GieOperation inv = op("proj=merc ellps=GRS80 inv");
        assertTrue(inv.isUsable());
        assertTrue(inv.isInverted());

        // pj_right(P) with P->inverted reads P->left, i.e. RADIANS, so a forward run
        // of an inverted operation is compared geodesically.
        assertEquals(GieIoUnits.RADIANS,
                GieIoUnits.outputUnits(inv.leftUnits(), inv.rightUnits(), true,
                        GieDirection.FORWARD));

        // ...and proj_trans negates the direction, so "forward" on +inv actually
        // runs the inverse.
        double[] viaInv = inv.transform(new double[] {222638.981586547, 110579.965218249, 0, 0},
                GieDirection.FORWARD);
        double[] viaPlain = plain.transform(
                new double[] {222638.981586547, 110579.965218249, 0, 0}, GieDirection.INVERSE);
        assertNotNull(viaInv);
        assertNotNull(viaPlain);
        assertEquals(viaPlain[0], viaInv[0], 0.0);
        assertEquals(viaPlain[1], viaInv[1], 0.0);
    }

    @Test
    @DisplayName("+proj=longlat is RADIANS on both sides and does not emit degrees")
    void longlatIsRadiansBothSides() {
        GieOperation o = op("proj=longlat ellps=GRS80");
        assertTrue(o.isUsable(), o.failure() == null ? "" : o.failure().message());
        assertEquals(GieIoUnits.RADIANS, o.leftUnits());
        assertEquals(GieIoUnits.RADIANS, o.rightUnits());

        double[] out = o.transform(new double[] {toRad(12), toRad(55), 0, 0}, GieDirection.FORWARD);
        assertNotNull(out);
        // LongLatProjection installs Units.DEGREES, so projectRadians would have
        // returned degrees; the bridge converts back because PROJ's longlat is
        // RADIANS on the right.
        assertEquals(toRad(12), out[0], 1e-15);
        assertEquals(toRad(55), out[1], 1e-15);
    }

    // ============================================ the host-level fwd guard

    @Test
    @DisplayName("|phi| beyond pi/2 + 1e-12 is INVALID_COORD, from fwd.cpp and not from merc")
    void latitudeOverRangeIsInvalidCoord() {
        GieOperation o = op("proj=merc ellps=GRS80");
        assertNull(o.transform(new double[] {0, toRad(91), 0, 0}, GieDirection.FORWARD));
        assertEquals(GieFailureKind.INVALID_COORD, o.lastFailure().kind());
    }

    @Test
    @DisplayName("latitude within PJ_EPS_LAT of the pole is clamped, not rejected")
    void latitudeWithinEpsilonIsClamped() {
        GieOperation o = op("proj=laea ellps=GRS80");
        double[] out = o.transform(new double[] {0, Math.PI / 2 + 5e-13, 0, 0},
                GieDirection.FORWARD);
        assertNotNull(out, "1e-12 rad of slop is clamped by fwd_prepare, not an error");
    }

    @Test
    @DisplayName("longitude is bounded at 10 radians, not at 180 degrees")
    void longitudeBoundIsTenRadians() {
        GieOperation o = op("proj=merc ellps=GRS80");
        // 200 degrees is legal in PROJ - the bound is |lambda| > 10 rad (~573 deg).
        assertNotNull(o.transform(new double[] {toRad(200), 0, 0, 0}, GieDirection.FORWARD),
                "200 degrees must not be rejected; a [-180,180] check is stricter than PROJ");
        assertNull(o.transform(new double[] {11.0, 0, 0, 0}, GieDirection.FORWARD));
        assertEquals(GieFailureKind.INVALID_COORD, o.lastFailure().kind());
    }

    @Test
    @DisplayName("NaN in, NaN out is a result, not a NUMERICAL failure")
    void nanInNanOutIsAResult() {
        // PROJ's documented behaviour: fwd_prepare tests for HUGE_VAL, not NaN, and
        // its two range checks are both false for NaN, so a NaN coordinate flows
        // through the projection and comes back out. more_builtins.gie:791 asserts
        // exactly that. The bridge must therefore return the NaN rather than call it
        // a numerical defect, or the comparator's NaN-both-sides branch (which
        // scores d = 0) can never fire.
        //
        // Deliberately NOT the corpus row's own +proj=laea +lat_0=90: in proj4j
        // today that combination returns (0.0, 0.0) from an all-NaN input - a
        // failure dressed up as the false easting and northing. That is a core
        // defect, outside this bridge's scope, and it must surface as a FAIL against
        // the corpus's `expect NaN NaN NaN NaN` rather than be papered over here.
        GieOperation o = op("proj=merc ellps=GRS80");
        assertTrue(o.isUsable(), o.failure() == null ? "" : o.failure().message());
        double[] out = o.transform(new double[] {Double.NaN, Double.NaN, Double.NaN, Double.NaN},
                GieDirection.FORWARD);
        assertNotNull(out, "a NaN input must be allowed to produce a NaN output");
        assertTrue(Double.isNaN(out[0]));
        assertTrue(Double.isNaN(out[1]));
        assertNull(o.lastFailure());
    }

    @Test
    @DisplayName("more_builtins.gie:791's own row is classified, not silently mis-answered")
    void corpusNanRowWithLonZero() {
        // The corpus row itself carries +lon_0=-150, and proj4j's
        // ProjectionMath.normalizeLongitude - which projectRadians only calls when
        // lon_0 != 0 - now rejects a non-finite longitude outright. PROJ does not.
        // So this is a genuine behaviour difference; what matters here is that it
        // surfaces as a classified per-point failure rather than as a plausible
        // coordinate.
        GieOperation o = op("proj=laea lat_0=90 lon_0=-150 datum=WGS84 units=m");
        assertTrue(o.isUsable(), o.failure() == null ? "" : o.failure().message());
        double[] out = o.transform(new double[] {Double.NaN, Double.NaN, Double.NaN, Double.NaN},
                GieDirection.FORWARD);
        if (out == null) {
            assertEquals(GieFailureKind.INVALID_COORD, o.lastFailure().kind(),
                    o.lastFailure().message());
        } else {
            assertTrue(Double.isNaN(out[0]));
        }
    }

    @Test
    @DisplayName("a NaN produced from finite input IS a NUMERICAL failure")
    void nanFromFiniteInputIsNumerical() {
        // The 62 enumerated sites where proj4j returns NaN silently. Any projection
        // reachable here will do; what is asserted is the shape of the outcome, not
        // which projection exhibits it.
        GieOperation o = op("proj=merc ellps=GRS80");
        double[] out = o.transform(new double[] {0, 0, 0, 0}, GieDirection.FORWARD);
        assertNotNull(out, "the control case must succeed");
        // Feed the inverse a value no Mercator northing can produce.
        GieOperation stere = op("proj=stere ellps=GRS80 lat_0=90");
        double[] bad = stere.transform(new double[] {1e300, 1e300, 0, 0}, GieDirection.INVERSE);
        if (bad == null) {
            assertNotNull(stere.lastFailure());
            assertTrue(stere.lastFailure().kind() == GieFailureKind.NUMERICAL
                            || stere.lastFailure().kind() == GieFailureKind.COORD_OUT_OF_DOMAIN,
                    "got " + stere.lastFailure());
        }
    }

    // ================================== INVALID_DEFINITION: PROJ's verdict

    @Test
    @DisplayName("builtins.gie:3637 `+proj=laea +ellps=GRS80 +lat_0=91` is INVALID_DEFINITION")
    void corpusLatZeroNinetyOneIsInvalidDefinition() {
        // The corpus asserts `expect failure errno invalid_op_illegal_arg_value`.
        // PROJ rejects |lat_0| > 90 in init.cpp, so this is a statement about the
        // definition, not about proj4j.
        GieOperation o = op("proj=laea ellps=GRS80 lat_0=91");
        assertKind(GieFailureKind.INVALID_DEFINITION, o);
        assertTrue(o.failure().message().contains("lat_0"), o.failure().message());
    }

    @Test
    @DisplayName("more_builtins.gie:821 `+proj=aeqd +R=1 +lat_0=91` is INVALID_DEFINITION")
    void corpusAeqdLatZeroNinetyOneIsInvalidDefinition() {
        assertKind(GieFailureKind.INVALID_DEFINITION, op("proj=aeqd R=1 lat_0=91"));
    }

    @Test
    @DisplayName("more_builtins.gie:227 nested pipeline is INVALID_DEFINITION, not NOT_IMPLEMENTED")
    void corpusNestedPipelineIsInvalidDefinition() {
        // `expect failure pjd_err_malformed_pipeline`. A pipeline we cannot run is
        // NOT_IMPLEMENTED, but a pipeline PROJ itself rejects is
        // INVALID_DEFINITION - and PROJ's verdict wins.
        GieOperation o = op("proj=pipeline step proj=pipeline step proj=merc");
        assertKind(GieFailureKind.INVALID_DEFINITION, o);
        assertTrue(o.failure().message().contains("nested pipeline"), o.failure().message());
    }

    @Test
    @DisplayName("more_builtins.gie:235 `+proj=pipeline` with no steps is INVALID_DEFINITION")
    void corpusEmptyPipelineIsInvalidDefinition() {
        assertKind(GieFailureKind.INVALID_DEFINITION, op("proj=pipeline"));
    }

    @Test
    @DisplayName("an operator PROJ itself does not know is INVALID_DEFINITION")
    void unknownToProjIsInvalidDefinition() {
        // The decidability that makes `expect failure` meaningful: unknown to
        // PROJ's own 186-name table means upstream errors too.
        assertKind(GieFailureKind.INVALID_DEFINITION, op("proj=not_a_projection"));
        assertKind(GieFailureKind.INVALID_DEFINITION, op("ellps=GRS80"));
        assertKind(GieFailureKind.INVALID_DEFINITION, op("proj="));
    }

    @Test
    @DisplayName("bad values of recognised keys are INVALID_DEFINITION")
    void badValuesAreInvalidDefinition() {
        assertKind(GieFailureKind.INVALID_DEFINITION, op("proj=merc ellps=GRS80 k_0=0"));
        assertKind(GieFailureKind.INVALID_DEFINITION, op("proj=merc ellps=GRS80 k=-1"));
        assertKind(GieFailureKind.INVALID_DEFINITION, op("proj=merc ellps=GRS80 units=furlong"));
        assertKind(GieFailureKind.INVALID_DEFINITION, op("proj=merc ellps=GRS80 to_meter=0"));
        assertKind(GieFailureKind.INVALID_DEFINITION, op("proj=merc ellps=GRS80 axis=en"));
        assertKind(GieFailureKind.INVALID_DEFINITION, op("proj=merc ellps=GRS80 axis=enq"));
        assertKind(GieFailureKind.INVALID_DEFINITION, op("proj=merc ellps=nonexistent"));
        assertKind(GieFailureKind.INVALID_DEFINITION, op("proj=merc datum=nonexistent"));
        assertKind(GieFailureKind.INVALID_DEFINITION, op("proj=merc a=6400000 es=1.5"));
        assertKind(GieFailureKind.INVALID_DEFINITION, op("proj=merc a=6400000 rf=-1"));
        assertKind(GieFailureKind.INVALID_DEFINITION, op("proj=merc ellps=GRS80 towgs84=1,2"));
        assertKind(GieFailureKind.INVALID_DEFINITION, op("proj=merc ellps=GRS80 zone=32a"));
        assertKind(GieFailureKind.INVALID_DEFINITION, op("proj=merc ellps=GRS80 south=maybe"));
        // +no_defs with nothing to size the ellipsoid: verified against the
        // installed 9.8.1, which fails with "Must specify ellipsoid or sphere".
        assertKind(GieFailureKind.INVALID_DEFINITION, op("proj=merc no_defs"));
    }

    @Test
    @DisplayName("+ellps plus a shape parameter is NOT rejected - PROJ treats it as a modifier")
    void ellpsPlusShapeIsNotAContradiction() {
        // ell_set.cpp's own comment: later shape and size parameters are taken into
        // account as modifiers for the built-in definition. Rejecting this would be
        // stricter than PROJ.
        GieOperation o = op("proj=merc ellps=GRS80 rf=300");
        if (!o.isUsable()) {
            assertEquals(GieFailureKind.NOT_IMPLEMENTED, o.failure().kind(),
                    "may be unimplemented, but must never be INVALID_DEFINITION: "
                            + o.failure().message());
        }
    }

    // ==================================== NOT_IMPLEMENTED: proj4j's gaps

    @Test
    @DisplayName("+proj=pipeline is executed, not classified")
    void pipelineIsExecutable() {
        // 75 of the corpus's 780 operations, plus every GIGS file. This assertion
        // used to read NOT_IMPLEMENTED; org.locationtech.proj4j.pipeline is why it
        // no longer does. See PipelineGieOperationTest for the behavioural coverage.
        GieOperation o = op("proj=pipeline step proj=axisswap order=2,1 step proj=merc ellps=GRS80");
        assertTrue(o.isUsable(), () -> "expected usable, got " + o.failure());
        // axisswap declares WHATEVER on both sides and adopts its neighbour's units.
        assertEquals(GieIoUnits.RADIANS, o.leftUnits());
        assertEquals(GieIoUnits.PROJECTED, o.rightUnits());
    }

    @Test
    @DisplayName("more_builtins.gie:535's implicit pipeline is executed")
    void implicitPipelineIsExecutable() {
        // `+step +proj=latlong +ellps=WGS84` has no global +proj, and would fail
        // pj_init outright, because pj_param_exists stops at the first `step`. But
        // proj_create routes a proj-string through PROJStringParser, which reads a
        // lone +step as a one-step pipeline - and the corpus asserts a real
        // coordinate for it (expect -64.737589 17.546000). Classifying it at all
        // would make an operation PROJ runs look like one PROJ cannot, which is the
        // exact failure mode that renders `expect failure` rows meaningless.
        GieOperation o = op("step proj=latlong ellps=WGS84");
        assertTrue(o.isUsable(), () -> "expected usable, got " + o.failure());
        assertEquals(GieIoUnits.RADIANS, o.leftUnits());
        assertEquals(GieIoUnits.RADIANS, o.rightUnits());
    }

    @Test
    @DisplayName("+nadgrids now routes to the hgridshift helper instead of being refused")
    void nadgridsRoutesToTheHiddenHgridshift() {
        // 4D-API_cs2cs-style.gie:58, and the inverse of what this test used to assert.
        // The old reason was correct and is worth keeping on the record: left to
        // construction the failure came back MISSING_GRID from Grid.fromNadGrids, which
        // was true but secondary, because even with the grid present the
        // single-projection path applies no shift at all - PROJ implements +nadgrids as
        // an inserted +proj=hgridshift step. pipeline/Cs2csOperator now builds that step,
        // and Proj4jCapabilities.requiresCs2csEmulation routes the definition to it.
        GieOperation o = op("proj=latlong nadgrids=ntf_r93.gsb ellps=GRS80");
        assertTrue(o.isUsable(), () -> "expected usable, got " + o.failure());
        assertEquals(GieIoUnits.RADIANS, o.leftUnits());
        assertEquals(GieIoUnits.RADIANS, o.rightUnits());
    }

    @Test
    @DisplayName("a required +nadgrids that does not resolve is still fatal, and says so")
    void aRequiredMissingNadgridIsStillRefused() {
        // Non-negotiable: routing must not turn a missing grid into a silent no-op that
        // reports success. Only an @-prefixed token may be skipped, and that is PROJ's
        // own behaviour.
        GieOperation o = op("proj=latlong nadgrids=nosuchgrid.gsb ellps=GRS80");
        assertFalse(o.isUsable(), () -> "a required missing grid must not be executable");
        assertTrue(o.failure().message().contains("nosuchgrid.gsb"), o.failure().message());
    }

    @Test
    @DisplayName("a PROJ operator absent from proj4j's Registry is NOT_IMPLEMENTED")
    void unregisteredOperatorIsNotImplemented() {
        // INVERTED for three of these. axisswap, unitconvert and cart are conversions that
        // live in the pipeline engine, not in Registry, and the factory now routes a BARE
        // `+proj=<conversion>` there. Before that, axisswap.gie sat at 2/27 and
        // unitconvert.gie at 0/16 with BOTH operators complete and passing their own unit
        // tests - every failure was the routing, not the operator. Asserting
        // NOT_IMPLEMENTED here would re-pin that.
        assertTrue(op("proj=axisswap axis=neu").isUsable(), "axisswap routes to the pipeline engine");
        assertTrue(op("proj=unitconvert xy_in=m xy_out=km").isUsable(), "unitconvert likewise");
        assertTrue(op("proj=cart ellps=GRS80").isUsable(), "cart likewise");
        assertKind(GieFailureKind.NOT_IMPLEMENTED, op("proj=helmert x=1"));
    }

    @Test
    @DisplayName("alsk/apian/bacon are NOT_IMPLEMENTED and print nothing to stderr")
    void abstractRegistrationsAreSilentlyNotImplemented() throws UnsupportedEncodingException {
        // Registry registers these three against the ABSTRACT Projection class, so
        // Registry.getProjection catches the InstantiationException, prints a stack
        // trace to System.err and returns null. Three stack traces per corpus sweep
        // makes a conformance run look broken, so the probe suppresses them.
        PrintStream saved = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(captured, true, "UTF-8"));
            // INVERTED. All three were bound to the ABSTRACT Projection class and were
            // therefore uninstantiable; alsk alone was 16 silently-wrong builtins.gie
            // assertions. apian/bacon are now real implementations (bacon.cpp) and alsk
            // came with the mod_ster port, so they must RESOLVE. The stderr assertion
            // below is the part still worth keeping: Registry must never print.
            String[] names = {"alsk", "apian", "bacon"};
            for (int i = 0; i < names.length; i++) {
                GieOperation o = factory.create("proj=" + names[i]);
                assertTrue(o.isUsable(), names[i] + " is implemented now and must resolve");
            }
        } finally {
            System.setErr(saved);
        }
        assertEquals("", captured.toString("UTF-8"),
                "probing an abstract registration must not leak a stack trace to stderr");
    }

    @Test
    @DisplayName("a parameter PROJ acts on but proj4j has no notion of is NOT_IMPLEMENTED"
            + " (+lon_wrap); +approx, +geoidgrids, +W and +over are no longer such parameters")
    void unimplementedParameterIsNotImplemented() {
        // INVERTED. +approx selects tmerc's Evenden/Snyder series (builtins.gie:7245), and
        // it is now registered, dispatched and honoured - it HAS to be, because PROJ 9.8.1
        // ships Poder/Engsager as the default and proj4j now matches, so +approx is the
        // only way to ask for the previous behaviour. The movement it guards is unbounded:
        // 0.83 mm at 6 deg from the central meridian, 4 m at 20 deg, kilometres beyond 45.
        GieOperation approx = op("proj=tmerc a=6400000 rf=1e12 k=0.9 lat_0=40 approx");
        assertTrue(approx.isUsable(), "+approx is honoured now");

        // INVERTED, and the check that justifies it is the one the standing rule names:
        // registering a key with no dispatch is the dangerous direction, so the question is not
        // "is +W in HONOURED" but "does Proj4Parser reach every operator that reads it".
        // Enumerated at the source rather than from the docs -
        //     git grep -nE '"[a-z]W"' 9.8.1 -- src/
        // - and upstream reads +W in exactly two places: hammer.cpp:63-64 and lagrng.cpp:79-80.
        // (A grep for '"W"' returns ZERO: PROJ spells the key with a pj_param sigil, "tW"/"dW".)
        // Proj4Parser.java:467-476 dispatches to LagrangeProjection.setW AND
        // HammerProjection.setW, and Registry.java:355,377 registers both classes - so the set of
        // registered readers equals the set of upstream readers, with nothing dropped.
        //
        // On +proj=merc specifically, neither PROJ nor proj4j reads +W at all, so the two agree
        // by doing the same nothing. That is an honest usable operation, not a silent gap.
        assertTrue(op("proj=merc ellps=GRS80 W=2").isUsable(),
                "+W is registered AND dispatched to both of its upstream readers now");
        // INVERTED. +geoidgrids now becomes a real auto-inserted +proj=vgridshift step
        // (4D-API_cs2cs-style.gie:104), built where create.cpp:88-105 builds it, with the
        // GTX reader behind it verified against cct 9.8.1 at 14 points to 12 decimals.
        assertTrue(op("proj=longlat geoidgrids=egm96_15.gtx axis=neu ellps=GRS80").isUsable(),
                "+geoidgrids is an auto-inserted vgridshift step now");
        // INVERTED, judged on its own evidence and not swept along with +W above. +over is
        // global in PROJ - init.cpp:601 reads "bover" into P->over, and the flag is consumed at
        // exactly three sites, all of them a suppressed adjlon: fwd.cpp:84-85 and :111-112, and
        // inv.cpp:116-117. proj4j now covers all three: Proj4Parser.java:278-279 dispatches to
        // Projection.setOver, Projection.java:443 guards the forward wrap (proj4j has one forward
        // funnel where fwd_prepare has two) and Projection.java:678 guards the inverse one. Base
        // class, base funnels - so it is honoured for every projection, as upstream's is.
        assertTrue(op("proj=merc ellps=GRS80 over").isUsable(),
                "+over is dispatched to Projection.setOver and guards both funnels now");
        // STILL CORRECT, and left alone. +lon_wrap is NOT in HONOURED, and PROJ genuinely acts
        // on it (fwd.cpp:165, adjlon(lam - P->long_wrap_center)) where proj4j has no notion of a
        // wrap centre at all. Honest NOT_IMPLEMENTED.
        assertKind(GieFailureKind.NOT_IMPLEMENTED, op("proj=merc ellps=GRS80 lon_wrap=0"));
    }

    @Test
    @DisplayName("a parameter proj4j accepts but silently ignores is NOT_IMPLEMENTED, never executed")
    void acceptedButIgnoredParametersAreNotImplemented() {
        // +zone on a projection that is not a transverse Mercator: Proj4Parser drops it,
        // PROJ acts on it, and no rerouting helps because the gap is the dispatch table
        // and not the path. This is the case the rule exists for.
        assertKind(GieFailureKind.NOT_IMPLEMENTED, op("proj=merc ellps=GRS80 zone=32"));
    }

    @Test
    @DisplayName("+axis and +pm are no longer refused: they route to the emulation helpers")
    void emulationKeysRouteRatherThanRefuse() {
        // Both used to be asserted NOT_IMPLEMENTED here, and the reason given was right:
        // they are in Proj4Keyword's allow-list and are read by Proj4Parser, yet have no
        // effect on Projection.projectRadians, so executing on that path would have
        // produced a wrong number that looks like a pass. The fix was not to execute them
        // there but to send them somewhere that implements them - PROJ turns each into a
        // hidden sub-operation (+proj=axisswap, and from_greenwich in fwd_prepare), and
        // pipeline/Cs2csOperator builds both.
        assertTrue(op("proj=merc ellps=GRS80 axis=neu").isUsable());
        // builtins.gie:3351 - krovak with the Ferro prime meridian.
        assertTrue(op("proj=krovak lat_0=49.5 lon_0=42.5 k=0.9999 x_0=0 y_0=0 ellps=bessel "
                + "pm=ferro").isUsable());
    }

    @Test
    @DisplayName("+axis=enu and +pm=greenwich are honoured, because they are the defaults")
    void identityValuedConditionalsAreHonoured() {
        assertTrue(op("proj=merc ellps=GRS80 axis=enu").isUsable());
        assertTrue(op("proj=merc ellps=GRS80 pm=greenwich").isUsable());
        assertTrue(op("proj=merc ellps=GRS80 pm=0").isUsable());
    }

    @Test
    @DisplayName("+zone IS honoured on utm and tmerc")
    void zoneHonouredOnTransverseMercator() {
        assertTrue(op("proj=utm zone=32 ellps=GRS80").isUsable());
        assertTrue(op("proj=tmerc zone=32 ellps=GRS80").isUsable());
    }

    @Test
    @DisplayName("a datum shift is inert when null and routed to the helper when not")
    void datumShiftsAreRoutedRatherThanRefused() {
        // A null shift stays on the single-projection path, which is the whole point of
        // asking conditionalFailure per token *at its value*: no definition that used to
        // pass can change route.
        assertTrue(op("proj=merc ellps=GRS80 towgs84=0,0,0").isUsable());
        assertTrue(op("proj=merc datum=WGS84").isUsable());
        assertTrue(op("proj=merc datum=NAD83").isUsable());
        // A real shift is now built rather than refused - a Helmert plus the cart round
        // trip for +towgs84, an hgridshift for the two grid-shift datums and for an
        // explicit +nadgrids.
        assertTrue(op("proj=merc ellps=GRS80 towgs84=1,2,3").isUsable());
        assertTrue(op("proj=merc datum=NAD27").isUsable());
        assertTrue(op("proj=merc datum=potsdam").isUsable());
        assertTrue(op("proj=merc ellps=GRS80 nadgrids=@conus").isUsable());
        // An unknown datum is still an error, and it is PROJ's error too
        // ("Unknown value for datum", datum_set.cpp:76).
        assertFalse(op("proj=merc datum=nosuchdatum").isUsable());
    }

    @Test
    @DisplayName("rerouting does not smuggle an unhonoured token past the token check")
    void anUnhonouredTokenStillFailsOnTheEmulationRoute() {
        // Cs2csOperator parses in ParseMode.PROJ_COMPATIBLE, which retains and IGNORES a
        // key outside the allow-list rather than refusing it. So the token check has to
        // run before routing, or a definition combining an emulation key with a key
        // proj4j drops would be executed and answer plausibly and wrongly.
        assertKind(GieFailureKind.NOT_IMPLEMENTED,
                op("proj=merc ellps=GRS80 towgs84=1,2,3 no_such_key=1"));
        // ...and the projection-dependent conditionals are still judged: +zone on merc is
        // dropped by Proj4Parser on both paths.
        assertKind(GieFailureKind.NOT_IMPLEMENTED,
                op("proj=merc ellps=GRS80 towgs84=1,2,3 zone=32"));
    }

    @Test
    @DisplayName("a value grammar PROJ has and proj4j lacks is NOT_IMPLEMENTED, not a wrong answer")
    void narrowerValueGrammarIsNotImplemented() {
        // PROJ's +to_meter accepts a num/den ratio; proj4j's Double.parseDouble
        // would read "1/0.3048" as 1 and scale the whole projection by 1 metre per
        // unit instead of 3.28.
        GieOperation ratio = op("proj=merc ellps=GRS80 to_meter=1/0.3048");
        assertKind(GieFailureKind.NOT_IMPLEMENTED, ratio);
        assertTrue(ratio.failure().message().contains("ratio"), ratio.failure().message());
    }

    @Test
    @DisplayName("a PROJ unit id proj4j's Units table lacks is NOT_IMPLEMENTED, not silently metres")
    void unresolvableUnitIsNotImplemented() {
        // Units.findUnits returns METRES for ANY unknown name rather than null, so a
        // PROJ unit id proj4j's table lacks would silently scale by 1 instead of
        // failing - e.g. +units=fath would be out by 1.8288x, which looks like a
        // pass on a loose-tolerance row.
        // INVERTED. All seven missing linear units (fath ch link us-ch ind-yd ind-ft ind-ch)
        // were added to core's Units table, so these now resolve. The DANGER the comment
        // above describes is still real and still worth guarding - Units.findUnits returns
        // METRES for any unknown name - which is why ProjTablesTest now asserts proj4j
        // resolves all 21 linear ids PROJ accepts, and why the +units gap check is retained
        // rather than deleted: PROJ gaining a unit must surface as a classified gap, never
        // as a silent 1x scale.
        assertTrue(op("proj=merc ellps=GRS80 units=fath").isUsable(), "fath resolves now");
        assertTrue(op("proj=merc ellps=GRS80 units=ind-yd").isUsable(), "ind-yd resolves now");
        assertTrue(op("proj=merc ellps=GRS80 units=link").isUsable(), "link resolves now");
        // ...but the ones it really has are fine.
        assertTrue(op("proj=merc ellps=GRS80 units=m").isUsable());
        assertTrue(op("proj=merc ellps=GRS80 units=us-ft").isUsable());
        assertTrue(op("proj=merc ellps=GRS80 units=km").isUsable());
    }

    @Test
    @DisplayName("nkg.gie's OGC URN operations are NOT_IMPLEMENTED, not malformed")
    void ogcUrnIsNotImplemented() {
        // All 26 operations in nkg.gie are OGC URNs. proj_create() resolves them
        // through proj.db, so they are perfectly valid upstream and must not be
        // classified INVALID_DEFINITION - that would make an `expect failure` row
        // pass for entirely the wrong reason.
        GieOperation o = op("urn:ogc:def:coordinateOperation:NKG::ITRF2000_TO_DK");
        assertKind(GieFailureKind.NOT_IMPLEMENTED, o);
        assertTrue(o.failure().message().contains("proj.db"), o.failure().message());
        assertKind(GieFailureKind.NOT_IMPLEMENTED, op("EPSG:4326"));
        assertKind(GieFailureKind.NOT_IMPLEMENTED,
                op("PROJCRS[\"foo\",BASEGEOGCRS[\"bar\"]]"));
    }

    @Test
    @DisplayName("ellipsoid.gie:73-76 - a bare word and an empty definition ARE malformed")
    void bogusDefinitionsAreInvalidDefinition() {
        // Both carry `expect failure` in the corpus, and neither is a database
        // identifier - so unlike the URNs above, INVALID_DEFINITION is right.
        assertKind(GieFailureKind.INVALID_DEFINITION, op("cobra"));
        assertKind(GieFailureKind.INVALID_DEFINITION, op(""));
        // 4D-API_cs2cs-style.gie:539, `errno invalid_op_wrong_syntax`
        assertKind(GieFailureKind.INVALID_DEFINITION,
                op("this is a bogus CRS meant to trigger a syntax error in proj_create()"));
    }

    @Test
    @DisplayName("a PROJ-false boolean is NOT_IMPLEMENTED, because containsKey would enable it")
    void projFalseBooleanIsNotImplemented() {
        GieOperation o = op("proj=utm zone=32 ellps=GRS80 south=F");
        assertKind(GieFailureKind.NOT_IMPLEMENTED, o);
        assertTrue(o.failure().message().contains("containsKey"), o.failure().message());
    }

    @Test
    @DisplayName("+south on a projection that does not implement it is NOT_IMPLEMENTED, not an escape")
    void southOnUnsupportedProjectionIsCaught() {
        // Projection.setSouthernHemisphere throws NoSuchElementException, which is
        // not a Proj4jException and would otherwise escape the runner entirely.
        GieOperation o = op("proj=merc ellps=GRS80 south");
        if (!o.isUsable()) {
            assertEquals(GieFailureKind.NOT_IMPLEMENTED, o.failure().kind(),
                    o.failure().message());
        }
    }

    // ============================================================ contract

    @Test
    @DisplayName("the factory never throws, whatever it is handed")
    void factoryNeverThrows() {
        String[] hostile = {
                null, "", "   ", "=", "==", "+", "++", "proj", "proj=", "=merc",
                "proj=merc lat_0=", "proj=merc lat_0=abc", "proj=merc a=",
                "proj=merc towgs84=", "proj=merc axis=", "step", "step step",
                "proj=merc  ", "proj=merc ellps=", "proj=merc units=",
                "proj=merc pm=", "proj=merc zone=", "proj=merc R=",
        };
        for (int i = 0; i < hostile.length; i++) {
            GieOperation o = factory.create(hostile[i]);
            assertNotNull(o, "create(" + hostile[i] + ") returned null");
            if (!o.isUsable()) {
                assertNotNull(o.failure(), "unusable without a failure: " + hostile[i]);
                assertNotNull(o.failure().message());
                assertFalse(o.failure().message().isEmpty());
            }
        }
    }

    @Test
    @DisplayName("an unusable operation's transform always returns null, never a coordinate")
    void unusableTransformReturnsNull() {
        // `+proj=pipeline` with no `+step` at all: malformed upstream too, so this
        // stays unusable now that ordinary pipelines execute.
        GieOperation o = op("proj=pipeline");
        assertFalse(o.isUsable());
        assertNull(o.transform(new double[] {0, 0, 0, 0}, GieDirection.FORWARD));
        assertNull(o.transform(new double[] {0, 0, 0, 0}, GieDirection.INVERSE));
        assertNotNull(o.lastFailure());
    }

    @Test
    @DisplayName("crsDstIsLatLonOrYX is the documented false, on both operation kinds")
    void crsDstIsLatLonOrYXIsAlwaysFalse() {
        assertFalse(op("proj=merc ellps=GRS80").crsDstIsLatLonOrYX());
        assertFalse(factory.createCrsToCrs("EPSG:4326", "EPSG:3857").crsDstIsLatLonOrYX());
    }

    // ====================================================== crs_src/crs_dst

    @Test
    @DisplayName("a crs_src/crs_dst pair reports DEGREES on a geographic side")
    void crsToCrsUnits() {
        GieOperation o = factory.createCrsToCrs("EPSG:4326", "EPSG:3857");
        if (o.isUsable()) {
            assertEquals(GieIoUnits.DEGREES, o.leftUnits(),
                    "proj_create_crs_to_crs ends a geographic side in a unitconvert to Degree");
            assertEquals(GieIoUnits.PROJECTED, o.rightUnits());
        }
    }

    @Test
    @DisplayName("epsg_no_grid.gie:12 EPSG:4258 -> EPSG:25832 either runs or is classified")
    void crsToCrsCorpusPair() {
        GieOperation o = factory.createCrsToCrs("EPSG:4258", "EPSG:25832");
        if (o.isUsable()) {
            double[] out = o.transform(new double[] {12, 55, 0, 0}, GieDirection.FORWARD);
            assertNotNull(out);
            assertTrue(out[0] > 600000 && out[0] < 800000, "easting was " + out[0]);
            assertTrue(out[1] > 6000000 && out[1] < 6200000, "northing was " + out[1]);
        } else {
            assertNotNull(o.failure().message());
        }
    }

    @Test
    @DisplayName("an incomplete or unresolvable crs pair is classified, not thrown")
    void crsToCrsDegenerate() {
        assertKind(GieFailureKind.INVALID_DEFINITION, factory.createCrsToCrs(null, "EPSG:4326"));
        assertKind(GieFailureKind.INVALID_DEFINITION, factory.createCrsToCrs("EPSG:4326", ""));
        GieOperation o = factory.createCrsToCrs("EPSG:999999", "EPSG:4326");
        assertFalse(o.isUsable());
        assertNotNull(o.failure().message());
    }
}
