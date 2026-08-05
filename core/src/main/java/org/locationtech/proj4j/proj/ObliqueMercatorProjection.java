/*******************************************************************************
 * Copyright 2006, 2017 Jerry Huxtable, Martin Davis
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

/*
 * This file was semi-automatically converted from the public-domain USGS PROJ source.
 */
package org.locationtech.proj4j.proj;

import java.util.Objects;

import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.InvalidValueException;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.ProjectionException;
import org.locationtech.proj4j.datum.Ellipsoid;
import org.locationtech.proj4j.util.ProjectionMath;

/**
 * Oblique Mercator (<code>+proj=omerc</code>), ported from
 * {@code 9.8.1:src/projections/omerc.cpp}.
 *
 * <h2>Parameter precedence, which is where this projection went wrong twice</h2>
 *
 * Upstream reads the centre-line either as an <em>azimuth</em> ({@code +alpha} and/or
 * {@code +gamma}, with {@code +lonc} for the centre) or as <em>two points</em>
 * ({@code +lat_1}/{@code +lon_1}, {@code +lat_2}/{@code +lon_2}). The azimuth form wins whenever
 * either {@code +alpha} or {@code +gamma} is present ({@code omerc.cpp:135-146}), and in that form
 * <b>{@code +lon_0} is ignored outright</b> — {@code omerc.cpp:193-195} logs a trace and never reads
 * it, because {@code P->lam0} is <em>derived</em> from {@code +lonc} and the azimuth. Both branches
 * therefore overwrite {@link #projectionLongitude}.
 *
 * <p>Two defects lived in the old translation of that block, both silent:
 *
 * <ul>
 * <li><b>{@code Gamma} was a plain {@code double}, so it defaulted to {@code 0.0} rather than
 *     {@code NaN}.</b> The "was {@code +gamma} supplied?" test was consequently always true, the
 *     {@code gamma = alpha} default was dead code, and <em>any</em> {@code +alpha} given without
 *     {@code +gamma} got a rotation of zero. On RSO Borneo that is <b>215,218.8 m easting and
 *     303,073.5 m northing</b>.</li>
 * <li><b>{@code u_0} was computed with {@code cos(gamma)} where upstream uses {@code cos(alpha_c)}</b>
 *     ({@code omerc.cpp:296}). On RSO Borneo, where {@code gamma != alpha}, that is
 *     <b>2,532.3 m easting and 1,899.2 m northing</b>. The two defects interact: with {@code gamma}
 *     wrongly {@code 0}, {@code cos(gamma)} is {@code 1.0}, so neither error can be measured without
 *     fixing the other.</li>
 * </ul>
 *
 * <p>Three further divergences from 9.8.1 were found while fixing those and are fixed here too:
 * the {@code |cos(B*lam)| < TOL} continuation used {@code A*B*lam} where upstream uses
 * {@code A*lam} (wrong by a factor of {@code B^2}); the main branch used
 * {@code atan(y/x)} plus {@code +pi} for {@code x < 0}, which is {@code atan2} only for
 * {@code y >= 0} and is out by {@code 2*pi*A/B} for {@code y < 0}; and the pole branch shared the
 * non-pole domain check, where upstream evaluates precomputed {@code v_pole_n}/{@code v_pole_s}.
 *
 * <h2>Known remaining gaps</h2>
 *
 * <p>{@code +no_off}, {@code +lon_1} and {@code +lon_2} used to be listed here. All three are now
 * {@code Proj4Keyword}s and all three are dispatched — {@code +no_off} through
 * {@link #setNoUoff(boolean)} (upstream ORs the two spellings, {@code omerc.cpp:139-143}) and the two
 * longitudes through {@link #setLon1(double)}/{@link #setLon2(double)}. Two remain:
 *
 * <ul>
 * <li>{@code +no_rot} has a setter ({@link #setNoRot(boolean)}) but is <b>not yet registered</b> as a
 *     {@code Proj4Keyword} and is not in the bridge's honoured set. Registering it without the setter
 *     would have been the dangerous order: the bridge would have called two {@code builtins.gie}
 *     blocks executable and they would have returned a <em>rotated</em> answer where PROJ returns an
 *     unrotated one. With the setter in place the key can now be added to {@code Proj4Keyword} and to
 *     the bridge's honoured set in one change.</li>
 * <li>A bare {@code +proj=omerc} with no {@code +alpha}, {@code +gamma}, {@code +lat_1} or
 *     {@code +lat_2} keeps the historic placeholder {@code alpha = -45} degrees rather than failing.
 *     Upstream rejects it ("lat_1 should be different from 0"). The placeholder cannot simply be
 *     removed: {@code Registry.getProjection} instantiates through the no-argument constructor
 *     <em>before</em> any parameter is set, so a throwing constructor would make every {@code omerc}
 *     definition unparseable.</li>
 * </ul>
 */
public class ObliqueMercatorProjection extends CylindricalProjection {

	private static final long serialVersionUID = -8211259010341855542L;

	private final static double TOL	= 1.0e-7;

	/** Upstream's {@code lamc}: the centre longitude, {@code +lonc}, defaulting to 0. */
	private double lamc;
	/** Upstream's {@code lam1}/{@code lam2}: {@code +lon_1}/{@code +lon_2}, defaulting to 0. */
	private double lam1, lam2;
	/** Upstream's {@code A}, {@code B}, {@code E}, {@code rB = 1/B}, {@code ArB}, {@code BrA}. */
	private double al, bl, el, rb, arb, bra;
	private double singam, cosgam, sinrot, cosrot, u_0, v_pole_n, v_pole_s;

	/**
	 * Upstream's {@code Q->rot}: whether to rotate the (u, v) frame by {@code gamma} on the way out.
	 * <p>
	 * {@code omerc.cpp:145} reads it as {@code !pj_param(..., "tno_rot").i}, so the default is
	 * {@code true} and {@code +no_rot} turns it off. It used to be assigned {@code rot = true}
	 * unconditionally inside {@link #initialize()} with no setter, which meant the parser had nowhere
	 * to deliver the key even if it recognised it. See {@link #setNoRot(boolean)}.
	 */
	private boolean rot = true;
	private boolean no_uoff;

	/**
	 * The {@code +gamma} parameter exactly as supplied, in radians, or {@code NaN} when it was not
	 * supplied. <b>The {@code NaN} is the whole point</b>: it is the only record that the parameter is
	 * absent, and when this field was a plain {@code double} defaulting to {@code 0.0} every
	 * {@code +alpha}-only definition silently lost its rotation. Never overwritten by
	 * {@link #initialize()} — the derived rotation angle is a local there, so a second
	 * {@code initialize()} sees the same inputs as the first.
	 */
	private double gamma = Double.NaN;

	/**
	 * Whether {@code +alpha} was set by a caller, as opposed to left at the no-argument
	 * constructor's placeholder. {@link #alpha} alone cannot answer that, because the placeholder is
	 * a real number.
	 */
	private boolean alphaExplicit;

	/** Whether {@code +lat_1}/{@code +lat_2} were set, i.e. whether the two-point form was asked for. */
	private boolean lat1Explicit, lat2Explicit;

	public ObliqueMercatorProjection() {
		ellipsoid = Ellipsoid.WGS84;
		projectionLatitude = ProjectionMath.toRad(0);
		projectionLongitude = ProjectionMath.toRad(0);
		minLongitude = ProjectionMath.toRad(-60);
		maxLongitude = ProjectionMath.toRad(60);
		minLatitude = ProjectionMath.toRad(-80);
		maxLatitude = ProjectionMath.toRad(80);
		// Placeholder, deliberately NOT marked explicit: it keeps this constructor - and therefore
		// Registry.getProjection("omerc") - from throwing, while still letting a later +gamma,
		// +lat_1 or +lat_2 take precedence over it. See the class javadoc.
		alpha = ProjectionMath.toRad(-45);
		initialize();
	}

	/**
	* Set up a projection suitable for State Plane Coordinates.
	*/
	public ObliqueMercatorProjection(Ellipsoid ellipsoid, double lon_0, double lat_0, double alpha, double k, double x_0, double y_0) {
		setEllipsoid(ellipsoid);
		// +lonc, not +lon_0: upstream derives lam0 from lonc and ignores lon_0 entirely, and
		// initialize() reads lonc. Assigning lamc here was pointless - initialize() overwrote it.
		lonc = lon_0;
		projectionLatitude = lat_0;
		this.alpha = alpha;
		this.alphaExplicit = true;
		scaleFactor = k;
		falseEasting = x_0;
		falseNorthing = y_0;
		initialize();
	}

	/**
	 * Port of {@code PJ_PROJECTION(omerc)} ({@code omerc.cpp:120-306}).
	 *
	 * @throws InvalidValueException for each of upstream's five parameter rejections
	 */
	public void initialize() {
		super.initialize();
		double con, com, cosphi0, d, f, h, l, sinphi0, p, j, gamma0;

		// `rot` is NOT reset here. It is a parameter, not a derived value: assigning it in
		// initialize() - which runs twice, once from a constructor and once from the parser - would
		// discard whatever setNoRot() had been told and make this method non-idempotent in the one
		// field a caller can actually set. Its default lives on the field declaration.

		// Upstream's `gam`/`alp`. Either one selects the azimuth form (omerc.cpp:135).
		final boolean gam = !Double.isNaN(gamma);
		final boolean twoPoint = lat1Explicit || lat2Explicit;
		// The placeholder alpha counts only when nothing else was given at all; see the class javadoc.
		final boolean alp = alphaExplicit || (!gam && !twoPoint);

		// Upstream's phi1/phi2, defaulting to 0 exactly as pj_param does for an absent key.
		final double phi1 = lat1Explicit ? projectionLatitude1 : 0.0;
		final double phi2 = lat2Explicit ? projectionLatitude2 : 0.0;

		// Upstream's alpha_c: the azimuth actually used, derived in the two-point and gamma-only
		// forms. A local, not the alpha field, so initialize() stays idempotent.
		double alphaC = alp ? alpha : 0.0;

		if (alp || gam) {
			// omerc.cpp:136. +lonc defaults to 0; +lon_0 is ignored (omerc.cpp:193-195).
			lamc = Double.isNaN(lonc) ? 0.0 : lonc;
		} else {
			// omerc.cpp:148-192. Upstream's four two-point rejections, in its order.
			if (Math.abs(phi1) > ProjectionMath.HALFPI - TOL) {
				throw new InvalidValueException(
						"Invalid value for +lat_1: |lat_1| should be < 90 degrees, but is "
								+ Math.toDegrees(phi1));
			}
			if (Math.abs(phi2) > ProjectionMath.HALFPI - TOL) {
				throw new InvalidValueException(
						"Invalid value for +lat_2: |lat_2| should be < 90 degrees, but is "
								+ Math.toDegrees(phi2));
			}
			if (Math.abs(phi1 - phi2) <= TOL) {
				throw new InvalidValueException(
						"Invalid value for +lat_1/+lat_2: lat_1 should be different from lat_2, but "
								+ "both are " + Math.toDegrees(phi1) + " degrees");
			}
			if (Math.abs(phi1) <= TOL) {
				throw new InvalidValueException(
						"Invalid value for +lat_1: lat_1 should be different from 0");
			}
			if (Math.abs(Math.abs(projectionLatitude) - ProjectionMath.HALFPI) <= TOL) {
				throw new InvalidValueException(
						"Invalid value for +lat_0: |lat_0| should be < 90 degrees, but is "
								+ Math.toDegrees(projectionLatitude));
			}
		}

		com = Math.sqrt(one_es);
		if (Math.abs(projectionLatitude) > EPS10) {
			sinphi0 = Math.sin(projectionLatitude);
			cosphi0 = Math.cos(projectionLatitude);
			con = 1. - es * sinphi0 * sinphi0;
			bl = cosphi0 * cosphi0;
			bl = Math.sqrt(1. + es * bl * bl / one_es);
			al = bl * scaleFactor * com / con;
			d = bl * com / (cosphi0 * Math.sqrt(con));
			if ((f = d * d - 1.) <= 0.)
				f = 0.;
			else {
				f = Math.sqrt(f);
				if (projectionLatitude < 0.)
					f = -f;
			}
			el = f += d;
			// tsfn degenerates to tan(pi/4 - phi/2) at e == 0, so upstream needs no spherical
			// branch here and neither does this.
			el *= Math.pow(ProjectionMath.tsfn(projectionLatitude, sinphi0, e), bl);
		} else {
			bl = 1. / com;
			al = scaleFactor;
			el = d = f = 1.;
		}
		if (alp || gam) {
			if (alp) {
				gamma0 = ProjectionMath.asinChecked(Math.sin(alphaC) / d);
			} else {
				gamma0 = gamma;
				// omerc.cpp:236-247: |gamma| must be <= asin(1/D), and upstream turns aasin's errno
				// into exactly this message.
				double sinAlphaC = d * Math.sin(gamma0);
				if (Math.abs(sinAlphaC) > ProjectionMath.ONE_TOL) {
					throw new InvalidValueException(
							"Invalid value for +gamma: given lat_0 = " + Math.toDegrees(projectionLatitude)
									+ " degrees, |gamma| should be <= "
									+ Math.toDegrees(Math.asin(1. / d)) + " degrees, but is "
									+ Math.toDegrees(gamma0));
				}
				alphaC = ProjectionMath.asinChecked(sinAlphaC);
			}

			if (Math.abs(Math.abs(projectionLatitude) - ProjectionMath.HALFPI) <= TOL) {
				throw new InvalidValueException(
						"Invalid value for +lat_0: |lat_0| should be < 90 degrees, but is "
								+ Math.toDegrees(projectionLatitude));
			}

			projectionLongitude = lamc
					- ProjectionMath.asinChecked(.5 * (f - 1. / f) * Math.tan(gamma0)) / bl;
		} else {
			h = Math.pow(ProjectionMath.tsfn(phi1, Math.sin(phi1), e), bl);
			l = Math.pow(ProjectionMath.tsfn(phi2, Math.sin(phi2), e), bl);
			f = el / h;
			p = (l - h) / (l + h);
			if (p == 0) {
				throw new InvalidValueException(
						"Invalid value for eccentricity: it is so close to 1 that the two-point "
								+ "centre line is undefined");
			}
			j = el * el;
			j = (j - l * h) / (j + l * h);
			double lam2Adj = lam2;
			if ((con = lam1 - lam2Adj) < -Math.PI)
				lam2Adj -= ProjectionMath.TWOPI;
			else if (con > Math.PI)
				lam2Adj += ProjectionMath.TWOPI;
			projectionLongitude = ProjectionMath.normalizeLongitude(.5 * (lam1 + lam2Adj) - Math.atan(
			   j * Math.tan(.5 * bl * (lam1 - lam2Adj)) / p) / bl);
			final double denom = f - 1. / f;
			if (denom == 0) {
				throw new InvalidValueException(
						"Invalid value for eccentricity: the two-point centre line azimuth is "
								+ "undefined");
			}
			gamma0 = Math.atan(2. * Math.sin(bl
					* ProjectionMath.normalizeLongitude(lam1 - projectionLongitude)) / denom);
			alphaC = ProjectionMath.asinChecked(d * Math.sin(gamma0));
		}

		// Upstream's `gamma`: the rotation angle. In the azimuth form with no +gamma it is alpha
		// (omerc.cpp:233-234 - the default this class used to have as dead code); in the two-point
		// form it is the derived azimuth (omerc.cpp:288).
		final double gammaRot = gam ? gamma : alphaC;

		singam = Math.sin(gamma0);
		cosgam = Math.cos(gamma0);
		sinrot = Math.sin(gammaRot);
		cosrot = Math.cos(gammaRot);
		// Grouped exactly as omerc.cpp:293 so the constants are bit-identical to upstream's.
		bra = 1. / (arb = al * (rb = 1. / bl));

		// omerc.cpp:294-300. cos(alphaC), NOT cos(gammaRot): the two coincide only when gamma was
		// omitted or equals alpha, which is why every in-repo omerc except RSO Borneo hid this.
		if (no_uoff)
			u_0 = 0.;
		else {
			u_0 = Math.abs(arb * Math.atan(Math.sqrt(d * d - 1.) / Math.cos(alphaC)));
			if (projectionLatitude < 0.)
				u_0 = -u_0;
		}
		f = 0.5 * gamma0;
		v_pole_n = arb * Math.log(Math.tan(ProjectionMath.QUARTERPI - f));
		v_pole_s = arb * Math.log(Math.tan(ProjectionMath.QUARTERPI + f));
	}

    /** {@code +gamma}, in radians. */
    @Override public void setGamma(double gamma) {
        this.gamma = gamma;
    }

    /** {@code +no_off} / {@code +no_uoff}. */
    @Override public void setNoUoff(boolean no_uoff) {
    	this.no_uoff = no_uoff;
    }

    /**
     * {@code +no_rot}: do <b>not</b> rotate the (u, v) frame by {@code gamma} on output.
     * <p>
     * {@code omerc.cpp:145} is {@code Q->rot = !pj_param(P->ctx, P->params, "tno_rot").i}, i.e. a
     * presence flag that <em>disables</em> the rotation, which is why this setter's argument is
     * negated into {@link #rot}.
     * <p>
     * <b>The key is not registered yet, and the ordering was deliberate.</b> A dispatch stream
     * declared {@code Proj4Keyword.no_rot} but did not add it to the allow-list or to the bridge's
     * honoured set, because without this setter the parser would have had nothing to call: the bridge
     * would then have classified the two {@code +no_rot} corpus blocks as executable and they would
     * have returned a rotated answer where PROJ returns an unrotated one — a plausible wrong
     * coordinate instead of an honest "not implemented". With the setter present, the key can be
     * registered in {@code Proj4Keyword} and added to the bridge's honoured set in one change.
     *
     * @param noRot {@code true} to suppress the rotation
     */
    public void setNoRot(boolean noRot) {
        this.rot = !noRot;
    }

    /** @return whether the (u, v) frame is rotated by {@code gamma}; {@code true} unless {@code +no_rot}. */
    public boolean isRot() {
        return rot;
    }

    @Override public void setAlpha(double alpha) {
        super.setAlpha(alpha);
        this.alphaExplicit = true;
    }

    @Override public void setAlphaDegrees(double alpha) {
        super.setAlphaDegrees(alpha);
        this.alphaExplicit = true;
    }

    @Override public void setProjectionLatitude1(double projectionLatitude1) {
        super.setProjectionLatitude1(projectionLatitude1);
        this.lat1Explicit = true;
    }

    @Override public void setProjectionLatitude1Degrees(double projectionLatitude1) {
        super.setProjectionLatitude1Degrees(projectionLatitude1);
        this.lat1Explicit = true;
    }

    @Override public void setProjectionLatitude2(double projectionLatitude2) {
        super.setProjectionLatitude2(projectionLatitude2);
        this.lat2Explicit = true;
    }

    @Override public void setProjectionLatitude2Degrees(double projectionLatitude2) {
        super.setProjectionLatitude2Degrees(projectionLatitude2);
        this.lat2Explicit = true;
    }

    /** {@code +lon_1}, in radians. Not reachable from {@code Proj4Parser}; defaults to 0. */
    public void setLon1(double lon1) {
        this.lam1 = lon1;
    }

    /** {@code +lon_2}, in radians. Not reachable from {@code Proj4Parser}; defaults to 0. */
    public void setLon2(double lon2) {
        this.lam2 = lon2;
    }

	/** Port of {@code omerc_e_forward} ({@code omerc.cpp:45-81}). */
	public ProjCoordinate project(double lam, double phi, ProjCoordinate xy) {
		double u, v;

		if (Math.abs(Math.abs(phi) - ProjectionMath.HALFPI) > EPS10) {
			final double w = el / Math.pow(ProjectionMath.tsfn(phi, Math.sin(phi), e), bl);
			final double oneDivW = 1. / w;
			final double s = .5 * (w - oneDivW);
			final double t = .5 * (w + oneDivW);
			final double vl = Math.sin(bl * lam);
			final double ul = (s * singam - vl * cosgam) / t;
			if (Math.abs(Math.abs(ul) - 1.0) < EPS10) {
				throw new ProjectionException(ErrorCause.COORDINATE_OUT_OF_DOMAIN, this,
						"omerc: the point lies on the projection's singular line (|U| = 1)");
			}
			v = 0.5 * arb * Math.log((1. - ul) / (1. + ul));
			final double temp = Math.cos(bl * lam);
			if (Math.abs(temp) < TOL) {
				// omerc.cpp:64: A*lam, not A*B*lam. At |cos(B*lam)| < TOL the main branch tends to
				// (A/B)*(+-pi/2) and lam tends to +-pi/(2B), so A*lam is its continuation and the
				// extra factor of B was simply wrong.
				u = al * lam;
			} else {
				// omerc.cpp:66: atan2. atan(y/x) + pi is atan2 only for y >= 0; for y < 0 and
				// x < 0 the old form was out by 2*pi*A/B.
				u = arb * Math.atan2(s * cosgam + vl * singam, temp);
			}
		} else {
			v = phi > 0 ? v_pole_n : v_pole_s;
			u = arb * phi;
		}
		if (!rot) {
			xy.x = u;
			xy.y = v;
		} else {
			u -= u_0;
			xy.x = v * cosrot + u * sinrot;
			xy.y = u * cosrot - v * sinrot;
		}
		return xy;
	}

	/** Port of {@code omerc_e_inverse} ({@code omerc.cpp:83-118}). */
	public ProjCoordinate projectInverse(double x, double y, ProjCoordinate lp) {
		double u, v;

		if (!rot) {
			v = y;
			u = x;
		} else {
			v = x * cosrot - y * sinrot;
			u = y * cosrot + x * sinrot + u_0;
		}
		final double qp = Math.exp(-bra * v);
		if (qp == 0) {
			throw new ProjectionException(ErrorCause.COORDINATE_OUT_OF_DOMAIN, this,
					"omerc inverse: exp(-B/A * v) underflowed to zero");
		}
		final double sp = .5 * (qp - 1. / qp);
		final double tp = .5 * (qp + 1. / qp);
		final double vp = Math.sin(bra * u);
		final double up = (vp * cosgam + sp * singam) / tp;
		if (Math.abs(Math.abs(up) - 1.) < EPS10) {
			lp.x = 0.;
			lp.y = up < 0. ? -ProjectionMath.HALFPI : ProjectionMath.HALFPI;
		} else {
			lp.y = el / Math.sqrt((1. + up) / (1. - up));
			lp.y = ProjectionMath.phi2(Math.pow(lp.y, 1. / bl), e);
			lp.x = -rb * Math.atan2(sp * cosgam - vp * singam, Math.cos(bra * u));
		}
		return lp;
	}

	public boolean hasInverse() {
		return true;
	}

	public String toString() {
		return "Oblique Mercator";
	}

	@Override
	public boolean equals(Object that) {
			if (this == that) {
					return true;
			}
			if (that instanceof ObliqueMercatorProjection) {
					ObliqueMercatorProjection p = (ObliqueMercatorProjection) that;
					return (
						// Double.compare: gamma is NaN when +gamma was absent, and two definitions
						// that both omitted it must still compare equal.
						Double.compare(gamma, p.gamma) == 0 &&
						Double.compare(alpha, p.alpha) == 0 &&
						Double.compare(lonc, p.lonc) == 0 &&
						// u_0 depends on no_uoff, so omitting it made two omerc projections that
						// differ by 5,000 km of false origin handling compare equal.
						no_uoff == p.no_uoff &&
						lam1 == p.lam1 &&
						lam2 == p.lam2 &&
						super.equals(that));
			}
			return false;
	}

	@Override
	public int hashCode() {
			return Objects.hash(gamma, alpha, lonc, no_uoff, lam1, lam2, super.hashCode());
	}
}
