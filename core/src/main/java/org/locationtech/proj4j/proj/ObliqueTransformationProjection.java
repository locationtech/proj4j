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

package org.locationtech.proj4j.proj;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.InvalidValueException;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.ProjectionException;
import org.locationtech.proj4j.units.Angle;
import org.locationtech.proj4j.util.FastStrictTrig;
import org.locationtech.proj4j.util.ProjectionMath;

/**
 * General Oblique Transformation, {@code +proj=ob_tran} — a port of
 * {@code 9.8.1:src/projections/ob_tran.cpp}.
 *
 * <p>A meta-projection: it rotates the sphere so that a chosen point becomes the pole, then hands
 * the rotated coordinate to a <em>child</em> projection named by {@code +o_proj=}. Its own
 * assertion count in the corpus is small (12 {@code expect}, 2 of them {@code expect failure}), but
 * it is the gateway to every {@code +o_proj=} CRS and a large slice of EPSG.
 *
 * <h2>The composition seam needs no new hook</h2>
 *
 * <p>Upstream invokes the child at the <b>raw</b> level — {@code Q->link->fwd(lp, Q->link)}, never
 * {@code pj_fwd} — so the child's own {@code lam0}, {@code a}, {@code x_0} and {@code k_0} are
 * never applied even though they were parsed. {@link Projection#project(double, double,
 * ProjCoordinate)} and {@link Projection#projectInverse(double, double, ProjCoordinate)} are
 * {@code protected}, which in Java <em>includes</em> package access, so a class in this package can
 * call a sibling's raw layer directly. {@link SpilhausProjection} already proves it against
 * {@code adams_ws2}. Nothing new was needed here.
 *
 * <h2>The five things that are easy to get wrong</h2>
 *
 * <ol>
 * <li><b>{@code o_proj=xxx} becomes {@code proj=xxx} by advancing the string pointer two
 *     characters</b> ({@code ob_tran.cpp:159}, {@code args.argv[i] += 2}), after
 *     {@code proj=ob_tran} and a bare {@code inv} have been dropped. Everything else — including
 *     {@code +o_lat_p}, which the child ignores, and {@code +lon_0}, which the child parses and
 *     then never uses because it is called raw — passes through verbatim. Only the <b>first</b>
 *     {@code o_proj=} token is rewritten; the loop {@code break}s. See
 *     {@link #childParameters(String[])}.</li>
 * <li><b>Recursion is blocked by string comparison after the rewrite</b>, not before: if the
 *     rewritten token is exactly {@code proj=ob_tran} the whole child list is discarded and the
 *     setup fails with {@code PROJ_ERR_INVALID_OP_MISSING_ARG}. That is what makes
 *     {@code builtins.gie:5072}'s {@code +o_proj +o_proj=ob_tran} an
 *     {@code invalid_op_missing_arg}. Note the bare {@code +o_proj} is <em>not</em> the token that
 *     matches: {@code strncmp("o_proj", "o_proj=", 7)} compares the terminating NUL against
 *     {@code '='} and differs, so it is skipped — and yet it satisfies the earlier
 *     "is {@code o_proj} present" test, because {@code pj_param}'s {@code 's'} sigil returns the
 *     empty string rather than {@code nullptr} for a valueless token.</li>
 * <li><b>Three mutually exclusive pole specifications</b>, tested in a fixed order and by
 *     <em>presence</em> ({@code pj_param}'s {@code 't'} sigil), never by value:
 *     {@code +o_alpha} wins, else {@code +o_lat_p}, else the two-point
 *     {@code +o_lon_1/+o_lat_1/+o_lon_2/+o_lat_2} form with its four validity guards.</li>
 * <li><b>{@code P->fwd}/{@code P->inv} are set only if the child has that direction</b>
 *     ({@code ob_tran.cpp:286-293}, {@code Q->link->fwd ? o_forward : nullptr}). So
 *     {@code +proj=ob_tran +o_proj=guyou} legitimately has a forward and <em>no</em> inverse, and
 *     asking for one must fail rather than fall back to anything. {@link #hasInverse()} answers
 *     the child's capability, so {@link Projection#projectInverse} raises
 *     {@link ErrorCause#NO_INVERSE_AVAILABLE} for exactly the child projections upstream leaves
 *     {@code nullptr}.</li>
 * <li><b>If the child outputs radians, the wrapper's output units become
 *     {@code PJ_IO_UNITS_WHATEVER}</b> ({@code ob_tran.cpp:296-298}), and {@code fwd_finalize}'s
 *     {@code WHATEVER} case is a bare {@code break} — no {@code a}, no {@code x_0}, no
 *     {@code fr_meter}, in either direction. See {@link #initialize()} for how that is
 *     reproduced.</li>
 * </ol>
 *
 * <h2>How {@code +o_proj} and friends arrive</h2>
 *
 * <p>{@code Proj4Parser} hands this class the <em>whole</em> raw argument list through
 * {@link #setParameters(String[])}, before {@code initialize()}, rather than dispatching the ten
 * {@code o_*} keys one at a time. That mirrors upstream: {@code ob_tran_target_params} builds the
 * child's {@code argv} from ob_tran's own, and the pole specification is chosen by <em>presence</em>
 * of {@code +o_alpha} / {@code +o_lat_p} / the two-point pair, which {@code pj_param}'s {@code 't'}
 * sigil answers without looking at the value — so {@code +o_alpha=0} selects the azimuth form.
 *
 * <p>A parse that supplies no argument array at all, and a definition with no {@code +o_proj},
 * are both refused with {@link ErrorCause#MISSING_PARAM}, which is upstream's
 * {@code "Missing parameter: o_proj"}.
 *
 * @since 1.5.0
 */
public class ObliqueTransformationProjection extends Projection {

    private static final long serialVersionUID = -5198764690057590149L;

    /** {@code ob_tran.cpp:23}, {@code #define TOL 1e-10} — the oblique/transverse threshold. */
    private static final double TOL = 1e-10;

    /** {@code Q->link}: the child projection, invoked at its raw layer. */
    private Projection link;

    /** {@code Q->lamp}: the longitude of the new pole, radians. */
    private double lamp;

    /** {@code Q->cphip}/{@code Q->sphip}, and whether the oblique branch was selected. */
    private double cphip;
    private double sphip;
    private boolean oblique;

    /** Scratch for the child's result; see {@link SpilhausProjection}'s note on thread safety. */
    private final ProjCoordinate child = new ProjCoordinate();

    /**
     * The whole of {@code PJ_PROJECTION(ob_tran)}'s parameter handling, in the one call the parser
     * will need: build the child from the rewritten argument list, then resolve the pole.
     *
     * @param args the full {@code +}-parameter list of the {@code ob_tran} definition, exactly as
     *             handed to {@code CRSFactory.createFromParameters}; leading {@code +} optional on
     *             each token, as PROJ allows
     * @throws InvalidValueException for each of upstream's six setup rejections
     */
    public void setParameters(String[] args) {
        setChild(new CRSFactory()
                .createFromParameters("ob_tran o_proj", childParameters(args))
                .getProjection());
        applyPoleSpecification(parameterMap(args));
    }

    /**
     * Sets the child projection directly, for a caller composing programmatically.
     * <p>
     * The child must already be initialized — upstream's child is a fully-built {@code PJ} — and
     * its {@code lam0}, {@code a}, {@code x_0} and {@code k_0} are irrelevant, because only its
     * raw layer is ever called.
     */
    public void setChild(Projection child) {
        if (child instanceof ObliqueTransformationProjection) {
            // Unreachable through setParameters, which blocks it textually as upstream does, but
            // reachable by a programmatic caller -- and an ob_tran of an ob_tran recurses without
            // bound in upstream too, which is why the string check exists at all.
            throw new InvalidValueException(ErrorCause.INVALID_PARAM_VALUE,
                    "+proj=ob_tran cannot rotate another +proj=ob_tran");
        }
        this.link = child;
    }

    public Projection getChild() {
        return link;
    }

    /** {@code +o_lon_p}/{@code +o_lat_p} — the "specified new pole" form, radians. */
    public void setPole(double oLonP, double oLatP) {
        this.lamp = oLonP;
        setPhip(oLatP);
    }

    /**
     * {@code +o_alpha}/{@code +o_lon_c}/{@code +o_lat_c} — the azimuth form, radians
     * ({@code ob_tran.cpp:230-243}).
     *
     * @throws InvalidValueException if {@code |oLatC|} is within {@link #TOL} of 90&deg;
     */
    public void setAzimuthalPole(double oAlpha, double oLonC, double oLatC) {
        if (Math.abs(Math.abs(oLatC) - ProjectionMath.HALFPI) <= TOL) {
            throw new InvalidValueException(ErrorCause.INVALID_PARAM_VALUE,
                    "+o_lat_c=" + (oLatC * RTD) + " deg: Invalid value for lat_c: |lat_c| "
                            + "should be < 90 deg");
        }
        this.lamp = oLonC + AdamsProjection.aatan2(-FastStrictTrig.cos(oAlpha),
                -FastStrictTrig.sin(oAlpha) * FastStrictTrig.sin(oLatC));
        setPhip(AdamsProjection.aasin(FastStrictTrig.cos(oLatC) * FastStrictTrig.sin(oAlpha)));
    }

    /**
     * {@code +o_lon_1}/{@code +o_lat_1}/{@code +o_lon_2}/{@code +o_lat_2} — the "new equator
     * points" form, radians ({@code ob_tran.cpp:245-281}).
     *
     * <p>Four guards, in upstream's order and with upstream's messages. Note the third and fourth
     * use {@code TOL} on a <em>difference</em> and on {@code |phi1|}, so "the two latitudes must
     * differ" and "lat_1 must not be zero" are both tested at 1e-10 rad, about 0.6&nbsp;&micro;m
     * on the ground.
     *
     * @throws InvalidValueException for each of the four
     */
    public void setEquatorPoints(double oLon1, double oLat1, double oLon2, double oLat2) {
        final double con = Math.abs(oLat1);
        if (Math.abs(oLat1) > ProjectionMath.HALFPI - TOL) {
            throw new InvalidValueException(ErrorCause.INVALID_PARAM_VALUE,
                    "+o_lat_1=" + (oLat1 * RTD) + " deg: Invalid value for lat_1: |lat_1| "
                            + "should be < 90 deg");
        }
        if (Math.abs(oLat2) > ProjectionMath.HALFPI - TOL) {
            throw new InvalidValueException(ErrorCause.INVALID_PARAM_VALUE,
                    "+o_lat_2=" + (oLat2 * RTD) + " deg: Invalid value for lat_2: |lat_2| "
                            + "should be < 90 deg");
        }
        if (Math.abs(oLat1 - oLat2) < TOL) {
            throw new InvalidValueException(ErrorCause.INVALID_PARAM_VALUE,
                    "+o_lat_1=" + (oLat1 * RTD) + " +o_lat_2=" + (oLat2 * RTD)
                            + " deg: Invalid value for lat_1 and lat_2: lat_1 should be "
                            + "different from lat_2");
        }
        if (con < TOL) {
            throw new InvalidValueException(ErrorCause.INVALID_PARAM_VALUE,
                    "+o_lat_1=" + (oLat1 * RTD) + " deg: Invalid value for lat_1: lat_1 should "
                            + "be different from zero");
        }
        final double sinPhi1 = FastStrictTrig.sin(oLat1);
        final double cosPhi1 = FastStrictTrig.cos(oLat1);
        final double sinPhi2 = FastStrictTrig.sin(oLat2);
        final double cosPhi2 = FastStrictTrig.cos(oLat2);
        // Plain atan2/atan here, not aatan2: ob_tran.cpp:274-280 uses the unguarded forms.
        this.lamp = Math.atan2(
                cosPhi1 * sinPhi2 * FastStrictTrig.cos(oLon1)
                        - sinPhi1 * cosPhi2 * FastStrictTrig.cos(oLon2),
                sinPhi1 * cosPhi2 * FastStrictTrig.sin(oLon2)
                        - cosPhi1 * sinPhi2 * FastStrictTrig.sin(oLon1));
        setPhip(Math.atan(-FastStrictTrig.cos(lamp - oLon1) / FastStrictTrig.tan(oLat1)));
    }

    /** {@code ob_tran.cpp:283-294}: {@code |phip| > TOL} selects oblique, otherwise transverse. */
    private void setPhip(double phip) {
        this.oblique = Math.abs(phip) > TOL;
        this.cphip = FastStrictTrig.cos(phip);
        this.sphip = FastStrictTrig.sin(phip);
    }

    /**
     * Requires a child and reproduces item 5 — the {@code PJ_IO_UNITS_WHATEVER} demotion.
     *
     * <p>{@code fwd_finalize}'s and {@code inv_prepare}'s {@code WHATEVER} cases are both a bare
     * {@code break}, so when the child is geographic the wrapper applies <em>no</em> affine at all
     * and its output is raw radians. Proj4J has one affine, {@code totalScale * v +
     * totalFalse*}, computed by {@link Projection#initialize()} from {@code a}, {@code fromMetres}
     * and the false origin — so the way to say "no affine" is to make it the identity:
     * {@code a = 1}, {@code fromMetres = 1}, false origin zero. The ellipsoid object itself is
     * left alone, so {@link #getEllipsoid()} still reports what the definition asked for, and
     * nothing in this class or in the child's raw layer reads {@code a}.
     *
     * @throws InvalidValueException {@link ErrorCause#MISSING_PARAM} if no child was set, which is
     *         upstream's {@code "Missing parameter: o_proj"}
     */
    @Override
    public void initialize() {
        if (link == null) {
            throw new InvalidValueException(ErrorCause.MISSING_PARAM,
                    "+proj=ob_tran: Missing parameter: o_proj. The child projection is set from "
                            + "the whole parameter list by setParameters(String[]), or directly "
                            + "by setChild(Projection).");
        }
        if (childOutputsRadians()) {
            a = 1.0;
            fromMetres = 1.0;
            falseEasting = 0.0;
            falseNorthing = 0.0;
        }
        super.initialize();
    }

    /**
     * {@code Q->link->right == PJ_IO_UNITS_RADIANS}, i.e. "the child is {@code latlong}".
     * {@code isGeographic()} is the predicate Proj4J already uses for that.
     */
    private boolean childOutputsRadians() {
        return link != null && Boolean.TRUE.equals(link.isGeographic());
    }

    /**
     * {@code o_forward} (Snyder 5-8b then 5-7) or {@code t_forward}, then the child's raw forward.
     */
    @Override
    protected ProjCoordinate project(double lam, double phi, ProjCoordinate xy) {
        final double coslam = FastStrictTrig.cos(lam);
        final double sinphi = FastStrictTrig.sin(phi);
        final double cosphi = FastStrictTrig.cos(phi);
        final double rotLam;
        final double rotPhi;
        if (oblique) {
            /* Formula (5-8b) of Snyder's "Map projections: a working manual" */
            rotLam = AdamsProjection.adjlon(
                    AdamsProjection.aatan2(cosphi * FastStrictTrig.sin(lam),
                            sphip * cosphi * coslam + cphip * sinphi) + lamp);
            /* Formula (5-7) */
            rotPhi = AdamsProjection.aasin(sphip * sinphi - cphip * cosphi * coslam);
        } else {
            rotLam = AdamsProjection.adjlon(
                    AdamsProjection.aatan2(cosphi * FastStrictTrig.sin(lam), sinphi) + lamp);
            rotPhi = AdamsProjection.aasin(-cosphi * coslam);
        }
        link.project(rotLam, rotPhi, child);
        xy.x = child.x;
        xy.y = child.y;
        return xy;
    }

    /**
     * The child's raw inverse, then {@code o_inverse} (Snyder 5-9 then 5-10b) or
     * {@code t_inverse}.
     *
     * <p>Upstream guards the rotation with {@code if (lp.lam != HUGE_VAL)}, i.e. "only if the
     * child's inverse succeeded". Proj4J's child raises instead of returning a sentinel, so the
     * guard is the propagating exception and there is nothing to test.
     */
    @Override
    protected ProjCoordinate projectInverse(double x, double y, ProjCoordinate lp) {
        if (!hasInverse()) {
            throw new ProjectionException(ErrorCause.NO_INVERSE_AVAILABLE, this,
                    "+proj=ob_tran +o_proj=" + link.getName() + " has no inverse, because the "
                            + "child projection has none. Upstream leaves P->inv null in exactly "
                            + "this case (ob_tran.cpp:288/292), so there is nothing to fall back "
                            + "to.");
        }
        link.projectInverse(x, y, child);
        final double childLam = child.x;
        final double childPhi = child.y;
        if (oblique) {
            final double lam = childLam - lamp;
            final double coslam = FastStrictTrig.cos(lam);
            final double sinphi = FastStrictTrig.sin(childPhi);
            final double cosphi = FastStrictTrig.cos(childPhi);
            /* Formula (5-9) */
            lp.y = AdamsProjection.aasin(sphip * sinphi + cphip * cosphi * coslam);
            /* Formula (5-10b) */
            lp.x = AdamsProjection.aatan2(cosphi * FastStrictTrig.sin(lam),
                    sphip * cosphi * coslam - cphip * sinphi);
        } else {
            final double cosphi = FastStrictTrig.cos(childPhi);
            final double t = childLam - lamp;
            lp.x = AdamsProjection.aatan2(cosphi * FastStrictTrig.sin(t),
                    -FastStrictTrig.sin(childPhi));
            lp.y = AdamsProjection.aasin(cosphi * FastStrictTrig.cos(t));
        }
        return lp;
    }

    /**
     * {@code Q->link->inv ? o_inverse : nullptr} — the wrapper is invertible exactly when the
     * child is.
     */
    @Override
    public boolean hasInverse() {
        return link != null && (link.hasInverse() || Boolean.TRUE.equals(link.isGeographic()));
    }

    @Override
    public String toString() {
        return "General Oblique Transformation"
                + (link == null ? "" : " of " + link.getName());
    }

    // ------------------------------------------------------------------------------------------
    // ob_tran_target_params
    // ------------------------------------------------------------------------------------------

    /**
     * {@code ob_tran_target_params} ({@code ob_tran.cpp:130-170}) — turn the {@code ob_tran}
     * argument list into the child's.
     *
     * <p>Three steps, in upstream's order:
     * <ol>
     * <li>drop every token equal to {@code proj=ob_tran} or to a bare {@code inv};</li>
     * <li>find the <b>first</b> token starting {@code o_proj=} and strip its first two characters
     *     — that is what {@code args.argv[i] += 2} does — then stop looking;</li>
     * <li>if the rewritten token is exactly {@code proj=ob_tran}, discard the whole list.</li>
     * </ol>
     *
     * <p>A leading {@code +} is stripped from each token first, because
     * {@code createParameterMap}'s tokens are compared without it upstream (PROJ's
     * {@code paralist->param} holds the key without the {@code +}) and Proj4J accepts both forms.
     *
     * @param args the {@code ob_tran} argument list
     * @return the child's argument list
     * @throws InvalidValueException if {@code +o_proj} is absent, or names {@code ob_tran}
     */
    public static String[] childParameters(String[] args) {
        final List<String> out = new ArrayList<String>();
        boolean oProjPresent = false;
        for (int i = 0; i < args.length; i++) {
            if (args[i] == null) {
                continue;
            }
            String token = args[i].startsWith("+") ? args[i].substring(1) : args[i];
            if (token.length() == 0) {
                continue;
            }
            // The "is o_proj present at all" test is pj_param's 's' sigil, which yields "" -- not
            // null -- for a valueless token, so a bare "+o_proj" satisfies it.
            if (token.equals("o_proj") || token.startsWith("o_proj=")) {
                oProjPresent = true;
            }
            if (token.equals("proj=ob_tran") || token.equals("inv")) {
                continue;
            }
            out.add(token);
        }
        if (!oProjPresent) {
            throw new InvalidValueException(ErrorCause.MISSING_PARAM,
                    "+proj=ob_tran: Missing parameter: o_proj");
        }
        for (int i = 0; i < out.size(); i++) {
            if (!out.get(i).startsWith("o_proj=")) {
                continue;
            }
            // args.argv[i] += 2 -- advance the pointer two characters, "o_proj=x" -> "proj=x".
            final String rewritten = out.get(i).substring(2);
            if (rewritten.equals("proj=ob_tran")) {
                throw new InvalidValueException(ErrorCause.MISSING_PARAM,
                        "+proj=ob_tran +o_proj=ob_tran: Failed to find projection to be rotated. "
                                + "Upstream discards the entire child argument list here to "
                                + "avoid endless recursion, and reports it as a missing "
                                + "argument rather than an illegal one.");
            }
            out.set(i, rewritten);
            break;
        }
        return out.toArray(new String[out.size()]);
    }

    /**
     * Chooses among the three pole specifications by <b>presence</b>, in upstream's order
     * ({@code ob_tran.cpp:230-281}). {@code pj_param}'s {@code 't'} sigil tests presence and
     * never value, so {@code +o_alpha=0} selects the azimuth form.
     */
    private void applyPoleSpecification(Map<String, String> params) {
        if (params.containsKey("o_alpha")) {
            setAzimuthalPole(radians(params, "o_alpha"), radians(params, "o_lon_c"),
                    radians(params, "o_lat_c"));
        } else if (params.containsKey("o_lat_p")) {
            setPole(radians(params, "o_lon_p"), radians(params, "o_lat_p"));
        } else {
            setEquatorPoints(radians(params, "o_lon_1"), radians(params, "o_lat_1"),
                    radians(params, "o_lon_2"), radians(params, "o_lat_2"));
        }
    }

    /**
     * A parameter as radians, defaulting to zero when absent — {@code pj_param(..., "rXXX").f}
     * returns 0 for an absent key, and every one of {@code ob_tran}'s ten angular parameters is
     * read through the {@code "r"} sigil, i.e. {@code dmstor}. So DMS, a trailing cardinal and the
     * {@code r} radian suffix all have to work.
     */
    private static double radians(Map<String, String> params, String key) {
        final String value = params.get(key);
        if (value == null || value.length() == 0) {
            return 0.0;
        }
        try {
            final int length = value.length();
            final char last = value.charAt(length - 1);
            if (length > 1 && (last == 'r' || last == 'R')) {
                return Double.parseDouble(value.substring(0, length - 1));
            }
            return Angle.parse(value) * DTR;
        } catch (NumberFormatException e) {
            throw new InvalidValueException("Invalid value for +" + key + ": " + value, e);
        }
    }

    /** First occurrence wins, as {@code pj_param_exists} does. */
    private static Map<String, String> parameterMap(String[] args) {
        final Map<String, String> params = new LinkedHashMap<String, String>();
        for (int i = 0; i < args.length; i++) {
            if (args[i] == null) {
                continue;
            }
            String arg = args[i].startsWith("+") ? args[i].substring(1) : args[i];
            if (arg.length() == 0) {
                continue;
            }
            final int eq = arg.indexOf('=');
            final String key = eq < 0 ? arg : arg.substring(0, eq);
            final String value = eq < 0 ? null : arg.substring(eq + 1);
            if (!params.containsKey(key)) {
                params.put(key, value);
            }
        }
        return params;
    }
}
