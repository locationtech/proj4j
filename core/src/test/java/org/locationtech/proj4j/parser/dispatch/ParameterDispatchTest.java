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

package org.locationtech.proj4j.parser.dispatch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.InvalidValueException;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.Proj4jException;
import org.locationtech.proj4j.Registry;
import org.locationtech.proj4j.UnsupportedParameterException;
import org.locationtech.proj4j.parser.Proj4Keyword;
import org.locationtech.proj4j.proj.ObliqueMercatorProjection;
import org.locationtech.proj4j.parser.Proj4Parser;
import org.locationtech.proj4j.proj.ColombiaUrbanProjection;
import org.locationtech.proj4j.proj.GeneralSinusoidalProjection;
import org.locationtech.proj4j.proj.MisrSpaceObliqueMercatorProjection;
import org.locationtech.proj4j.proj.ObliqueTransformationProjection;
import org.locationtech.proj4j.proj.Projection;
import org.locationtech.proj4j.proj.SpaceObliqueMercatorProjection;
import org.locationtech.proj4j.proj.Urmaev5Projection;

/**
 * The regression net for the <b>silent parameter loss</b> defect.
 *
 * <h2>What is being proved, and why a "does it parse" test would prove nothing</h2>
 *
 * <p>{@code ParseMode.PROJ_COMPATIBLE} retains-and-ignores unrecognised keys, which is exactly
 * what PROJ does — {@code init.cpp} keeps every token and recognition is pull-based, so an
 * unknown {@code +key} is never an error. The consequence is that a definition which the parser
 * does not dispatch <em>parses cleanly and projects</em>, from the operator's defaults. So
 * "the definition was accepted" is worthless as evidence, and so is "the projection was
 * constructed": both were already true before the dispatch existed.
 *
 * <p>The evidence that dispatch happened is therefore always the same shape: <b>removing the
 * key, or changing its value, must change the observable outcome.</b> Every test below is that
 * assertion. Several of the keys had a defaulted value that was not merely wrong but plausible —
 * {@code som}'s three orbital parameters all default to {@code 0}, and {@code 0} passes all
 * three of upstream's own range checks, yielding the coordinates of a satellite in a
 * zero-inclination zero-period orbit.
 *
 * @see DispatchedCorpusAgreementTest for agreement with PROJ's expected values
 */
public class ParameterDispatchTest {

    // ------------------------------------------------------------------ +proj=som

    private static final String SOM =
            "+proj=som +ellps=GRS80 +inc_angle=98.30382 +ps_rev=0.06866666666666667 "
                    + "+asc_lon=127.7605356226";

    /**
     * Each of {@code som}'s three orbital parameters changes the answer, so each of them is
     * genuinely read.
     *
     * <p>This is the test that would have caught the original defect. Before the dispatch
     * landed, all four of {@code builtins.gie}'s {@code som} blocks reduced to the same
     * degenerate orbit, so the "with" and "without" outcomes were identical — and identical
     * to each other.
     */
    @Test
    public void somOrbitalParametersEachChangeTheAnswer() {
        String full = outcome(SOM, 2, 1);
        assertNotEquals("+inc_angle was dropped: removing it did not change the answer",
                full, outcome(without(SOM, "inc_angle"), 2, 1));
        assertNotEquals("+ps_rev was dropped: removing it did not change the answer",
                full, outcome(without(SOM, "ps_rev"), 2, 1));
        assertNotEquals("+asc_lon was dropped: removing it did not change the answer",
                full, outcome(without(SOM, "asc_lon"), 2, 1));
    }

    /**
     * The three values land on the fields, in radians, and are not merely consumed.
     * {@code +inc_angle} and {@code +asc_lon} are read with {@code pj_param}'s {@code r} sigil,
     * i.e. through {@code dmstor}, so the parser converts degrees to radians on the way in.
     */
    @Test
    public void somOrbitalParametersReachTheFields() {
        SpaceObliqueMercatorProjection som = (SpaceObliqueMercatorProjection) projection(SOM);
        assertEquals(Math.toRadians(98.30382), som.getIncidenceAngle(), 1e-15);
        assertEquals(0.06866666666666667, som.getPeriodOfRevolution(), 0.0);
        assertEquals(Math.toRadians(127.7605356226), som.getProjectionLongitude(), 1e-15);
    }

    /**
     * The angular value grammar, on the two {@code r}-sigil keys. All four forms are the same
     * angle, so all four must project identically — and {@code Double.parseDouble} accepts only
     * the first of them, which is why {@code +alpha}, {@code +lonc}, {@code +gamma} and
     * {@code +pm} using it is a defect and not a style choice.
     *
     * <p>{@code 98.30382} degrees is {@code 98d18'13.752"}; the corpus itself supplies the
     * radian form, {@code 1.7157253262878522r}.
     */
    @Test
    public void somAnglesAcceptEveryDmstorForm() {
        // A trailing cardinal is a pure sign operation on the same decimal, so this one has to
        // agree bit for bit.
        assertEquals("a trailing cardinal is not reaching +asc_lon", outcome(SOM, 2, 1),
                outcome("+proj=som +ellps=GRS80 +inc_angle=98.30382 "
                        + "+ps_rev=0.06866666666666667 +asc_lon=127.7605356226E", 2, 1));

        // Degrees-and-minutes, where the conversion is exact in binary: 98d30' is 98.5 and
        // 127d45' is 127.75.
        assertEquals("degree/minute DMS is not reaching +inc_angle/+asc_lon",
                outcome("+proj=som +ellps=GRS80 +inc_angle=98.5 "
                        + "+ps_rev=0.06866666666666667 +asc_lon=127.75", 2, 1),
                outcome("+proj=som +ellps=GRS80 +inc_angle=98d30' "
                        + "+ps_rev=0.06866666666666667 +asc_lon=127d45'", 2, 1));

        // Arcseconds, and the corpus's own radian spelling. Neither is the same *real number*
        // as the decimal-degree spelling -- 1.7157253262878522 rad rounds to
        // 98.30381999999... deg -- so the claim is agreement to the precision the corpus
        // writes them and no further. Upstream has the same difference and gives both of its
        // som pairs byte-identical expected values, which is exactly the 0.1 mm bar used here.
        assertProjectionsAgree("full DMS with d/'/\" is not reaching +inc_angle/+asc_lon",
                SOM, "+proj=som +ellps=GRS80 +inc_angle=98d18'13.752\" "
                        + "+ps_rev=0.06866666666666667 +asc_lon=127d45'37.92824136\"",
                2, 1, 1e-4);
        assertProjectionsAgree("the r/R radian suffix is not reaching +inc_angle/+asc_lon",
                SOM, "+proj=som +ellps=GRS80 +inc_angle=1.7157253262878522r "
                        + "+ps_rev=0.06866666666666667 +asc_lon=2.2298420007209447r",
                2, 1, 1e-4);
    }

    /**
     * The guard that {@code SpaceObliqueMercatorProjection.initialize()} used to carry is
     * <b>gone</b>, and this pins that it is gone rather than merely unreached.
     *
     * <p>It refused a {@code som} whose three orbital parameters had never been set, because
     * verbatim 9.8.1 behaviour plus the parser gap would have answered a fully-specified
     * definition from a degenerate orbit. With the keys dispatched the gap is closed, so the
     * divergence is removed and a bare {@code +proj=som} now behaves as 9.8.1 does: all three
     * default to {@code 0}, all three pass upstream's range checks, and it projects.
     */
    @Test
    public void bareSomNoLongerRefuses() {
        Projection p = projection("+proj=som +ellps=GRS80");
        SpaceObliqueMercatorProjection som = (SpaceObliqueMercatorProjection) p;
        assertEquals("upstream defaults +inc_angle to 0", 0.0, som.getIncidenceAngle(), 0.0);
        assertEquals("upstream defaults +ps_rev to 0", 0.0, som.getPeriodOfRevolution(), 0.0);
    }

    /** Upstream's own three range checks are still in force, on the dispatched values. */
    @Test
    public void somRangeChecksStillApply() {
        assertRejects("+proj=som +ellps=GRS80 +inc_angle=181 +ps_rev=0.1 +asc_lon=0",
                "inclination");
        assertRejects("+proj=som +ellps=GRS80 +inc_angle=98 +ps_rev=-1 +asc_lon=0", "days");
        assertRejects("+proj=som +ellps=GRS80 +inc_angle=98 +ps_rev=0.1 +asc_lon=400r",
                "ascending longitude");
    }

    // -------------------------------------------------------------- +proj=misrsom

    /** {@code +path} reaches {@code setPath}, and a different path is a different projection. */
    @Test
    public void misrsomPathChangesTheAnswer() {
        assertNotEquals("+path was dropped: path 1 and path 2 gave the same answer",
                outcome("+proj=misrsom +ellps=GRS80 +path=1", 2, 1),
                outcome("+proj=misrsom +ellps=GRS80 +path=2", 2, 1));
        MisrSpaceObliqueMercatorProjection misr = (MisrSpaceObliqueMercatorProjection)
                projection("+proj=misrsom +ellps=GRS80 +path=42");
        assertEquals(42, misr.getPath());
    }

    /**
     * {@code misrsom}'s refusal when {@code +path} is absent is <b>upstream's own</b>
     * ({@code som.cpp:288}, {@code path <= 0} is an error) and therefore stays, unlike
     * {@code som}'s parser-gap guard which is now deleted.
     */
    @Test
    public void misrsomStillRequiresPath() {
        assertRejects("+proj=misrsom +ellps=GRS80", "path");
    }

    /**
     * {@code pj_param}'s {@code i} sigil, exactly: {@code param.cpp:180-187} runs {@code atoi}
     * and then rejects the value if <em>any</em> character lies outside {@code 0-9}. So a sign,
     * a decimal point, surrounding whitespace and trailing text are all errors rather than
     * partial parses. {@code Integer.parseInt} accepts {@code -5} and trims whitespace, which
     * is why {@code +path} does not use it.
     */
    @Test
    public void pathIsStrictlyDigits() {
        assertEquals(12, ((MisrSpaceObliqueMercatorProjection)
                projection("+proj=misrsom +ellps=GRS80 +path=12")).getPath());
        String[] rejected = {"12a", "-5", "+5", "1.0", "1e2", "", "0x1f", "١٢"};
        for (int i = 0; i < rejected.length; i++) {
            assertRejects("+proj=misrsom +ellps=GRS80 +path=" + rejected[i], "path");
        }
        // Surrounding whitespace, which cannot be expressed in a space-separated definition
        // string. Integer.parseInt trims; pj_param's 'i' does not, because its loop tests every
        // character of the value.
        assertRejectsArgs(new String[] {"+proj=misrsom", "+ellps=GRS80", "+path= 12"}, "path");
        assertRejectsArgs(new String[] {"+proj=misrsom", "+ellps=GRS80", "+path=12 "}, "path");
    }

    // --------------------------------------------------------------- +proj=gn_sinu

    private static final String GN_SINU = "+proj=gn_sinu +a=6400000 +m=1 +n=2";

    /**
     * {@code gn_sinu}'s {@code +m} and {@code +n} are both required and both undocumented
     * ({@code gn_sinu.cpp:180-198}). {@code +m} had no keyword at all; {@code +n} had one but
     * was dispatched only to {@code urmfps}.
     */
    @Test
    public void generalSinusoidalMAndNChangeTheAnswer() {
        assertNotEquals("+m was dropped", outcome(GN_SINU, 2, 1),
                outcome("+proj=gn_sinu +a=6400000 +m=3 +n=2", 2, 1));
        assertNotEquals("+n was dropped", outcome(GN_SINU, 2, 1),
                outcome("+proj=gn_sinu +a=6400000 +m=1 +n=3", 2, 1));
        GeneralSinusoidalProjection p = (GeneralSinusoidalProjection) projection(GN_SINU);
        assertEquals(1.0, p.getM(), 0.0);
        assertEquals(2.0, p.getN(), 0.0);
    }

    /** Upstream: absent {@code n} is "Missing parameter n.", {@code n <= 0} is illegal. */
    @Test
    public void generalSinusoidalStillValidates() {
        assertRejects("+proj=gn_sinu +a=6400000 +m=1", "n");
        assertRejects("+proj=gn_sinu +a=6400000 +m=-1 +n=2", "m");
    }

    /**
     * {@code +m} and {@code +n} reach {@code gn_sinu} and <b>not</b> its siblings.
     * {@code sinu}, {@code eck6} and {@code mbtfps} share {@code gn_sinu.cpp}'s kernel — and,
     * here, the same package-private base class — but hard-code their own {@code m} and
     * {@code n} and read neither key upstream. Dispatching on the base class would silently
     * reshape all three.
     */
    @Test
    public void generalSinusoidalSiblingsIgnoreMAndN() {
        assertEquals("+m/+n must not reach +proj=mbtfps",
                outcome("+proj=mbtfps +a=6400000", 2, 1),
                outcome("+proj=mbtfps +a=6400000 +m=3 +n=0.25", 2, 1));
        assertEquals("+m/+n must not reach +proj=sinu",
                outcome("+proj=sinu +a=6400000", 2, 1),
                outcome("+proj=sinu +a=6400000 +m=3 +n=0.25", 2, 1));
    }

    // ----------------------------------------------------------------- +proj=urm5

    /** {@code urm5}'s {@code +n} (required) and {@code +q} (optional, default 0). */
    @Test
    public void urmaev5NAndQChangeTheAnswer() {
        String base = "+proj=urm5 +a=6400000 +n=0.5";
        assertNotEquals("+n was dropped", outcome(base, 2, 1),
                outcome("+proj=urm5 +a=6400000 +n=0.9", 2, 1));
        assertNotEquals("+q was dropped: the default is 0, so any non-zero q must move y",
                outcome(base, 2, 1), outcome(base + " +q=3", 2, 1));
        Urmaev5Projection p = (Urmaev5Projection) projection(base + " +q=3");
        assertEquals(0.5, p.getN(), 0.0);
        assertEquals(3.0, p.getQ(), 0.0);
    }

    /** {@code urm5} without {@code +n} is "Missing parameter n." upstream. */
    @Test
    public void urmaev5StillRequiresN() {
        assertRejects("+proj=urm5 +a=6400000", "n");
        assertRejects("+proj=urm5 +a=6400000 +n=1.5", "n");
    }

    /**
     * {@code builtins.gie}'s second {@code urm5} block, {@code +n=1 +alpha=90}: upstream
     * rejects it because {@code n * sin(alpha) == 1} makes {@code sqrt(1 - t*t)} zero
     * ({@code urm5.cpp:51-58}). Reachable only now that {@code +n} is dispatched — with
     * {@code n} defaulted the definition failed for the wrong reason.
     */
    @Test
    public void urm5RejectsNSinAlphaEqualToOne() {
        assertRejects("+proj=urm5 +a=6400000 +n=1 +alpha=90", "alpha");
    }

    // ------------------------------------------------------------ +proj=col_urban

    /**
     * {@code +h_0}, {@code col_urban}'s only mandatory parameter beyond the ellipsoid
     * ({@code col_urban.cpp:65}). It is always metres and is never scaled by {@code +units}.
     */
    @Test
    public void colombiaUrbanH0ChangesTheAnswer() {
        String base = "+proj=col_urban +lat_0=4.68048611111111 +lon_0=-74.1465916666667 "
                + "+x_0=92334.879 +y_0=109320.965 +ellps=GRS80";
        assertNotEquals("+h_0 was dropped: the default is 0, so 2550 m must move the answer",
                outcome(base, -74.25, 4.8), outcome(base + " +h_0=2550", -74.25, 4.8));
        assertEquals(2550.0,
                ((ColombiaUrbanProjection) projection(base + " +h_0=2550")).getH0(), 0.0);
    }

    // -------------------------------------------------------------- +proj=ob_tran

    private static final String OB_TRAN =
            "+proj=ob_tran +R=6400000 +o_proj=latlon +o_lon_p=20 +o_lat_p=20 +lon_0=180";

    /**
     * The whole point of {@code ob_tran}: {@code +o_proj} names a <em>child</em> projection,
     * and the child is built from the {@code ob_tran} argument list with {@code o_proj=xxx}
     * rewritten to {@code proj=xxx} by advancing the token pointer two characters
     * ({@code ob_tran.cpp:159}). This is why {@code parse(String, String[])} has to hand the
     * raw array down to {@code parseProjection}: there is no per-value dispatch that could
     * express it.
     */
    @Test
    public void obTranBuildsItsChildFromTheArgumentList() {
        ObliqueTransformationProjection p =
                (ObliqueTransformationProjection) projection(OB_TRAN);
        assertTrue("+o_proj did not reach setParameters", p.getChild() != null);
        assertEquals("latlon", p.getChild().getName());
        assertNotEquals("+o_proj=moll and +o_proj=latlon must not agree",
                outcome(OB_TRAN, 2, 1),
                outcome(OB_TRAN.replace("o_proj=latlon", "o_proj=moll"), 2, 1));
    }

    /** Each pole parameter is read, so each one moves the answer. */
    @Test
    public void obTranPoleParametersChangeTheAnswer() {
        String full = outcome(OB_TRAN, 2, 1);
        assertNotEquals("+o_lat_p was dropped", full,
                outcome(OB_TRAN.replace("o_lat_p=20", "o_lat_p=40"), 2, 1));
        assertNotEquals("+o_lon_p was dropped", full,
                outcome(OB_TRAN.replace("o_lon_p=20", "o_lon_p=40"), 2, 1));
    }

    /**
     * The three pole specifications are chosen by <b>presence</b> and never by value
     * ({@code pj_param}'s {@code t} sigil), in the fixed order {@code o_alpha}, then
     * {@code o_lat_p}, then the two-point form. So {@code +o_alpha=0} selects the azimuth
     * branch even though its value is zero.
     */
    @Test
    public void obTranPoleFormsAreChosenByPresence() {
        String withAlpha = OB_TRAN + " +o_alpha=0 +o_lon_c=10 +o_lat_c=30";
        assertNotEquals("+o_alpha=0 must select the azimuth form, not be ignored as falsy",
                outcome(OB_TRAN, 2, 1), outcome(withAlpha, 2, 1));
        // The two-point form, reached only when neither o_alpha nor o_lat_p is present.
        String twoPoint = "+proj=ob_tran +R=6400000 +o_proj=latlon +o_lon_1=0 +o_lat_1=30 "
                + "+o_lon_2=90 +o_lat_2=60";
        assertNotEquals("+o_lat_1 was dropped from the two-point form",
                outcome(twoPoint, 2, 1),
                outcome(twoPoint.replace("o_lat_1=30", "o_lat_1=50"), 2, 1));
    }

    /**
     * {@code builtins.gie:5072}, {@code +o_proj +o_proj=ob_tran}: upstream blocks the recursion
     * by string comparison <em>after</em> the rewrite and reports it as a missing argument, not
     * an illegal one. Note the bare {@code +o_proj} is not the token that matches —
     * {@code strncmp("o_proj", "o_proj=", 7)} compares a NUL against {@code '='} — yet it does
     * satisfy the earlier "is o_proj present" test, because the {@code s} sigil yields the
     * empty string rather than null for a valueless token.
     */
    @Test
    public void obTranRefusesToRotateItself() {
        assertRejects("+proj=ob_tran +R=6400000 +o_proj +o_proj=ob_tran", "o_proj");
    }

    /** No {@code +o_proj} at all is upstream's "Missing parameter: o_proj". */
    @Test
    public void obTranRequiresOProj() {
        assertRejects("+proj=ob_tran +R=6400000 +o_lat_p=20 +o_lon_p=20", "o_proj");
    }

    // ------------------------------------------------------- registration bookkeeping

    /**
     * Every key this change dispatches is in the allow-list, so STRICT mode accepts the
     * definitions PROJ accepts.
     *
     * <p><b>Both halves matter and they must move together.</b> A key registered but not
     * dispatched is retained-and-ignored, which is the silent-loss defect. A key dispatched but
     * not registered is worse in a different way: the conformance bridge's {@code toProj4Args()}
     * filters the definition through {@code Proj4Keyword.isSupported}, so the token would be
     * deleted from the array before the parser ever saw it.
     */
    @Test
    public void everyDispatchedKeyIsRegistered() {
        String[] keys = {
            Proj4Keyword.inc_angle, Proj4Keyword.ps_rev, Proj4Keyword.asc_lon,
            Proj4Keyword.path, Proj4Keyword.m, Proj4Keyword.n, Proj4Keyword.q,
            Proj4Keyword.h_0,
        };
        for (int i = 0; i < keys.length; i++) {
            assertTrue("+" + keys[i] + " is dispatched but not registered, so a filtering "
                    + "caller would strip it before the parser saw it",
                    Proj4Keyword.isSupported(keys[i]));
        }
        for (int i = 0; i < Proj4Keyword.OB_TRAN_PARAMS.length; i++) {
            assertTrue("+" + Proj4Keyword.OB_TRAN_PARAMS[i] + " is not registered",
                    Proj4Keyword.isSupported(Proj4Keyword.OB_TRAN_PARAMS[i]));
        }
    }

    /**
     * The six vertical-axis keys are registered and nothing more.
     *
     * <p>They need no dispatch here: the vertical stack reads them off {@code ProjParams} in
     * the pipeline layer. Registration does exactly one thing — it stops STRICT mode rejecting
     * a definition PROJ accepts — and this test pins that, by constructing in STRICT mode,
     * which is the only mode in which the allow-list is enforced at all.
     */
    @Test
    public void verticalKeysAreAcceptedInStrictMode() {
        String def = "+proj=merc +ellps=GRS80 +geoidgrids=egm96_15.gtx +vunits=m "
                + "+vto_meter=1 +z_0=0 +geoid_crs=WGS84 +multiplier=1";
        CoordinateReferenceSystem crs =
                new Proj4Parser(new Registry(), Proj4Parser.ParseMode.STRICT)
                        .parse("vertical", def.split("\\s+"));
        assertTrue(crs != null && crs.getProjection() != null);
        // And they are inert on the horizontal projection, as they are in pj_init.
        assertEquals(outcome("+proj=merc +ellps=GRS80", 2, 1), outcome(def, 2, 1));
    }

    /**
     * {@code +no_rot} is <b>deliberately not registered</b>, and this test is the record of
     * why, so that the omission cannot be mistaken for an oversight.
     *
     * <p>{@code omerc}'s "do not rotate the (u,v) frame" switch ({@code omerc.cpp:145}) needs a
     * setter that {@code ObliqueMercatorProjection} does not have: {@code rot} is a private
     * field assigned {@code true} unconditionally in that class's {@code initialize()}. Adding
     * the key without the dispatch is the dangerous direction — the conformance bridge would
     * then classify {@code builtins.gie:5246} and {@code :5269} as executable and they would
     * return a <em>rotated</em> answer where PROJ returns an unrotated one, which is a plausible
     * wrong number rather than an honest {@code NOT_IMPLEMENTED}.
     *
     * <p>When that setter lands, register the key here and in the bridge's {@code HONOURED} in
     * the same change, and delete this test.
     */
    @Test
    public void noRotIsNowRegisteredAndDispatched() {
        // INVERTED. This used to assert +no_rot must NOT be registered, and that was correct at
        // the time: ObliqueMercatorProjection.rot was a private field written unconditionally
        // inside initialize(), which runs TWICE - once from the constructor, once from the parser -
        // so anything a setter was told was discarded on the second pass. Registering the key while
        // it was inert would have made the conformance bridge call builtins.gie:5246 and :5269
        // executable, and they would have returned a ROTATED answer where PROJ returns an
        // unrotated one: a plausible wrong number reported as a pass.
        //
        // setNoRot now exists, `rot` is a field initialiser, initialize() no longer writes it, and
        // the key was added to Proj4Keyword.supportedParameters(), Proj4Parser's dispatch and the
        // bridge's HONOURED set in ONE change. Asserting the old state would re-pin a fixed defect.
        assertTrue("+no_rot must be registered now that setNoRot exists and the parser dispatches it",
                Proj4Keyword.isSupported(Proj4Keyword.no_rot));
    }

    @Test
    public void noRotSurvivesTheSecondInitialize() {
        // The regression net for WHY this could not be registered earlier: initialize() runs twice,
        // so a parameter stored in a field that initialize() also writes is silently discarded.
        ObliqueMercatorProjection p = new ObliqueMercatorProjection();
        p.setNoRot(true);
        p.initialize();
        p.initialize();
        assertTrue("setNoRot must survive a second initialize(); if this fails, `rot` has been "
                + "moved back inside initialize() and +no_rot is inert again", !p.isRot());
    }

    // -------------------------------------------------------------------- helpers

    private static Projection projection(String definition) {
        CoordinateReferenceSystem crs =
                new CRSFactory().createFromParameters("dispatch", definition);
        return crs.getProjection();
    }

    /**
     * The observable outcome of projecting one point: either the coordinate, or the name and
     * message of the exception.
     *
     * <p>Both are folded into a single string on purpose. The question every test here asks is
     * "did removing this key change anything?", and the answer is yes whether the change is a
     * different number or the difference between projecting and refusing. Comparing coordinates
     * alone would make a test throw where it means to fail with a diagnosis.
     */
    private static String outcome(String definition, double lon, double lat) {
        try {
            Projection p = projection(definition);
            ProjCoordinate out = new ProjCoordinate();
            p.project(new ProjCoordinate(lon, lat), out);
            return out.x + " " + out.y;
        } catch (RuntimeException e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    /** The same definition with one {@code +key=value} token removed. */
    private static String without(String definition, String key) {
        StringBuilder sb = new StringBuilder();
        String[] tokens = definition.split("\\s+");
        for (int i = 0; i < tokens.length; i++) {
            String bare = tokens[i].startsWith("+") ? tokens[i].substring(1) : tokens[i];
            if (bare.equals(key) || bare.startsWith(key + "=")) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(tokens[i]);
        }
        String result = sb.toString();
        if (result.equals(definition)) {
            throw new IllegalArgumentException("+" + key + " is not in " + definition);
        }
        return result;
    }

    /**
     * Asserts that a definition is refused, with {@code expectedInMessage} named in the reason.
     *
     * <p>The message check is what stops this passing for the wrong reason: several of these
     * definitions would also have been refused before the dispatch landed, just with a
     * different diagnosis.
     */
    private static void assertRejects(String definition, String expectedInMessage) {
        try {
            Projection p = projection(definition);
            ProjCoordinate out = new ProjCoordinate();
            p.project(new ProjCoordinate(2, 1), out);
            fail("expected " + definition + " to be refused, but it projected (2, 1) to "
                    + out.x + " " + out.y);
        } catch (InvalidValueException e) {
            assertMentions(definition, e, expectedInMessage);
        } catch (UnsupportedParameterException e) {
            assertMentions(definition, e, expectedInMessage);
        } catch (Proj4jException e) {
            assertMentions(definition, e, expectedInMessage);
        }
    }

    /** {@link #assertRejects} for a definition that cannot survive whitespace splitting. */
    private static void assertRejectsArgs(String[] args, String expectedInMessage) {
        String joined = String.join("|", args);
        try {
            CoordinateReferenceSystem crs =
                    new CRSFactory().createFromParameters("dispatch", args);
            ProjCoordinate out = new ProjCoordinate();
            crs.getProjection().project(new ProjCoordinate(2, 1), out);
            fail("expected " + joined + " to be refused, but it projected (2, 1) to "
                    + out.x + " " + out.y);
        } catch (Proj4jException e) {
            assertMentions(joined, e, expectedInMessage);
        }
    }

    /**
     * Asserts that two definitions project the same point to within {@code toleranceMetres}.
     * Used where the two definitions are the same angle only to the precision the corpus
     * writes it, so bit equality would be asserting something untrue.
     */
    private static void assertProjectionsAgree(String message, String a, String b,
            double lon, double lat, double toleranceMetres) {
        ProjCoordinate pa = new ProjCoordinate();
        ProjCoordinate pb = new ProjCoordinate();
        projection(a).project(new ProjCoordinate(lon, lat), pa);
        projection(b).project(new ProjCoordinate(lon, lat), pb);
        double deviation = Math.hypot(pa.x - pb.x, pa.y - pb.y);
        assertTrue(message + " -- " + a + " gave " + pa.x + " " + pa.y + " but " + b + " gave "
                        + pb.x + " " + pb.y + ", a deviation of " + deviation + " m",
                deviation <= toleranceMetres);
    }

    private static void assertMentions(String definition, RuntimeException e, String expected) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        assertTrue(definition + " was refused, but for an unexpected reason -- \"" + expected
                        + "\" does not appear in: " + e.getClass().getSimpleName() + ": "
                        + message,
                message.toLowerCase().contains(expected.toLowerCase()));
    }
}
