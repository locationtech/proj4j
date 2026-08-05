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
import org.locationtech.proj4j.util.FastStrictTrig;
import org.locationtech.proj4j.util.ProjectionMath;

/**
 * Hammer and Eckert-Greifendorff ({@code +proj=hammer}), ported from
 * {@code 9.8.1:src/projections/hammer.cpp}.
 *
 * <h2>There was no inverse</h2>
 *
 * <p>{@code hammer_s_inverse} is closed form — no iteration — and it was simply absent, so
 * {@code hasInverse()} was false and four corpus rows could not be answered at all. Note the
 * {@code |2z^2 - 1| &lt; 1e-10} guard: that is the boundary of the map, where the inverse is
 * singular, and upstream reports it as outside the projection domain.
 *
 * <h2>{@code +W} and {@code +M} were parameters in name only</h2>
 *
 * <p>{@code initialize()} read the fields and then unconditionally overwrote them:
 *
 * <pre>
 *   if ((w = Math.abs(w)) &lt;= 0.) throw ...; else w = .5;      // always .5
 *   if ((m = Math.abs(m)) &lt;= 0.) throw ...; else m = 1.;       // always 1
 *   rm = 1./m; m /= w;
 * </pre>
 *
 * <p>so {@link #setW}/{@link #setM} had no effect, and {@code m /= w} wrote a <em>derived</em> value
 * back into the field it had just read — which {@code initialize()} running twice then divided by
 * {@code w} a second time. Upstream keeps {@code W} and {@code M} as given, defaults them to
 * {@code .5} and {@code 1}, and rejects a non-positive value with
 * {@code PROJ_ERR_INVALID_OP_ILLEGAL_ARG_VALUE}. The parameters and the derived quantities are now
 * separate fields, so a second {@code initialize()} sees the same inputs.
 *
 * <p><b>Neither {@code +W} nor {@code +M} is a {@code Proj4Keyword} yet</b>, so the corpus's
 * {@code +proj=hammer +a=6400000 +W=1} block is still reported as not implemented rather than run at
 * the default. The setters make registering them a parser-only change.
 */
public class HammerProjection extends PseudoCylindricalProjection {

	private static final long serialVersionUID = 5527306458585454077L;

	private static final double EPS = 1.0e-10;

	/** {@code +W}, exactly as supplied. Upstream's default is {@code .5}. */
	private double w = 0.5;

	/** {@code +M}, exactly as supplied. Upstream's default is {@code 1}. */
	private double mParam = 1;

	/** {@code Q->m = M/W} and {@code Q->rm = 1/M}, both derived in {@link #initialize()}. */
	private double m, rm;

	public HammerProjection() {
	}

	/** {@code hammer_s_forward}. */
	public ProjCoordinate project(double lplam, double lpphi, ProjCoordinate xy) {
		double cosphi, d;

		cosphi = FastStrictTrig.cos(lpphi);
		final double lam = lplam * w;
		final double denom = 1. + cosphi * FastStrictTrig.cos(lam);
		if (denom == 0.0) {
			throw new ProjectionException(ErrorCause.COORDINATE_OUT_OF_DOMAIN, this,
					"forward of (" + Math.toDegrees(lplam) + ", " + Math.toDegrees(lpphi)
							+ ") deg is the antipode of the projection centre");
		}
		d = Math.sqrt(2. / denom);
		xy.x = m * d * cosphi * FastStrictTrig.sin(lam);
		xy.y = rm * d * FastStrictTrig.sin(lpphi);
		return xy;
	}

	/**
	 * {@code hammer_s_inverse}, closed form.
	 *
	 * @throws ProjectionException {@link ErrorCause#COORDINATE_OUT_OF_DOMAIN} on the map boundary,
	 *         where {@code |2z^2 - 1|} is within {@code 1e-10} of zero
	 */
	public ProjCoordinate projectInverse(double x, double y, ProjCoordinate lp) {
		double z = Math.sqrt(1. - 0.25 * w * w * x * x - 0.25 * y * y);
		if (Math.abs(2. * z * z - 1.) < EPS) {
			throw new ProjectionException(ErrorCause.COORDINATE_OUT_OF_DOMAIN, this,
					"inverse of (" + x + ", " + y + ") is on the map's boundary, where "
							+ "2z^2 - 1 = " + (2. * z * z - 1.) + " is within " + EPS + " of zero");
		}
		// aatan2: upstream returns 0 rather than atan2(0, 0) when both arguments are below 1e-50.
		final double num = w * x * z;
		final double den = 2. * z * z - 1;
		lp.x = (Math.abs(num) < ATOL && Math.abs(den) < ATOL ? 0. : Math.atan2(num, den)) / w;
		lp.y = ProjectionMath.asinChecked(z * y);
		return lp;
	}

	/** {@code aasincos.cpp}'s {@code ATOL}, used by {@code aatan2}. */
	private static final double ATOL = 1e-50;

	/**
	 * {@code PJ_PROJECTION(hammer)}.
	 *
	 * @throws InvalidValueException where upstream returns
	 *         {@code PROJ_ERR_INVALID_OP_ILLEGAL_ARG_VALUE}: a non-positive {@code +W} or {@code +M}
	 */
	public void initialize() {
		es = 0.;
		e = 0.;
		super.initialize();
		w = Math.abs(w);
		if (w <= 0.) {
			throw new InvalidValueException("Invalid value for +W: it should be > 0, but is " + w);
		}
		mParam = Math.abs(mParam);
		if (mParam <= 0.) {
			throw new InvalidValueException(
					"Invalid value for +M: it should be > 0, but is " + mParam);
		}
		rm = 1. / mParam;
		m = mParam / w;
	}

	/**
	 * Returns true if this projection is equal area
	 */
	public boolean isEqualArea() {
		return true;
	}

	// Properties

	/** {@code +W}: the longitudinal compression. {@code .5} is Hammer, {@code 1} is Lambert azimuthal. */
	public void setW( double w ) {
		this.w = w;
	}

	public double getW() {
		return w;
	}

	/**
	 * {@code +M}: the aspect factor. Note that {@code getM()} now answers the parameter as supplied,
	 * not the derived {@code M/W} — it used to answer the latter, because {@code initialize()}
	 * overwrote the field.
	 */
	public void setM( double m ) {
		this.mParam = m;
	}

	public double getM() {
		return mParam;
	}

	public boolean hasInverse() {
		return true;
	}

	public String toString() {
		return "Hammer & Eckert-Greifendorff";
	}

	@Override
	public boolean equals(Object that) {
			if (this == that) {
					return true;
			}
			if (that instanceof HammerProjection) {
					HammerProjection p = (HammerProjection) that;
					return (
						mParam == p.mParam &&
						w == p.w &&
						super.equals(that));
			}
			return false;
	}

	@Override
	public int hashCode() {
			return Objects.hash(mParam, w, super.hashCode());
	}
}
