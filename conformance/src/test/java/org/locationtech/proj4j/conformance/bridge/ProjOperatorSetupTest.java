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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The oracle transcript for {@link ProjOperatorSetup}.
 *
 * <p>Every row below was run through the installed {@code proj 9.8.1} binary
 * (Rel. 9.8.1, April 10th 2026 — the same rev the corpus is vendored from) as
 * {@code echo "0 0" | proj <definition>}, and the {@code REJECT}/{@code ACCEPT}
 * column is what it printed. The test asserts that
 * {@link ProjDefinitionValidator#validate} agrees.
 *
 * <p><b>The ACCEPT rows are the point.</b> A validator that returned
 * {@code INVALID_DEFINITION} for everything would satisfy every REJECT row and be
 * worthless — worse than worthless, since {@code INVALID_DEFINITION} is the only
 * verdict that can manufacture a false pass. So each guard is paired with a
 * near-miss that must still be accepted: {@code +rf=1} rejects but
 * {@code +rf=1.0000001} accepts; {@code lagrng +W=0} rejects but a bare
 * {@code lagrng} accepts because {@code +W} defaults to 2; {@code helmert +drx=1}
 * rejects but {@code +rx=0} accepts.
 *
 * <p>{@link #theOracleTranscriptExercisesBothVerdicts()} enforces that balance so
 * the transcript cannot decay into a one-sided list.
 */
class ProjOperatorSetupTest {

    /** One oracle observation: a definition and whether {@code proj 9.8.1} refused it. */
    private static final class Row {
        final String def;
        final boolean rejected;
        final String note;

        Row(String def, boolean rejected, String note) {
            this.def = def;
            this.rejected = rejected;
            this.note = note;
        }
    }

    private static Row reject(String def, String note) {
        return new Row(def, true, note);
    }

    private static Row accept(String def, String note) {
        return new Row(def, false, note);
    }

    /**
     * Definitions whose verdict this validator is expected to reproduce.
     *
     * <p>Deliberately excluded: definitions PROJ rejects for a reason
     * {@link ProjOperatorSetup} does not model (the {@code lcc}/{@code eqdc}
     * eccentricity guards, which need {@code pj_msfn}/{@code pj_mlfn}). Those are
     * listed in {@link #UNMODELLED} instead, so the gap is on the record rather than
     * silently absent.
     */
    private static final Row[] ORACLE = {
            // ---- ell_set.cpp / pj_calc_ellipsoid_params: f must be in [0,1)
            reject("proj=merc a=1 f=1", "f=1 is not < 1"),
            reject("proj=merc a=1 f=2", "f=2 is not < 1"),
            reject("proj=utm zone=32 ellps=GRS80 f=1", "the +f modifier still bounds f"),
            reject("proj=merc a=1 rf=1", "rf=1 gives f=1"),
            reject("proj=merc a=1 rf=0.5", "rf<1 gives f>1"),
            reject("proj=merc a=1 b=2", "b>a gives a negative flattening"),
            accept("proj=merc a=1 f=0.5", "f in range"),
            accept("proj=merc a=1 rf=1.0000001", "just inside: f<1"),
            accept("proj=merc a=1 rf=300", "ordinary"),
            accept("proj=merc a=1 b=1", "b==a is a sphere"),
            accept("proj=merc a=1 b=0.5", "b<a"),
            accept("proj=merc R=1 f=2", "+R short-circuits every shape parameter"),

            // ---- lcc
            reject("proj=lcc ellps=GRS80 lat_1=0 lat_2=90", "|lat_2| >= 90"),
            reject("proj=lcc ellps=GRS80 lat_1=90 lat_2=0", "|lat_1| >= 90"),
            reject("proj=lcc ellps=GRS80 lat_1=90 lat_2=90", "lat_1 guard wins"),
            reject("proj=lcc ellps=sphere lat_1=91", "lat_2 defaults to lat_1"),
            reject("proj=lcc ellps=sphere lat_2=91", "|lat_2| >= 90"),
            reject("proj=lcc ellps=GRS80 lat_1=1 lat_2=-1", "|lat_1+lat_2| == 0"),
            reject("proj=lcc ellps=GRS80", "both parallels default to 0"),
            accept("proj=lcc ellps=GRS80 lat_1=30 lat_2=45", "ordinary secant"),
            accept("proj=lcc ellps=GRS80 lat_1=0.5 lat_2=2", "corpus row builtins.gie:3833"),

            // ---- aea / leac / eqdc
            reject("proj=aea R=6400000 lat_1=1 lat_2=-1", "|lat_1+lat_2| == 0"),
            reject("proj=aea ellps=GRS80 lat_1=900", "|lat_1| > 90"),
            reject("proj=aea ellps=GRS80 lat_2=900", "|lat_2| > 90"),
            reject("proj=aea", "both parallels default to 0"),
            accept("proj=aea ellps=GRS80 lat_1=0 lat_2=2", "sum is 2 degrees"),
            reject("proj=eqdc R=6400000 lat_1=1 lat_2=-1", "|lat_1+lat_2| == 0"),
            reject("proj=eqdc R=6400000 lat_1=91", "|lat_1| > 90"),
            reject("proj=eqdc R=6400000 lat_2=91", "|lat_2| > 90"),
            reject("proj=eqdc R=1 lat_1=1e-9", "1e-9 degrees is 1.7e-11 rad, under EPS10"),
            accept("proj=eqdc ellps=GRS80 lat_1=0.5 lat_2=2", "ordinary"),
            accept("proj=leac ellps=GRS80 lat_1=0 lat_2=2", "leac takes phi1 from the pole"),
            accept("proj=leac R=6400000 lat_1=0 lat_2=2", "and ignores lat_2"),

            // ---- omerc
            reject("proj=omerc R=1 alpha=0 lat_0=90", "|lat_0| >= 90 in the alpha branch"),
            reject("proj=omerc lat_1=91", "|lat_1| > 90 - TOL"),
            reject("proj=omerc lat_2=91", "|lat_2| > 90 - TOL"),
            reject("proj=omerc", "lat_1 == lat_2 == 0"),
            reject("proj=omerc ellps=GRS80 lat_1=0 lat_2=2", "lat_1 must differ from 0"),
            reject("proj=omerc ellps=GRS80 lat_1=1 lat_2=1", "lat_1 must differ from lat_2"),
            reject("proj=omerc ellps=GRS80 lat_1=30 lat_2=40 lat_0=90", "|lat_0| >= 90"),
            accept("proj=omerc R=1 alpha=0 lat_0=45", "alpha branch, lat_0 in range"),
            accept("proj=omerc R=1 lat_0=1 lat_1=2 no_rot", "corpus row builtins.gie:5269"),
            accept("proj=omerc a=6400000 lat_0=45 lat_1=45 lat_2=45.00001 lon_1=0 lon_2=1e-5",
                    "|lat_1-lat_2| is 1.7e-7 rad, just OVER TOL=1e-7"),
            accept("proj=omerc ellps=GRS80 lat_1=0.5 lat_2=2", "corpus row builtins.gie:5223"),

            // ---- omerc: the +gamma limit, which is where D matters
            accept("proj=omerc lat_0=10 R=6400000 gamma=80",
                    "exactly at the spherical limit; aasin's ONE_TOL slack must absorb it"),
            reject("proj=omerc lat_0=10 R=6400000 gamma=80.0000001", "1e-7 degrees over"),
            reject("proj=omerc lat_0=10 R=6400000 rf=300 gamma=80.01",
                    "+R makes it spherical, so the limit is still 80 - the corpus's "
                            + "'# OK' comment on builtins.gie:5335 is wrong and unasserted"),
            reject("proj=omerc lat_0=10 a=6400000 rf=300 gamma=80.1",
                    "ellipsoidal limit is 80.031684"),
            accept("proj=omerc lat_0=10 a=6400000 rf=300 gamma=80.01",
                    "under the ellipsoidal limit, unlike the +R form above"),

            // ---- lagrng / krovak / labrd
            reject("proj=lagrng R=1 W=-1", "W must be > 0"),
            reject("proj=lagrng R=1 W=0", "W must be > 0"),
            reject("proj=lagrng R=1 lat_1=90.00001", "|sin(lat_1)| within TOL of 1"),
            accept("proj=lagrng R=1 W=0.5", "W in range"),
            accept("proj=lagrng R=1", "+W defaults to 2, so it is not required"),
            accept("proj=lagrng R=1 lat_1=89", "well inside"),
            reject("proj=krovak lat_0=-90", "tan(lat_0/2 + pi/4) == 0"),
            reject("proj=mod_krovak lat_0=-90", "shares krovak_setup"),
            accept("proj=krovak", "+lat_0 defaults to 49d30'N, not to 0"),
            accept("proj=krovak lat_0=49.5", "the default, written out"),
            reject("proj=labrd ellps=GRS80 lat_0=0", "lat_0 must differ from 0"),
            reject("proj=labrd ellps=GRS80", "+lat_0 defaults to 0"),
            accept("proj=labrd ellps=GRS80 lat_0=-18.9", "Madagascar"),

            // ---- nsper / tpers
            reject("proj=nsper R=1 h=0", "pn1 = h/a must be > 0"),
            reject("proj=nsper R=1 h=-5", "negative height"),
            reject("proj=nsper R=1", "+h defaults to 0"),
            reject("proj=nsper R=1 h=1e11", "pn1 > 1e10"),
            accept("proj=nsper R=1 h=1e10", "exactly at the upper bound"),
            accept("proj=nsper R=1 h=10", "ordinary"),
            reject("proj=tpers R=1 h=0", "tpers shares nsper_setup"),
            accept("proj=tpers R=1 h=10", "ordinary"),

            // ---- urm5 / s2 / isea
            reject("proj=urm5 a=6400000", "+n is required"),
            reject("proj=urm5 a=6400000 n=0", "n must be in ]0,1]"),
            reject("proj=urm5 a=6400000 n=1.5", "n must be in ]0,1]"),
            reject("proj=urm5 a=6400000 n=1 alpha=90", "n*sin(alpha) == 1"),
            accept("proj=urm5 a=6400000 n=0.5", "ordinary"),
            reject("proj=s2 ellps=WGS84 lat_0=0 lon_0=0 UVtoST=invalid", "not in the map"),
            accept("proj=s2 ellps=WGS84 lat_0=0 lon_0=0 UVtoST=linear", "in the map"),
            accept("proj=s2 ellps=WGS84 lat_0=0 lon_0=0", "defaults to quadratic"),
            reject("proj=isea mode=nope", "no corpus row reaches this guard"),
            reject("proj=isea orient=nope", "nor this one"),
            accept("proj=isea mode=hex", "corpus row builtins.gie:3152 - legal at setup"),
            accept("proj=isea orient=pole", "legal"),

            // ---- ob_tran
            reject("proj=ob_tran R=6400000", "+o_proj missing"),
            reject("proj=ob_tran R=6400000 o_proj=ob_tran", "recursion guard"),
            reject("proj=ob_tran R=6400000 o_proj",
                    "bare +o_proj passes the null check, then names nothing to build"),
            reject("proj=ob_tran R=6400000 o_proj=", "same, written with an empty value"),
            reject("proj=ob_tran R=6400000 o_proj=nosuchthing", "unknown target operator"),
            reject("proj=ob_tran R=6400000 o_proj=pipeline", "a pipeline with no steps"),
            accept("proj=ob_tran R=6400000 o_proj=moll o_lat_p=45 o_lon_p=0", "ordinary"),

            // ---- topocentric
            reject("proj=topocentric ellps=WGS84", "neither X_0 nor lon_0"),
            reject("proj=topocentric ellps=WGS84 X_0=0 Y_0=0", "Z_0 missing"),
            reject("proj=topocentric ellps=WGS84 lon_0=0", "lat_0 missing"),
            reject("proj=topocentric ellps=WGS84 X_0=0 lon_0=0", "mutually exclusive"),
            accept("proj=topocentric ellps=WGS84 X_0=0 Y_0=0 Z_0=0", "geocentric origin"),
            accept("proj=topocentric ellps=WGS84 lon_0=0 lat_0=0", "geographic origin"),

            // ---- helmert / molobadekas / molodensky
            reject("proj=helmert rx=1", "rotation without a convention"),
            reject("proj=helmert drx=1", "a rate of rotation counts too"),
            reject("proj=helmert rx=1 convention=foo", "not a known convention"),
            reject("proj=helmert rx=1 convention=1", "nor this"),
            reject("proj=helmert towgs84=1,2,3,4,5,6,7 convention=coordinate_frame",
                    "towgs84 is position_vector by history"),
            reject("proj=helmert transpose", "the obsolete flag is a hard error"),
            accept("proj=helmert towgs84=1,2,3,4,5,6,7 convention=position_vector", "legal"),
            accept("proj=helmert rx=1 convention=position_vector", "legal"),
            accept("proj=helmert rx=0", "a zero rotation is no rotation"),
            accept("proj=helmert x=1", "translation only"),
            accept("proj=helmert", "the identity is legal"),
            reject("proj=molobadekas", "convention is unconditionally required here"),
            accept("proj=molobadekas convention=position_vector", "legal"),
            reject("proj=molodensky a=6378160 rf=298.25", "dx missing"),
            reject("proj=molodensky a=6378160 rf=298.25 dx=0", "dy missing"),
            accept("proj=molodensky a=6378160 rf=298.25 dx=0 dy=0 dz=0 da=0 df=0", "complete"),

            // ---- defmodel / gridshift
            reject("proj=defmodel", "+model= required"),
            reject("proj=gridshift", "+grids required"),

            // ---- ups / utm / sterea
            reject("proj=ups a=6400000", "no spherical formulation"),
            accept("proj=ups ellps=GRS80", "ellipsoidal"),
            reject("proj=utm a=6400000 zone=30", "eccentricity must not be zero"),
            reject("proj=utm R=6400000 zone=30", "same, via +R"),
            accept("proj=utm ellps=GRS80 zone=30", "ordinary"),
            reject("proj=sterea a=9999 b=.9 lat_0=73", "pj_gauss_ini srat underflows"),
            accept("proj=sterea a=9999 b=.9 lat_0=0", "sin(lat_0)=0 makes srat 1"),
            accept("proj=sterea ellps=GRS80 lat_0=52", "ordinary"),
    };

    /**
     * Definitions {@code proj 9.8.1} rejects that {@link ProjOperatorSetup}
     * deliberately does <em>not</em>, because the guard needs {@code pj_msfn},
     * {@code pj_tsfn} or {@code pj_mlfn}. Asserting they come back valid pins the
     * boundary: if someone ports those guards, this list must shrink in the same
     * commit, and if the validator starts rejecting them by accident, this catches it.
     */
    private static final String[] UNMODELLED = {
            // Need pj_msfn / pj_tsfn / pj_mlfn to evaluate an `n == 0` secant-cone
            // degeneracy at an eccentricity indistinguishable from 1.
            "proj=lcc a=9999999 b=.9 lat_2=1",
            "proj=eqdc a=9999999 b=.9 lat_2=1",
            "proj=eqdc lat_1=1 ellps=GRS80 b=.1",
            "proj=omerc lat_1=0.8 a=6400000 b=.4",
            // A repeated +o_proj: ob_tran_target_params rewrites every occurrence and
            // the resulting failure is not the one the rewrite loop reads as though it
            // should be. proj 9.8.1 rejects this with omerc's lat_1/lat_2 message,
            // which is not predictable from ob_tran.cpp alone.
            "proj=ob_tran R=6400000 o_proj=moll o_proj=ob_tran",
    };

    @Test
    @DisplayName("the validator reproduces proj 9.8.1's verdict on every probed definition")
    void validatorAgreesWithTheOracle() {
        List<String> wrong = new ArrayList<String>();
        for (Row r : ORACLE) {
            GieFailure f = ProjDefinitionValidator.validate(GieProjArgs.parse(r.def));
            boolean saysInvalid = f != null;
            if (saysInvalid != r.rejected) {
                wrong.add(String.format("%-70s oracle=%s validator=%s   (%s)%s",
                        r.def, r.rejected ? "REJECT" : "ACCEPT",
                        saysInvalid ? "REJECT" : "ACCEPT", r.note,
                        f == null ? "" : "\n        " + f.message()));
            }
        }
        assertTrue(wrong.isEmpty(),
                "the validator disagrees with proj 9.8.1 on " + wrong.size() + " of "
                        + ORACLE.length + " probed definitions:\n  "
                        + String.join("\n  ", wrong));
    }

    @Test
    @DisplayName("the transcript asserts both verdicts, so it cannot be satisfied by a stub")
    void theOracleTranscriptExercisesBothVerdicts() {
        int rejects = 0;
        int accepts = 0;
        for (Row r : ORACLE) {
            if (r.rejected) {
                rejects++;
            } else {
                accepts++;
            }
        }
        assertTrue(accepts >= 30, "only " + accepts + " ACCEPT rows; a validator that "
                + "refused everything would pass a REJECT-only transcript");
        assertTrue(rejects >= 30, "only " + rejects + " REJECT rows");
        assertEquals(ORACLE.length, rejects + accepts);
    }

    @Test
    @DisplayName("guards needing pj_msfn/pj_tsfn/pj_mlfn are honestly reported as not modelled")
    void unmodelledGuardsStayValid() {
        for (String def : UNMODELLED) {
            GieFailure f = ProjDefinitionValidator.validate(GieProjArgs.parse(def));
            assertEquals(null, f, def + " is now classified INVALID_DEFINITION. proj 9.8.1 "
                    + "does reject it, but on an eccentricity guard this class does not "
                    + "model - so either the guard was ported (update this list) or the "
                    + "rejection is coming from somewhere it should not.");
        }
    }
}
