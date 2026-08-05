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
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.ProjectionException;
import org.locationtech.proj4j.util.FastStrictTrig;
import org.locationtech.proj4j.util.ProjectionMath;

/**
 * Bipolar conic of the western hemisphere ({@code +proj=bipc}), ported from
 * {@code 9.8.1:src/projections/bipc.cpp}.
 *
 * <p>All eighteen constants below are byte-identical to upstream's, and always were. The
 * {@code builtins.gie} failures came from three things that are not arithmetic at all:
 *
 * <ol>
 * <li><b>A {@code lon_0} of &minus;90&deg; that PROJ does not have.</b> {@code PJ_PROJECTION(bipc)}
 *     never assigns {@code P->lam0}, so it stays 0. This constructor set
 *     {@code projectionLongitude = -pi/2}, and {@link Projection#project} then handed
 *     {@link #project} {@code lam + 90deg} — pushing the point out of the western hemisphere, so
 *     {@code z > R104} and the {@code al < 0} guard fired. With {@code +a=6400000},
 *     {@code (2, ±1)} <b>threw</b> and {@code (-2, ±1)} returned finite values wrong by
 *     <b>1.06e7 m</b>.</li>
 * <li><b>{@code initialize()} omitted {@code es = 0}.</b> Upstream sets it, which is why
 *     {@code +proj=bipc +ellps=GRS80} and {@code +proj=bipc +a=6400000} differ in the corpus by
 *     nothing but the ratio of their semi-major axes. Without it the ellipsoidal
 *     {@code totalScale}/{@code one_es} state is live on a projection whose kernel is purely
 *     spherical.</li>
 * <li><b>The inverse's recentring was dead code.</b> Upstream reassigns {@code xy.y} in place —
 *     {@code xy.y = rhoc - xy.y} or {@code xy.y += rhoc} — and then computes
 *     {@code r = hypot(xy.x, xy.y)} and {@code Az = atan2(xy.x, xy.y)} from the <em>shifted</em>
 *     value. The old translation wrote the shift into the <em>output</em> coordinate and then read
 *     the unshifted inputs for both {@code r} and {@code Az}, so the {@code rhoc} translation never
 *     reached the arithmetic. In the {@code !noskew} case it was worse than dead: {@code out.y +=
 *     rhoc} incremented whatever the caller happened to have left in the output object. The
 *     {@code noskew} pre-rotation had the identical defect. Latitudes came back wrong by roughly
 *     57&deg;.</li>
 * </ol>
 *
 * <p>{@code +ns} (upstream's {@code noskew}) is still not a {@code Proj4Keyword}, so
 * {@link #setNoskew} exists for the parser to reach but nothing calls it yet; the corpus's two
 * {@code bipc} blocks do not use it.
 */
public class BipolarProjection extends Projection {

	private static final long serialVersionUID = 3539379562111642231L;

	private boolean	noskew;

	private final static double EPS = 1e-10;
	private final static double EPS10 = 1e-10;
	private final static double ONEEPS = 1.000000001;
	private final static int NITER = 10;
	private final static double lamB = -.34894976726250681539;
	private final static double n = .63055844881274687180;
	private final static double F = 1.89724742567461030582;
	private final static double Azab = .81650043674686363166;
	private final static double Azba = 1.82261843856185925133;
	private final static double T = 1.27246578267089012270;
	private final static double rhoc = 1.20709121521568721927;
	private final static double cAzc = .69691523038678375519;
	private final static double sAzc = .71715351331143607555;
	private final static double C45 = .70710678118654752469;
	private final static double S45 = .70710678118654752410;
	private final static double C20 = .93969262078590838411;
	private final static double S20 = -.34202014332566873287;
	private final static double R110 = 1.91986217719376253360;
	private final static double R104 = 1.81514242207410275904;

	public BipolarProjection() {
		minLatitude = ProjectionMath.toRad(-80);
		maxLatitude = ProjectionMath.toRad(80);
		// NO projectionLongitude here: PJ_PROJECTION(bipc) never sets P->lam0. See the class javadoc.
		minLongitude = ProjectionMath.toRad(-90);
		maxLongitude = ProjectionMath.toRad(90);
	}

	public ProjCoordinate project(double lplam, double lpphi, ProjCoordinate out) {
		double cphi, sphi, tphi, t, al, Az, z, Av, cdlam, sdlam, r;
		boolean tag;

		cphi = FastStrictTrig.cos(lpphi);
		sphi = FastStrictTrig.sin(lpphi);
		cdlam = FastStrictTrig.cos(sdlam = lamB - lplam);
		sdlam = FastStrictTrig.sin(sdlam);
		if (Math.abs(Math.abs(lpphi) - ProjectionMath.HALFPI) < EPS10) {
			Az = lpphi < 0. ? Math.PI : 0.;
			tphi = Double.POSITIVE_INFINITY;
		} else {
			tphi = sphi / cphi;
			Az = Math.atan2(sdlam , C45 * (tphi - cdlam));
		}
		if (tag = (Az > Azba)) {
			cdlam = FastStrictTrig.cos(sdlam = lplam + R110);
			sdlam = FastStrictTrig.sin(sdlam);
			z = S20 * sphi + C20 * cphi * cdlam;
			if (Math.abs(z) > 1.) {
				if (Math.abs(z) > ONEEPS)
					throw outsideDomain("forward", lplam, lpphi, "|cos(z)| = " + Math.abs(z)
							+ " exceeds " + ONEEPS + " on the Azab branch");
				else z = z < 0. ? -1. : 1.;
			} else
				z = Math.acos(z);
			if (!Double.isInfinite(tphi))
				Az = Math.atan2(sdlam, (C20 * tphi - S20 * cdlam));
			Av = Azab;
			out.y = rhoc;
		} else {
			z = S45 * (sphi + cphi * cdlam);
			if (Math.abs(z) > 1.) {
				if (Math.abs(z) > ONEEPS)
					throw outsideDomain("forward", lplam, lpphi, "|cos(z)| = " + Math.abs(z)
							+ " exceeds " + ONEEPS + " on the Azba branch");
				else z = z < 0. ? -1. : 1.;
			} else
				z = Math.acos(z);
			Av = Azba;
			out.y = -rhoc;
		}
		if (z < 0.)
			throw outsideDomain("forward", lplam, lpphi, "the polar angle z = " + z
					+ " is negative");
		r = F * (t = Math.pow(Math.tan(.5 * z), n));
		if ((al = .5 * (R104 - z)) < 0.)
			throw outsideDomain("forward", lplam, lpphi, "z = " + z
					+ " rad is beyond R104 = " + R104 + ", i.e. outside the mapped hemisphere");
		al = (t + Math.pow(al, n)) / T;
		if (Math.abs(al) > 1.) {
			if (Math.abs(al) > ONEEPS)
				throw outsideDomain("forward", lplam, lpphi, "|cos(alpha)| = " + Math.abs(al)
						+ " exceeds " + ONEEPS);
			else al = al < 0. ? -1. : 1.;
		} else
			al = Math.acos(al);
		if (Math.abs(t = n * (Av - Az)) < al)
			r /= FastStrictTrig.cos(al + (tag ? t : -t));
		out.x = r * FastStrictTrig.sin(t);
		out.y += (tag ? -r : r) * FastStrictTrig.cos(t);
		if (noskew) {
			t = out.x;
			out.x = -out.x * cAzc - out.y * sAzc;
			out.y = -out.y * cAzc + t * sAzc;
		}
		return out;
	}

	/**
	 * Inverse projection, {@code bipc_s_inverse}.
	 * <p>
	 * <b>The two recentrings are in-place reassignments of the working coordinate, not writes to
	 * the output.</b> Upstream mutates {@code xy} and then reads it back for {@code hypot} and
	 * {@code atan2}; the previous translation wrote to {@code out} and read the unshifted inputs,
	 * which made the whole {@code rhoc} translation dead and left {@code out.y += rhoc} reading
	 * whatever the caller's output object already held. Hence the two locals.
	 */
	public ProjCoordinate projectInverse(double xyx, double xyy, ProjCoordinate out) {
		double t, r, rp, rl, al, z = 0, fAz, Az, s, c, Av;
		boolean neg;
		int i;

		double x = xyx;
		double y = xyy;
		if (noskew) {
			t = x;
			x = -x * cAzc + y * sAzc;
			y = -y * cAzc - t * sAzc;
		}
		if (neg = (x < 0.)) {
			y = rhoc - y;
			s = S20;
			c = C20;
			Av = Azab;
		} else {
			y += rhoc;
			s = S45;
			c = C45;
			Av = Azba;
		}
		rl = rp = r = ProjectionMath.distance(x, y);
		fAz = Math.abs(Az = Math.atan2(x, y));
		for (i = NITER; i > 0; --i) {
			z = 2. * Math.atan(Math.pow(r / F,1 / n));
			al = Math.acos((Math.pow(Math.tan(.5 * z), n) +
			   Math.pow(Math.tan(.5 * (R104 - z)), n)) / T);
			if (fAz < al)
				r = rp * FastStrictTrig.cos(al + (neg ? Az : -Az));
			if (Math.abs(rl - r) < EPS)
				break;
			rl = r;
		}
		if (i == 0)
			throw outsideDomain("inverse", xyx, xyy, "the radius iteration did not settle to "
					+ EPS + " within " + NITER + " trips");
		Az = Av - Az / n;
		out.y = ProjectionMath.asinChecked(s * FastStrictTrig.cos(z)
				+ c * FastStrictTrig.sin(z) * FastStrictTrig.cos(Az));
		out.x = Math.atan2(FastStrictTrig.sin(Az),
				c / FastStrictTrig.tan(z) - s * FastStrictTrig.cos(Az));
		if (neg)
			out.x -= R110;
		else
			out.x = lamB - out.x;
		return out;
	}

	/**
	 * @param direction {@code "forward"} or {@code "inverse"}, for the message
	 * @param u         the first ordinate as supplied
	 * @param v         the second ordinate as supplied
	 * @param why       what upstream's guard tested
	 * @return the exception to throw
	 */
	private ProjectionException outsideDomain(String direction, double u, double v, String why) {
		return new ProjectionException(ErrorCause.COORDINATE_OUT_OF_DOMAIN, this,
				direction + " of (" + u + ", " + v + ") is outside the mapped hemisphere: " + why);
	}

	public boolean hasInverse() {
		return true;
	}

	/**
	 * {@code +ns}: rotate the (x, y) frame so the two conics' common axis is horizontal.
	 * <p>
	 * Not yet a {@code Proj4Keyword}, so nothing calls this; it exists so that registering the key
	 * is a one-line parser change rather than a change here as well. Neither {@code bipc} block in
	 * {@code builtins.gie} uses it.
	 *
	 * @param noskew whether to apply the de-skew rotation
	 */
	public void setNoskew(boolean noskew) {
		this.noskew = noskew;
	}

	public boolean isNoskew() {
		return noskew;
	}

	/**
	 * {@code PJ_PROJECTION(bipc)}, whose entire body beyond wiring the two kernels is
	 * {@code Q->noskew = pj_param(...,"bns").i} and {@code P->es = 0}.
	 * <p>
	 * The {@code es = 0} is the load-bearing half and it was missing. It must be assigned
	 * <em>before</em> {@code super.initialize()}, which derives {@code spherical}, {@code one_es}
	 * and {@code rone_es} from it. Assigning constants is idempotent, so the second call the parser
	 * makes is a no-op — non-negotiable 4.
	 */
	public void initialize() { // bipc
		es = 0.;
		e = 0.;
		super.initialize();
	}

	public String toString() {
		return "Bipolar Conic of Western Hemisphere";
	}

}
