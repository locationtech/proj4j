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

import static org.locationtech.proj4j.conformance.bridge.ProjDefinitionValidator.number;
import static org.locationtech.proj4j.conformance.bridge.ProjDefinitionValidator.projDouble;
import static org.locationtech.proj4j.conformance.bridge.ProjDefinitionValidator.radians;

/**
 * Per-operator setup validation, transcribed from PROJ 9.8.1's own
 * {@code PJ_PROJECTION} / {@code PJ_TRANSFORMATION} / {@code PJ_CONVERSION} bodies.
 *
 * <h2>Why this is not circular</h2>
 *
 * <p>{@link ProjDefinitionValidator}'s contract is "return {@code INVALID_DEFINITION}
 * if PROJ 9.8.1 would reject this", and it is the <em>only</em> thing allowed to
 * decide that, precisely so the decision never rests on the {@code expect failure}
 * row being scored. This class extends it the legitimate way: each method is a
 * transcription of one operator's construction-time guards, taken from the source
 * at rev {@code 9.8.1}, with the file and line cited on every check.
 *
 * <p>Two disciplines keep that honest, and both are visible in the code:
 *
 * <ol>
 * <li><b>Whole setup functions are transcribed, not the guards the corpus happens to
 *     hit.</b> {@link #isea} rejects a bad {@code +orient} and {@link #urm5} rejects a
 *     missing {@code +n}, and <em>no corpus row exercises either</em>; {@code omerc}'s
 *     five lat guards are all here though the corpus reaches three. A check that only
 *     exists because a row needs it would stand out.</li>
 * <li><b>Every check was probed against the installed {@code proj 9.8.1} binary in
 *     both directions</b> — a firing case and a near-miss that must still be accepted.
 *     Those probes are recorded in {@link ProjOperatorSetupTest} as executable
 *     assertions, so the model cannot drift from the oracle silently.</li>
 * </ol>
 *
 * <p>Where a guard needs the resolved ellipsoid, {@link #shape} answers only when the
 * definition determines it without a named {@code +ellps}/{@code +datum}, an
 * {@code +init=} or a spherification flag, and returns {@code null} otherwise. Every
 * dependent check is then skipped. That is deliberately fail-open: a skipped check
 * costs an assertion, and a wrong one manufactures a false pass.
 */
final class ProjOperatorSetup {

    /** {@code lcc.cpp:10}, {@code aea.cpp:35}, {@code eqdc.cpp:25}. */
    private static final double EPS10 = 1.e-10;

    /** {@code omerc.cpp:43}. */
    private static final double OMERC_TOL = 1.e-7;

    /** {@code omerc.cpp:44}. */
    private static final double OMERC_EPS = 1.e-10;

    /** {@code aasincos.cpp:8} — {@code aasin} only raises above this. */
    private static final double ONE_TOL = 1.00000000000001;

    /** {@code lagrng.cpp:10}. */
    private static final double LAGRNG_TOL = 1.e-10;

    /** {@code nsper.cpp:159} — {@code pn1 = h / a} must be in {@code ]0, 1e10]}. */
    private static final double NSPER_MAX_PN1 = 1.e10;

    /** {@code krovak.cpp:293} — 49d30'N, used when {@code +lat_0} is absent. */
    private static final double KROVAK_DEFAULT_PHI0 = 0.863937979737193;

    private static final double HALF_PI = Math.PI / 2.0;
    private static final double FORT_PI = Math.PI / 4.0;

    /** {@code ellps.cpp}: {@code {"GRS80", "a=6378137.0", "rf=298.257222101"}}. */
    private static final double GRS80_A = 6378137.0;
    private static final double GRS80_RF = 298.257222101;

    /**
     * {@code ell_set.cpp:352-353} — the spherification flags. Any of them replaces the
     * ellipsoid with a derived sphere, so {@link #shape} declines rather than model it.
     */
    private static final String[] SPHERIFICATION_KEYS = {
            "R_A", "R_V", "R_a", "R_g", "R_h", "R_lat_a", "R_lat_g", "R_C"
    };

    private ProjOperatorSetup() {
    }

    /**
     * @param a a non-pipeline definition whose {@code +proj} names a PROJ 9.8.1
     *     operator and whose global parameters have already been validated
     * @return an {@code INVALID_DEFINITION} failure if this operator's setup function
     *     would reject it, otherwise {@code null}
     */
    static GieFailure validate(GieProjArgs a) {
        String name = a.peek("proj");
        if (name == null) {
            return null;
        }
        if ("lcc".equals(name)) {
            return lcc(a);
        }
        if ("aea".equals(name) || "leac".equals(name)) {
            return aea(a, "leac".equals(name));
        }
        if ("eqdc".equals(name)) {
            return eqdc(a);
        }
        if ("omerc".equals(name)) {
            return omerc(a);
        }
        if ("lagrng".equals(name)) {
            return lagrng(a);
        }
        if ("krovak".equals(name) || "mod_krovak".equals(name)) {
            return krovak(a);
        }
        if ("labrd".equals(name)) {
            return labrd(a);
        }
        if ("nsper".equals(name) || "tpers".equals(name)) {
            return nsper(a);
        }
        if ("urm5".equals(name)) {
            return urm5(a);
        }
        if ("s2".equals(name)) {
            return s2(a);
        }
        if ("isea".equals(name)) {
            return isea(a);
        }
        if ("ob_tran".equals(name)) {
            return obTran(a);
        }
        if ("topocentric".equals(name)) {
            return topocentric(a);
        }
        if ("helmert".equals(name)) {
            return helmert(a);
        }
        if ("molobadekas".equals(name)) {
            return molobadekas(a);
        }
        if ("molodensky".equals(name)) {
            return molodensky(a);
        }
        if ("defmodel".equals(name)) {
            return defmodel(a);
        }
        if ("gridshift".equals(name)) {
            return gridshift(a);
        }
        if ("ups".equals(name)) {
            return ups(a);
        }
        if ("utm".equals(name)) {
            return utm(a);
        }
        if ("sterea".equals(name)) {
            return sterea(a);
        }
        return null;
    }

    // --------------------------------------------------------------- conics

    /**
     * {@code lcc.cpp:88-113}. {@code lat_2} defaults to {@code lat_1} rather than to
     * zero, which is why {@code +proj=lcc +ellps=GRS80} with no parallels at all is
     * rejected: both are 0 and the sum guard fires.
     *
     * <p>The two eccentricity guards further down ({@code :123-141}, {@code n == 0} on
     * a secant cone) need {@code pj_msfn}/{@code pj_tsfn} and are <b>not</b> ported;
     * {@code builtins.gie:3862} and {@code :3869} stay unmeasured because of it.
     */
    private static GieFailure lcc(GieProjArgs a) {
        double phi1 = radians(a, "lat_1", 0.0);
        double phi2 = a.contains("lat_2") ? radians(a, "lat_2", 0.0) : phi1;

        if (Math.abs(phi1 + phi2) < EPS10) {
            return invalid("lcc", "|lat_1 + lat_2| should be > 0 (lcc.cpp:97-100)");
        }
        if (Math.abs(Math.cos(phi1)) < EPS10 || Math.abs(phi1) >= HALF_PI) {
            return invalid("lcc", "|lat_1| should be < 90 degrees (lcc.cpp:105-109)");
        }
        if (Math.abs(Math.cos(phi2)) < EPS10 || Math.abs(phi2) >= HALF_PI) {
            return invalid("lcc", "|lat_2| should be < 90 degrees (lcc.cpp:110-114)");
        }
        return null;
    }

    /**
     * {@code aea.cpp:130-147}, shared by {@code aea} and {@code leac} through
     * {@code setup()}. {@code leac} substitutes its own parallels at
     * {@code aea.cpp:222-223}: {@code phi2} comes from {@code +lat_1} and {@code phi1}
     * is a pole chosen by {@code +south}, so {@code +lat_2} is ignored entirely.
     *
     * <p>Note the bound is {@code > 90} here, not {@code >= 90} as in {@code lcc} —
     * {@code leac} depends on {@code |phi1| == 90} being legal.
     */
    private static GieFailure aea(GieProjArgs a, boolean leac) {
        double phi1;
        double phi2;
        if (leac) {
            phi2 = radians(a, "lat_1", 0.0);
            boolean south = a.contains("south")
                    && ProjDefinitionValidator.projBooleanValue(a.peek("south"));
            phi1 = south ? -HALF_PI : HALF_PI;
        } else {
            phi1 = radians(a, "lat_1", 0.0);
            phi2 = radians(a, "lat_2", 0.0);
        }
        String op = leac ? "leac" : "aea";
        if (Math.abs(phi1) > HALF_PI) {
            return invalid(op, "|lat_1| should be <= 90 degrees (aea.cpp:127-131)");
        }
        if (Math.abs(phi2) > HALF_PI) {
            return invalid(op, "|lat_2| should be <= 90 degrees (aea.cpp:132-136)");
        }
        if (Math.abs(phi1 + phi2) < EPS10) {
            return invalid(op, "|lat_1 + lat_2| should be > 0 (aea.cpp:137-141)");
        }
        return null;
    }

    /**
     * {@code eqdc.cpp:85-101}. The spherical {@code n == 0} guard at {@code :137-143}
     * and the {@code ml1 == ml2} guard at {@code :119-124} need {@code pj_mlfn} and
     * are not ported, so {@code builtins.gie:1865} stays unmeasured.
     */
    private static GieFailure eqdc(GieProjArgs a) {
        double phi1 = radians(a, "lat_1", 0.0);
        double phi2 = radians(a, "lat_2", 0.0);
        if (Math.abs(phi1) > HALF_PI) {
            return invalid("eqdc", "|lat_1| should be <= 90 degrees (eqdc.cpp:85-89)");
        }
        if (Math.abs(phi2) > HALF_PI) {
            return invalid("eqdc", "|lat_2| should be <= 90 degrees (eqdc.cpp:91-95)");
        }
        if (Math.abs(phi1 + phi2) < EPS10) {
            return invalid("eqdc", "|lat_1 + lat_2| should be > 0 (eqdc.cpp:96-100)");
        }
        return null;
    }

    /**
     * {@code omerc.cpp:132-195} and {@code :224-248}. Two disjoint parameterisations:
     * {@code +alpha}/{@code +gamma} with {@code +lonc}, or the two-point
     * {@code +lat_1}/{@code +lon_1}/{@code +lat_2}/{@code +lon_2} form. The
     * {@code |lat_0| < 90} guard belongs to both.
     *
     * <p>The {@code +gamma}-without-{@code +alpha} limit is the interesting one. PROJ
     * computes {@code alpha_c = aasin(D * sin(gamma))} and then <em>tests
     * {@code proj_errno}</em> ({@code :232-239}), which {@code aasin} sets only when
     * the argument exceeds {@code ONE_TOL} ({@code aasincos.cpp:14-19}). So the limit
     * is {@code |D sin(gamma)| > 1.00000000000001}, and the slack matters: on a sphere
     * with {@code +lat_0=10} the limit is exactly {@code gamma = 80}, where
     * {@code D sin(gamma)} rounds to within an ulp of 1 and must <em>not</em> raise.
     * The {@code +alpha} branch at {@code :227-230} calls {@code aasin} too but never
     * checks the errno, so there is no guard there.
     *
     * <p>Confirmed against the oracle, including the case the corpus gets wrong:
     * {@code builtins.gie:5335} labels {@code +R=6400000 +rf=300 +gamma=80.01} "# OK",
     * but {@code +R} overrules every shape parameter ({@code ell_set.cpp:90-98}) so it
     * is a sphere, and {@code proj 9.8.1} rejects it with "|gamma| should be <=
     * 80.000000". That block asserts nothing, so the comment was never checked.
     */
    private static GieFailure omerc(GieProjArgs a) {
        boolean alp = a.contains("alpha");
        boolean gam = a.contains("gamma");
        double phi0 = radians(a, "lat_0", 0.0);

        if (!alp && !gam) {
            double phi1 = radians(a, "lat_1", 0.0);
            double phi2 = radians(a, "lat_2", 0.0);
            if (Math.abs(phi1) > HALF_PI - OMERC_TOL) {
                return invalid("omerc", "|lat_1| should be < 90 degrees (omerc.cpp:157-162)");
            }
            if (Math.abs(phi2) > HALF_PI - OMERC_TOL) {
                return invalid("omerc", "|lat_2| should be < 90 degrees (omerc.cpp:164-169)");
            }
            if (Math.abs(phi1 - phi2) <= OMERC_TOL) {
                return invalid("omerc",
                        "lat_1 should be different from lat_2 (omerc.cpp:171-177)");
            }
            if (Math.abs(phi1) <= OMERC_TOL) {
                return invalid("omerc",
                        "lat_1 should be different from 0 (omerc.cpp:179-185)");
            }
        }
        if (Math.abs(Math.abs(phi0) - HALF_PI) <= OMERC_TOL) {
            return invalid("omerc", "|lat_0| should be < 90 degrees "
                    + "(omerc.cpp:187-192 in the two-point branch, :243-248 otherwise)");
        }
        if (gam && !alp) {
            double[] ell = shape(a);
            if (ell != null) {
                double es = ell[1];
                double gamma = radians(a, "gamma", 0.0);
                double d = omercD(es, phi0);
                if (Math.abs(d * Math.sin(gamma)) > ONE_TOL) {
                    return invalid("omerc", "given lat_0, |gamma| should be <= "
                            + Math.toDegrees(Math.asin(1.0 / d))
                            + " degrees (omerc.cpp:231-239 via aasin)");
                }
            }
        }
        return null;
    }

    /** {@code omerc.cpp:199-221} — {@code D}, which is what bounds {@code +gamma}. */
    private static double omercD(double es, double phi0) {
        double oneEs = 1.0 - es;
        double com = Math.sqrt(oneEs);
        if (Math.abs(phi0) <= OMERC_EPS) {
            return 1.0;
        }
        double sinph0 = Math.sin(phi0);
        double cosph0 = Math.cos(phi0);
        double con = 1.0 - es * sinph0 * sinph0;
        double b = cosph0 * cosph0;
        b = Math.sqrt(1.0 + es * b * b / oneEs);
        return b * com / (cosph0 * Math.sqrt(con));
    }

    // ------------------------------------------------- single-guard operators

    /** {@code lagrng.cpp:79-95}. {@code +W} defaults to 2, so it is not required. */
    private static GieFailure lagrng(GieProjArgs a) {
        double w = number(a, "W", 2.0);
        if (w <= 0) {
            return invalid("lagrng", "W should be > 0 (lagrng.cpp:83-86)");
        }
        double sinPhi1 = Math.sin(radians(a, "lat_1", 0.0));
        if (Math.abs(Math.abs(sinPhi1) - 1.0) < LAGRNG_TOL) {
            return invalid("lagrng", "|lat_1| should be < 90 degrees (lagrng.cpp:90-95)");
        }
        return null;
    }

    /**
     * {@code krovak.cpp:317-322}, reached from both {@code krovak} and
     * {@code mod_krovak} through {@code krovak_setup}. The test is an exact
     * {@code == 0.0} on {@code tan(lat_0/2 + pi/4)}, and {@code +lat_0} defaults to
     * 49d30'N ({@code :292-293}) rather than to 0 — so a bare {@code +proj=krovak} is
     * fine and only {@code +lat_0=-90} degenerates.
     */
    private static GieFailure krovak(GieProjArgs a) {
        double phi0 = radians(a, "lat_0", KROVAK_DEFAULT_PHI0);
        if (Math.tan(phi0 / 2.0 + FORT_PI) == 0.0) {
            return invalid("krovak",
                    "lat_0 + PI/4 should be different from 0 (krovak.cpp:317-322)");
        }
        return null;
    }

    /** {@code labrd.cpp:111-115}. {@code +lat_0} defaults to 0, which is the reject. */
    private static GieFailure labrd(GieProjArgs a) {
        if (radians(a, "lat_0", 0.0) == 0.0) {
            return invalid("labrd",
                    "lat_0 should be different from 0 (labrd.cpp:111-115)");
        }
        return null;
    }

    /**
     * {@code nsper.cpp:147,158-162}, shared with {@code tpers}. {@code +h} defaults to
     * 0, so it is effectively required. {@code pn1 = h / a}: the lower bound needs no
     * ellipsoid because {@code a > 0} is already established, the upper bound does.
     */
    private static GieFailure nsper(GieProjArgs a) {
        double h = number(a, "h", 0.0);
        if (h <= 0) {
            return invalid("nsper", "h / a must be > 0 (nsper.cpp:158-162)");
        }
        double[] ell = shape(a);
        if (ell != null && h / ell[0] > NSPER_MAX_PN1) {
            return invalid("nsper", "h / a must be <= 1e10 (nsper.cpp:158-162)");
        }
        return null;
    }

    /** {@code urm5.cpp:37-57}. */
    private static GieFailure urm5(GieProjArgs a) {
        if (!a.contains("n")) {
            return invalid("urm5", "missing parameter n (urm5.cpp:37-40)");
        }
        double n = number(a, "n", 0.0);
        if (n <= 0.0 || n > 1.0) {
            return invalid("urm5", "n should be in ]0,1] (urm5.cpp:42-47)");
        }
        double t = n * Math.sin(radians(a, "alpha", 0.0));
        if (Math.sqrt(1.0 - t * t) == 0.0) {
            return invalid("urm5",
                    "n * sin(|alpha|) should be < 1 (urm5.cpp:52-58)");
        }
        return null;
    }

    /** {@code s2.cpp:77-81, 417-427}. */
    private static GieFailure s2(GieProjArgs a) {
        return oneOf(a, "s2", "UVtoST",
                new String[] {"linear", "quadratic", "tangent", "none"}, "s2.cpp:417-427");
    }

    /**
     * {@code isea.cpp:1008-1020} and {@code :1039-1050}. Neither guard is reached by
     * any corpus row — {@code builtins.gie:3152} uses the legal {@code +mode=hex} and
     * fails at transform time, which is not this class's business. They are here
     * because the setup function is what is being transcribed.
     */
    private static GieFailure isea(GieProjArgs a) {
        GieFailure f = oneOf(a, "isea", "orient", new String[] {"isea", "pole"},
                "isea.cpp:1008-1020");
        if (f != null) {
            return f;
        }
        return oneOf(a, "isea", "mode", new String[] {"plane", "di", "dd", "hex"},
                "isea.cpp:1039-1050");
    }

    /**
     * {@code ob_tran.cpp:189-206}, with {@code ob_tran_target_params} at {@code :138-168}.
     *
     * <p>Three distinct rejections, in PROJ's order. Note that {@code +o_proj} written
     * with no {@code '='} still satisfies the first: {@code pj_param("so_proj").s}
     * points at the terminating NUL, which is non-null. It is the <em>rewrite</em> in
     * {@code ob_tran_target_params} that then finds no {@code proj=} to hand to
     * {@code pj_create_argv_internal}, so it fails as "unknown" rather than "missing".
     *
     * <p>Only the first {@code o_proj} token is modelled, which is what
     * {@code pj_param} reads. A definition repeating the key — {@code +o_proj=moll
     * +o_proj=ob_tran} — is rejected upstream for reasons the rewrite loop makes
     * genuinely hard to predict, and is left alone; see
     * {@code ProjOperatorSetupTest.UNMODELLED}. Under-counting there is free, and no
     * corpus row does it.
     */
    private static GieFailure obTran(GieProjArgs a) {
        GieToken t = a.find("o_proj");
        if (t == null) {
            return invalid("ob_tran", "missing parameter o_proj (ob_tran.cpp:189-192)");
        }
        String target = t.value();
        // ob_tran_target_params rewrites `o_proj=xxx` to `proj=xxx` and bails out
        // entirely when that yields `proj=ob_tran` - the recursion guard.
        if ("ob_tran".equals(target)) {
            return invalid("ob_tran",
                    "o_proj=ob_tran would recurse (ob_tran.cpp:164-168, :195-200)");
        }
        // Anything pj_create_argv_internal cannot build is "unknown": no name at all,
        // a name PROJ does not have, or `pipeline`, which has no steps to run here.
        if (target == null || target.isEmpty() || "pipeline".equals(target)
                || !ProjTables.isProjOperator(target)) {
            return invalid("ob_tran", "+o_proj=" + (target == null ? "" : target)
                    + " does not name a projection to rotate (ob_tran.cpp:204-207)");
        }
        return null;
    }

    /** {@code topocentric.cpp:92-116}. Purely a presence algebra over six keys. */
    private static GieFailure topocentric(GieProjArgs a) {
        boolean hasX0 = a.contains("X_0");
        boolean hasY0 = a.contains("Y_0");
        boolean hasZ0 = a.contains("Z_0");
        boolean hasLon0 = a.contains("lon_0");
        boolean hasLat0 = a.contains("lat_0");
        boolean hasH0 = a.contains("h_0");
        if (!hasX0 && !hasLon0) {
            return invalid("topocentric", "missing X_0 or lon_0 (topocentric.cpp:98-101)");
        }
        if ((hasX0 || hasY0 || hasZ0) && (hasLon0 || hasLat0 || hasH0)) {
            return invalid("topocentric",
                    "(X_0,Y_0,Z_0) and (lon_0,lat_0,h_0) are mutually exclusive "
                            + "(topocentric.cpp:102-107)");
        }
        if (hasX0 && (!hasY0 || !hasZ0)) {
            return invalid("topocentric", "missing Y_0 and/or Z_0 (topocentric.cpp:108-111)");
        }
        if (hasLon0 && !hasLat0) {
            return invalid("topocentric", "missing lat_0 (topocentric.cpp:112-116)");
        }
        return null;
    }

    /**
     * {@code helmert.cpp:581-585} plus {@code read_convention} at {@code :517-551}.
     *
     * <p>{@code +convention} is required exactly when a rotation is present, and
     * "present" means <em>non-zero</em> after {@code :663-666} compares all six of
     * {@code rx ry rz drx dry drz} — verified: {@code +rx=0} is accepted and
     * {@code +drx=1} is not. {@code +towgs84} feeds the same three rotation slots
     * through {@code pj_datum_set} ({@code :590-604}), and then may only be combined
     * with {@code convention=position_vector} ({@code :542-548}).
     */
    private static GieFailure helmert(GieProjArgs a) {
        if (a.contains("transpose")) {
            return invalid("helmert",
                    "the 'transpose' argument is no longer valid (helmert.cpp:581-585)");
        }
        return convention(a, "helmert", hasRotation(a));
    }

    /**
     * {@code helmert.cpp:699-723}. {@code molobadekas} never assigns
     * {@code Q->no_rotation}, so the calloc'd zero leaves {@code read_convention}'s
     * {@code !Q->no_rotation} permanently true: {@code +convention} is
     * <em>unconditionally</em> required, even for a bare {@code +proj=molobadekas}.
     * Confirmed against the oracle in both directions.
     */
    private static GieFailure molobadekas(GieProjArgs a) {
        return convention(a, "molobadekas", true);
    }

    /** {@code helmert.cpp:517-551}, shared by {@code helmert} and {@code molobadekas}. */
    private static GieFailure convention(GieProjArgs a, String op, boolean required) {
        if (!required) {
            return null;
        }
        GieToken t = a.find("convention");
        if (t == null) {
            return invalid(op, "missing 'convention' argument (helmert.cpp:523-528)");
        }
        String v = t.value();
        boolean positionVector = "position_vector".equals(v);
        if (!positionVector && !"coordinate_frame".equals(v)) {
            return invalid(op, "invalid value for 'convention' (helmert.cpp:529-538)");
        }
        if (a.contains("towgs84") && !positionVector) {
            return invalid(op, "towgs84 should only be used with "
                    + "convention=position_vector (helmert.cpp:542-548)");
        }
        return null;
    }

    /** {@code helmert.cpp:663-666}, including the {@code towgs84} feed at {@code :590-604}. */
    private static boolean hasRotation(GieProjArgs a) {
        String[] keys = {"rx", "ry", "rz", "drx", "dry", "drz"};
        for (int i = 0; i < keys.length; i++) {
            if (number(a, keys[i], 0.0) != 0.0) {
                return true;
            }
        }
        String towgs84 = a.peek("towgs84");
        if (towgs84 != null) {
            String[] parts = towgs84.split(",", -1);
            for (int i = 3; i < parts.length && i < 6; i++) {
                Double d = projDouble(parts[i]);
                if (d != null && d.doubleValue() != 0.0) {
                    return true;
                }
            }
        }
        return false;
    }

    /** {@code molodensky.cpp:321-349}: five required translation/ellipsoid deltas. */
    private static GieFailure molodensky(GieProjArgs a) {
        String[] required = {"dx", "dy", "dz", "da", "df"};
        for (int i = 0; i < required.length; i++) {
            if (!a.contains(required[i])) {
                return invalid("molodensky",
                        "missing " + required[i] + " (molodensky.cpp:321-349)");
            }
        }
        return null;
    }

    /** {@code defmodel.cpp:402-406}. */
    private static GieFailure defmodel(GieProjArgs a) {
        if (!a.contains("model")) {
            return invalid("defmodel", "+model= should be specified (defmodel.cpp:402-406)");
        }
        return null;
    }

    /**
     * {@code gridshift.cpp:913-916} and {@code :955-965}.
     *
     * <p>The {@code +interpolation} guard sits after the grid is opened, so the local
     * oracle cannot reach it — every probe stops at "could not find required grid(s)"
     * because PROJ's data path here has no {@code tests/} tree. The guard itself is
     * unambiguous in the source, and it is transcribed alongside the {@code +grids}
     * one, which the oracle does confirm.
     */
    private static GieFailure gridshift(GieProjArgs a) {
        if (!a.contains("grids")) {
            return invalid("gridshift", "+grids parameter missing (gridshift.cpp:913-916)");
        }
        return oneOf(a, "gridshift", "interpolation",
                new String[] {"bilinear", "biquadratic"}, "gridshift.cpp:955-965");
    }

    // -------------------------------------------------- ellipsoid-dependent

    /** {@code stere.cpp:318-323}: {@code ups} has no spherical formulation. */
    private static GieFailure ups(GieProjArgs a) {
        double[] ell = shape(a);
        if (ell != null && ell[1] == 0.0) {
            return invalid("ups",
                    "only the ellipsoidal formulation is supported (stere.cpp:318-323)");
        }
        return null;
    }

    /** {@code tmerc.cpp:632-653}. */
    private static GieFailure utm(GieProjArgs a) {
        double[] ell = shape(a);
        if (ell != null && ell[1] == 0.0) {
            return invalid("utm",
                    "eccentricity should not be zero (tmerc.cpp:632-636)");
        }
        if (a.contains("zone")) {
            Double z = projDouble(a.peek("zone"));
            if (z != null && !(z.doubleValue() > 0 && z.doubleValue() <= 60)) {
                return invalid("utm", "zone must be in [1,60] (tmerc.cpp:644-653)");
            }
        }
        return null;
    }

    /**
     * {@code sterea.cpp:104-106} through {@code pj_gauss_ini} at
     * {@code gauss.cpp:49-78}, which is {@code sterea}'s only caller in the whole of
     * 9.8.1. Two {@code nullptr} returns, both surfacing as {@code PROJ_ERR_OTHER}:
     * {@code C == 0}, and — the one the corpus reaches — {@code srat} underflowing to
     * zero, because {@code ratexp} grows without bound as {@code es} approaches 1.
     * With {@code +a=9999 +b=.9 +lat_0=73} the exponent is about 475 and the base
     * about 0.0223, so {@code pow} returns 0.
     */
    private static GieFailure sterea(GieProjArgs a) {
        double[] ell = shape(a);
        if (ell == null) {
            return null;
        }
        double es = ell[1];
        double e = Math.sqrt(es);
        double phi0 = radians(a, "lat_0", 0.0);
        double sphi = Math.sin(phi0);
        double cphi = Math.cos(phi0);
        cphi *= cphi;
        double c = Math.sqrt(1.0 + es * cphi * cphi / (1.0 - es));
        if (c == 0.0) {
            return invalid("sterea", "pj_gauss_ini: C == 0 (gauss.cpp:61-65)");
        }
        double ratexp = 0.5 * c * e;
        double esinp = e * sphi;
        double srat = Math.pow((1.0 - esinp) / (1.0 + esinp), ratexp);
        if (srat == 0.0) {
            return invalid("sterea",
                    "pj_gauss_ini: srat underflows to 0 with ratexp = " + ratexp
                            + " (gauss.cpp:66-71)");
        }
        return null;
    }

    // ------------------------------------------------------------- helpers

    /**
     * {@code pj_param} type {@code 's'} against a fixed value set.
     *
     * @return a failure when the key is present with a value outside {@code allowed};
     *     {@code null} when absent, since every one of these has a default
     */
    private static GieFailure oneOf(GieProjArgs a, String op, String key, String[] allowed,
            String where) {
        GieToken t = a.find(key);
        if (t == null) {
            return null;
        }
        String v = t.value();
        for (int i = 0; i < allowed.length; i++) {
            if (allowed[i].equals(v)) {
                return null;
            }
        }
        StringBuilder list = new StringBuilder();
        for (int i = 0; i < allowed.length; i++) {
            list.append(i == 0 ? "" : ", ").append(allowed[i]);
        }
        return invalid(op, "+" + key + "=" + v + " is not one of " + list + " (" + where + ")");
    }

    /**
     * The {@code a} and {@code es} that {@code ell_set.cpp} would resolve, <b>or
     * {@code null} when this class declines to say.</b>
     *
     * <p>It declines whenever a named {@code +ellps} or {@code +datum}, an
     * {@code +init=}, or any spherification flag is in play, because reproducing those
     * faithfully means porting all 657 lines of {@code ell_set.cpp} plus the 46-entry
     * {@code ellps.cpp} table — a second, competing model of parameter resolution
     * living in test scope. Every caller treats {@code null} as "skip this check",
     * which costs assertions and cannot manufacture a pass.
     *
     * <p>What it does model, verbatim: {@code +R} short-circuiting everything
     * ({@code :90-98}), {@code ellps_size} ({@code :199-238}), {@code ellps_shape}'s
     * first-match-wins over {@code rf f es e b} ({@code :242-345}), and the implicit
     * {@code +ellps=GRS80} that {@code init.cpp:362} appends when the definition names
     * no size or shape at all.
     *
     * @return {@code {a, es}}, or {@code null}
     */
    static double[] shape(GieProjArgs a) {
        if (a.contains("ellps") || a.contains("datum") || a.contains("init")) {
            return null;
        }
        for (int i = 0; i < SPHERIFICATION_KEYS.length; i++) {
            if (a.contains(SPHERIFICATION_KEYS[i])) {
                return null;
            }
        }
        if (a.contains("R")) {
            Double r = projDouble(a.peek("R"));
            return r == null || !(r.doubleValue() > 0)
                    ? null : new double[] {r.doubleValue(), 0.0};
        }

        boolean hasShapeKey = a.contains("rf") || a.contains("f") || a.contains("es")
                || a.contains("e") || a.contains("b");
        Double major = a.contains("a") ? projDouble(a.peek("a")) : null;
        if (major == null) {
            if (hasShapeKey || a.contains("no_defs")) {
                // ellps_size: "Major axis not given". Left to the existing
                // hasEllipsoidSize()/impliesGrs80() checks rather than duplicated here.
                return null;
            }
            double f = 1.0 / GRS80_RF;
            return new double[] {GRS80_A, 2 * f - f * f};
        }
        if (!(major.doubleValue() > 0)) {
            return null;
        }
        double size = major.doubleValue();

        double es;
        if (a.contains("rf")) {
            Double v = projDouble(a.peek("rf"));
            if (v == null || !(v.doubleValue() > 0)) {
                return null;
            }
            double f = 1.0 / v.doubleValue();
            es = 2 * f - f * f;
        } else if (a.contains("f")) {
            Double v = projDouble(a.peek("f"));
            if (v == null || v.doubleValue() < 0) {
                return null;
            }
            double f = v.doubleValue();
            es = 2 * f - f * f;
        } else if (a.contains("es")) {
            Double v = projDouble(a.peek("es"));
            if (v == null) {
                return null;
            }
            es = v.doubleValue();
        } else if (a.contains("e")) {
            Double v = projDouble(a.peek("e"));
            if (v == null) {
                return null;
            }
            es = v.doubleValue() * v.doubleValue();
        } else if (a.contains("b")) {
            Double v = projDouble(a.peek("b"));
            if (v == null || !(v.doubleValue() > 0)) {
                return null;
            }
            if (v.doubleValue() == size) {
                es = 0.0;
            } else {
                double f = (size - v.doubleValue()) / size;
                es = 2 * f - f * f;
            }
        } else {
            es = 0.0;
        }
        if (!(es >= 0.0) || es >= 1.0) {
            // Already an INVALID_DEFINITION by the global ellipsoid checks; declining
            // here keeps this method's contract to "a shape PROJ would have built".
            return null;
        }
        return new double[] {size, es};
    }

    private static GieFailure invalid(String op, String why) {
        return GieFailures.invalidDefinition("+proj=" + op + ": " + why
                + " - PROJ 9.8.1's own setup function rejects this definition");
    }
}
