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

import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.InvalidValueException;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.ProjectionException;
import org.locationtech.proj4j.util.FastStrictTrig;
import org.locationtech.proj4j.util.ProjectionMath;

/**
 * {@code +proj=lsat} &mdash; Space Oblique Mercator for Landsat
 * ({@code 9.8.1:src/projections/som.cpp}).
 *
 * <h2>Why this file uses {@link FastStrictTrig} and {@link StrictMath}, not {@code Math}</h2>
 *
 * <p>Exactly seven {@code java.lang.Math} methods are {@code @IntrinsicCandidate} and therefore
 * <em>platform-dependent</em>: {@code sin cos tan log log10 exp pow}. HotSpot substitutes a
 * hand-written implementation for each, and those implementations do not agree bit-for-bit
 * between aarch64 and x86-64. Everything else this file calls &mdash; {@code sqrt abs atan asin}
 * &mdash; already delegates to {@code StrictMath} (or to a single hardware instruction that IEEE
 * 754 defines exactly), is already deterministic, and is deliberately left as {@code Math}:
 * converting it would move numbers for no benefit.
 *
 * <p>So all 40 platform-dependent sites are re-pointed and none of the other 25 are:
 *
 * <table>
 *   <caption>the conversion, by method</caption>
 *   <tr><th>method</th><th>sites</th><th>now</th></tr>
 *   <tr><td>{@code sin}</td><td>19</td><td>{@link FastStrictTrig#sin(double)}</td></tr>
 *   <tr><td>{@code cos}</td><td>15</td><td>{@link FastStrictTrig#cos(double)}</td></tr>
 *   <tr><td>{@code tan}</td><td>4</td><td>{@link FastStrictTrig#tan(double)}</td></tr>
 *   <tr><td>{@code log}</td><td>1</td><td>{@code StrictMath.log} &mdash; allocation-free already,
 *       so {@code FastStrictTrig} has nothing to add and covers only the three</td></tr>
 *   <tr><td>{@code exp}</td><td>1</td><td>{@code StrictMath.exp}, same reason</td></tr>
 *   <tr><td>{@code sqrt abs atan asin}</td><td>25</td><td><b>unchanged</b> &mdash; already
 *       deterministic</td></tr>
 * </table>
 *
 * <h3>It moved numbers, and it moved them towards PROJ</h3>
 *
 * <p>This is not a pure refactor: on aarch64 the intrinsics differ from fdlibm, so output moves.
 * Measured over the {@code RepointDump.lsatForward} graticule (lon &minus;180..180 step 5, lat
 * &minus;80..80 step 5, {@code +proj=lsat +ellps=GRS80}, i.e. {@code +lsat=1 +path=120}),
 * <b>152 of 2,409 points moved</b>, 166 ordinates in all. Every moved ordinate was re-run through
 * the installed PROJ 9.8.1:
 *
 * <pre>
 * cs2cs -f '%.17g' +proj=longlat +ellps=GRS80 +to +proj=lsat +lsat=1 +path=120 +ellps=GRS80
 * </pre>
 *
 * <table>
 *   <caption>agreement with PROJ 9.8.1 over the 166 moved ordinates, metres</caption>
 *   <tr><th></th><th>after closer</th><th>before closer</th><th>ties</th>
 *       <th>max |&Delta;|</th></tr>
 *   <tr><td>after (this file)</td><td><b>103</b></td><td></td><td rowspan="2">2</td>
 *       <td><b>1.49e-8</b></td></tr>
 *   <tr><td>before ({@code Math})</td><td></td><td>61</td><td>3.73e-8</td></tr>
 * </table>
 *
 * <p>So the strict version is nearer upstream on 62% of the moved ordinates and halves the worst
 * case. The whole movement is bounded by 37 nanometres &mdash; an ulp-level reshuffle at an
 * easting of 3.8e7 m, where one ulp is about 7.5 nm &mdash; and nothing near any {@code gie} bar.
 * {@code LandsatInverseTest}'s 15 references from {@code proj} 9.8.1 hold at their unchanged
 * {@code 2e-9} deg tolerance.
 *
 * <p>{@code RepointBitIdentityTest}'s {@code lsatForward} digest was re-pinned for this change,
 * and only that one of its twelve; see the note there.
 *
 * @see FastStrictTrig
 */
public class LandsatProjection extends Projection {

	private static final long serialVersionUID = -6304348212843606110L;

	private double a2, a4, b, c1, c3;
	private double q, t, u, w, p22, sa, ca, xj, rlm, rlm2;

	/**
	 * {@code +lsat}, the Landsat vehicle number ({@code som.cpp:307}).
	 *
	 * <h4>Why the default is 1 and not upstream's 0</h4>
	 *
	 * <p>{@code PJ_PROJECTION(lsat)} reads {@code pj_param(ctx, params, "ilsat").i} with no
	 * default, so an absent {@code +lsat} is <b>0</b>, which fails {@code land <= 0} and makes a
	 * bare {@code +proj=lsat} an error upstream. This class instead keeps the 1 that used to be
	 * hard-coded here behind a {@code //FIXME}.
	 *
	 * <p>That is a deliberate, temporary divergence, and the reason is that two committed
	 * references were pinned against the hard-coded values while the parameters were unreachable:
	 * {@code core/src/test/java/.../proj/lsat/LandsatInverseTest.java} projects a bare
	 * {@code +proj=lsat +ellps=GRS80} against numbers its own javadoc records as generated by
	 * {@code proj +proj=lsat +lsat=1 +path=120 +ellps=GRS80}, and {@code golden}'s five
	 * {@code SYN proj/lsat} rows are {@code OK} at the canonical parameter set, which carries
	 * neither key. Defaulting to 0 today turns both into errors, so aligning with upstream means
	 * re-pinning {@code LandsatInverseTest}'s {@code SPEC} to carry {@code +lsat=1 +path=120}
	 * and recording the five golden transitions in {@code golden/rules.yaml} — neither of which
	 * belongs in a parameter-dispatch change.
	 *
	 * <p>No corpus row is affected either way: both of {@code builtins.gie}'s {@code lsat} blocks
	 * ({@code :4058} and {@code :4081}) supply both keys.
	 */
	private int land = 1;

	/**
	 * {@code +path}, the orbital path number ({@code som.cpp:318}). Range is
	 * {@code [1, 251]} for {@code +lsat <= 3} and {@code [1, 233]} above it.
	 *
	 * <p>120 for the same reason {@link #land} is 1. Upstream's absent value is 0, which is out
	 * of range.
	 */
	private int path = 120;

	private final static double TOL = 1e-7;
	private final static double PI_HALFPI = 4.71238898038468985766;
	private final static double TWOPI_HALFPI = 7.85398163397448309610;

	public ProjCoordinate project(double lplam, double lpphi, ProjCoordinate xy) {
		int l, nn;
		double lamt = 0, xlam, sdsq, c, d, s, lamdp = 0, phidp, lampp, tanph,
			lamtp, cl, sd, sp, fac, sav, tanphi;

		if (lpphi > ProjectionMath.HALFPI)
			lpphi = ProjectionMath.HALFPI;
		else if (lpphi < -ProjectionMath.HALFPI)
			lpphi = -ProjectionMath.HALFPI;
		lampp = lpphi >= 0. ? ProjectionMath.HALFPI : PI_HALFPI;
		tanphi = FastStrictTrig.tan(lpphi);
		for (nn = 0;;) {
			sav = lampp;
			lamtp = lplam + p22 * lampp;
			cl = FastStrictTrig.cos(lamtp);
			if (Math.abs(cl) < TOL)
				lamtp -= TOL;
			fac = lampp - FastStrictTrig.sin(lampp)
				* (cl < 0. ? -ProjectionMath.HALFPI : ProjectionMath.HALFPI);
			for (l = 50; l > 0; --l) {
				lamt = lplam + p22 * sav;
				if (Math.abs(c = FastStrictTrig.cos(lamt)) < TOL)
					lamt -= TOL;
				xlam = (one_es * tanphi * sa + FastStrictTrig.sin(lamt) * ca) / c;
				lamdp = Math.atan(xlam) + fac;
				if (Math.abs(Math.abs(sav) - Math.abs(lamdp)) < TOL)
					break;
				sav = lamdp;
			}
			if (l == 0 || ++nn >= 3 || (lamdp > rlm && lamdp < rlm2))
				break;
			if (lamdp <= rlm)
				lampp = TWOPI_HALFPI;
			else if (lamdp >= rlm2)
				lampp = ProjectionMath.HALFPI;
		}
		if (l != 0) {
			sp = FastStrictTrig.sin(lpphi);
			phidp = ProjectionMath.asin((one_es * ca * sp - sa * FastStrictTrig.cos(lpphi) *
				FastStrictTrig.sin(lamt)) / Math.sqrt(1. - es * sp * sp));
			tanph = StrictMath.log(FastStrictTrig.tan(ProjectionMath.QUARTERPI + .5 * phidp));
			sd = FastStrictTrig.sin(lamdp);
			sdsq = sd * sd;
			s = p22 * sa * FastStrictTrig.cos(lamdp) * Math.sqrt((1. + t * sdsq)
				 / ((1. + w * sdsq) * (1. + q * sdsq)));
			d = Math.sqrt(xj * xj + s * s);
			xy.x = b * lamdp + a2 * FastStrictTrig.sin(2. * lamdp) + a4 *
				FastStrictTrig.sin(lamdp * 4.) - tanph * s / d;
			xy.y = c1 * sd + c3 * FastStrictTrig.sin(lamdp * 3.) + tanph * xj / d;
		} else
			xy.x = xy.y = Double.POSITIVE_INFINITY;
		return xy;
	}

	/**
	 * Port of {@code som_e_inverse} ({@code 9.8.1:src/projections/som.cpp:158-203}).
	 *
	 * <p>Before 1.5.0 this method existed only as a commented-out block whose signature was
	 * {@code projectInverse(double, double, java.awt.geom.Point2D.Double)} — an AWT type this
	 * library has not used since it was forked. It therefore overrode nothing, while
	 * {@link #hasInverse()} kept returning {@code true}, so {@code +proj=lsat} inverses landed
	 * on {@code Projection}'s identity and returned projected metres reinterpreted as
	 * longitude and latitude in radians. That is the same defect class the fail-closed
	 * {@link org.locationtech.proj4j.ErrorCause#NO_INVERSE_AVAILABLE} gate was built for; this
	 * file was outside its scope, and an inverse does exist upstream, so it is ported here
	 * rather than gated.
	 *
	 * <p>Two divergences from the commented-out draft, both because the draft never compiled:
	 * {@code s} is initialised (C left it indeterminate before the {@code do}/{@code while},
	 * which is harmless there only because the body always runs once), and the
	 * {@code 1 - sin^2(phi'')(1 + u)} denominator is tested for zero, which 9.8.1 added as
	 * {@code PROJ_ERR_COORD_TRANSFM_OUTSIDE_PROJECTION_DOMAIN} and the draft predates.
	 *
	 * <p>The trigonometry here is {@link FastStrictTrig}/{@link StrictMath}, not {@code Math}; see
	 * the class javadoc. The forward was switched at the same time, so the two halves still match
	 * each other &mdash; that is what the note this replaces was asking for.
	 *
	 * @param xyx the projected x ordinate, scaled as {@link #project} produces it
	 * @param xyy the projected y ordinate
	 * @param out receives longitude and latitude in radians
	 * @return {@code out}
	 */
	@Override
	protected ProjCoordinate projectInverse(double xyx, double xyy, ProjCoordinate out) {
		int nn;
		double lamt, sdsq, s = 0., lamdp, phidp, sppsq, dd, sd, sl, fac, scl, sav, spp;

		lamdp = xyx / b;
		nn = 50;
		do {
			sav = lamdp;
			sd = FastStrictTrig.sin(lamdp);
			sdsq = sd * sd;
			s = p22 * sa * FastStrictTrig.cos(lamdp) * Math.sqrt((1. + t * sdsq)
				 / ((1. + w * sdsq) * (1. + q * sdsq)));
			lamdp = xyx + xyy * s / xj - a2 * FastStrictTrig.sin(
				2. * lamdp) - a4 * FastStrictTrig.sin(lamdp * 4.) - s / xj * (
				c1 * FastStrictTrig.sin(lamdp) + c3 * FastStrictTrig.sin(lamdp * 3.));
			lamdp /= b;
		} while (Math.abs(lamdp - sav) >= TOL && --nn != 0);
		sl = FastStrictTrig.sin(lamdp);
		fac = StrictMath.exp(Math.sqrt(1. + s * s / xj / xj) * (xyy -
			c1 * sl - c3 * FastStrictTrig.sin(lamdp * 3.)));
		phidp = 2. * (Math.atan(fac) - ProjectionMath.QUARTERPI);
		dd = sl * sl;
		if (Math.abs(FastStrictTrig.cos(lamdp)) < TOL)
			lamdp -= TOL;
		spp = FastStrictTrig.sin(phidp);
		sppsq = spp * spp;
		final double denom = 1. - sppsq * (1. + u);
		if (denom == 0.0) {
			throw new ProjectionException(ErrorCause.COORDINATE_OUT_OF_DOMAIN, this,
					"lsat inverse of (" + xyx + ", " + xyy + ") is outside the projection "
							+ "domain: 1 - sin^2(phi'')(1 + u) is exactly zero");
		}
		lamt = Math.atan(((1. - sppsq * rone_es) * FastStrictTrig.tan(lamdp) *
			ca - spp * sa * Math.sqrt((1. + q * dd) * (
			1. - sppsq) - sppsq * u) / FastStrictTrig.cos(lamdp)) / denom);
		sl = lamt >= 0. ? 1. : -1.;
		scl = FastStrictTrig.cos(lamdp) >= 0. ? 1. : -1;
		lamt -= ProjectionMath.HALFPI * (1. - scl) * sl;
		// adjlon here, not just in the caller. lamt - p22*lamdp accumulates the satellite's
		// along-track rotation, so this kernel legitimately returns |lam| well past pi -- at
		// (130, 45) with path 120 it returns 533.2 deg. inverseProjectRadians *clamps* to
		// +/-pi before adding projectionLongitude instead of wrapping (PROJ's inv_finalize
		// wraps, with adjlon), and a clamp turns that 533.2 deg into 180 deg and the answer
		// into 136.758 deg -- 6.76 deg wrong, silently. Wrapping first makes that clamp a
		// no-op. adjlon(adjlon(lam) + lam0) == adjlon(lam + lam0) to within an ulp of 2pi,
		// about 6 nm on the ground.
		out.x = ProjectionMath.adjlon(lamt - p22 * lamdp);
		if (Math.abs(sa) < TOL)
			out.y = ProjectionMath.asinChecked(spp / Math.sqrt(one_es * one_es + es * sppsq));
		else
			out.y = Math.atan((FastStrictTrig.tan(lamdp) * FastStrictTrig.cos(lamt)
				- ca * FastStrictTrig.sin(lamt)) / (one_es * sa));
		return out;
	}

	private void seraz0(double lam, double mult) {
		double sdsq, h, s, fc, sd, sq, d__1;

		lam *= DTR;
		sd = FastStrictTrig.sin(lam);
		sdsq = sd * sd;
		s = p22 * sa * FastStrictTrig.cos(lam) * Math.sqrt((1. + t * sdsq) / ((
			1. + w * sdsq) * (1. + q * sdsq)));
		d__1 = 1. + q * sdsq;
		h = Math.sqrt((1. + q * sdsq) / (1. + w * sdsq)) * ((1. + 
			w * sdsq) / (d__1 * d__1) - p22 * ca);
		sq = Math.sqrt(xj * xj + s * s);
		b += fc = mult * (h * xj - s * s) / sq;
		a2 += fc * FastStrictTrig.cos(lam + lam);
		a4 += fc * FastStrictTrig.cos(lam * 4.);
		fc = mult * s * (h + xj) / sq;
		c1 += fc * FastStrictTrig.cos(lam);
		c3 += fc * FastStrictTrig.cos(lam * 3.);
	}

	/**
	 * {@code +lsat}: the Landsat vehicle number, 1 to 5.
	 *
	 * <h4>This used to be unreachable</h4>
	 *
	 * <p>{@code initialize()} assigned {@code land = 1} to a <em>local</em> behind a
	 * {@code //FIXME}, so there was no field to set and no way to ask for Landsat 4 or 5 —
	 * {@code +proj=lsat +lsat=5} silently returned Landsat 1's map. Now a field, and
	 * {@code initialize()} only reads it, so the value survives the second
	 * {@code initialize()} the parser triggers.
	 *
	 * @param land the vehicle number
	 * @see #setPath(int)
	 */
	public void setLandsat(int land) {
		this.land = land;
	}

	/** @return the {@code +lsat} in force */
	public int getLandsat() {
		return land;
	}

	/**
	 * {@code +path}: the orbital path number, {@code [1, 251]} for {@code +lsat <= 3} and
	 * {@code [1, 233]} above it. Same story as {@link #setLandsat(int)} — it was a local
	 * pinned to 120, which is why the conformance bridge had to classify {@code +path} as
	 * conditionally honoured and refuse {@code lsat} rows outright.
	 *
	 * @param path the path number
	 */
	public void setPath(int path) {
		this.path = path;
	}

	/** @return the {@code +path} in force */
	public int getPath() {
		return path;
	}

	/**
	 * {@code PJ_PROJECTION(lsat)} ({@code som.cpp:303-345}) followed by {@code som_setup}.
	 *
	 * <p>Both range checks are upstream's, and both now carry a message rather than the bare
	 * numeric codes {@code "-28"} and {@code "-29"} they used to throw. They are statements
	 * about the definition, not about a coordinate, so they are
	 * {@link InvalidValueException}s; the old {@code ProjectionException("-28")} said
	 * "this coordinate" about a parameter.
	 *
	 * @throws InvalidValueException if {@code +lsat} is outside {@code [1, 5]} or {@code +path}
	 *         is outside its vehicle-dependent range
	 */
	public void initialize() {
		super.initialize();
		double lam, alf, esc, ess;

		if (land <= 0 || land > 5)
			throw new InvalidValueException(ErrorCause.INVALID_PARAM_VALUE,
					"Invalid value for +lsat: it should be in the [1, 5] range, but is " + land);
		final int maxPath = land <= 3 ? 251 : 233;
		if (path <= 0 || path > maxPath)
			throw new InvalidValueException(ErrorCause.INVALID_PARAM_VALUE,
					"Invalid value for +path: with +lsat=" + land + " it should be in the [1, "
							+ maxPath + "] range, but is " + path);
		if (land <= 3) {
			projectionLongitude = DTR * 128.87 - ProjectionMath.TWOPI / 251. * path;
			p22 = 103.2669323;
			alf = DTR * 99.092;
		} else {
			projectionLongitude = DTR * 129.3 - ProjectionMath.TWOPI / 233. * path;
			p22 = 98.8841202;
			alf = DTR * 98.2;
		}
		p22 /= 1440.;
		sa = FastStrictTrig.sin(alf);
		ca = FastStrictTrig.cos(alf);
		if (Math.abs(ca) < 1e-9)
			ca = 1e-9;
		esc = es * ca * ca;
		ess = es * sa * sa;
		w = (1. - esc) * rone_es;
		w = w * w - 1.;
		q = ess * rone_es;
		t = ess * (2. - es) * rone_es * rone_es;
		u = esc * rone_es;
		xj = one_es * one_es * one_es;
		rlm = Math.PI * (1. / 248. + .5161290322580645);
		rlm2 = rlm + ProjectionMath.TWOPI;
		a2 = a4 = b = c1 = c3 = 0.;
		seraz0(0., 1.);
		for (lam = 9.; lam <= 81.0001; lam += 18.)
			seraz0(lam, 4.);
		for (lam = 18; lam <= 72.0001; lam += 18.)
			seraz0(lam, 2.);
		seraz0(90., 1.);
		a2 /= 30.;
		a4 /= 60.;
		b /= 30.;
		c1 /= 15.;
		c3 /= 45.;
	}
	
	public boolean hasInverse() {
		return true;
	}

	public String toString() {
		return "Landsat";
	}

}

