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
 * Lagrange ({@code +proj=lagrng}), ported from {@code 9.8.1:src/projections/lagrng.cpp}.
 *
 * <h2>Three defects, and two of them were shared state rather than arithmetic</h2>
 *
 * <ol>
 * <li><b>{@code +W} defaulted to 1.4, where PROJ defaults it to 2</b> — and the field was
 *     <em>reciprocated in place</em>: {@code hrw = 0.5 * (rw = 1./rw)} writes the derived value back
 *     into the field it just read. {@code initialize()} runs twice (once from the parser, once from
 *     wherever the projection was constructed), so the second call reciprocated it again and the
 *     effective {@code W} flip-flopped between 1.4 and 0.714 depending on how many times
 *     {@code initialize()} had been called. Non-negotiable 4. {@code w} is now the parameter, kept
 *     as given, and {@code hw}/{@code rw}/{@code hrw} are three separate derived fields, exactly as
 *     upstream's {@code struct pj_lagrng} holds them.</li>
 * <li><b>The forward's pole test was on the latitude, where upstream's is on its sine.</b> Upstream
 *     tests {@code |‌|sin(phi)| - 1| &lt; 1e-10}; the old code tested {@code |‌|phi| - pi/2| &lt;
 *     1e-10}. Those are not the same band: at 89.9999999&deg; the latitude is 1.7e-9 rad from the
 *     pole (outside the band) but its sine is 1.5e-18 from 1 (well inside it). So upstream returns
 *     {@code (0, ±2)} and Proj4J divided by {@code 1 - sin(phi) = 1.5e-18} and returned
 *     {@code Infinity}. Two corpus rows measured it.</li>
 * <li><b>There was no inverse.</b> Upstream has a closed-form one.</li>
 * </ol>
 *
 * <p>Upstream's two setup rejections are {@code PROJ_ERR_INVALID_OP_ILLEGAL_ARG_VALUE}, so they are
 * {@link InvalidValueException} here rather than the {@code ProjectionException("-27")} /
 * {@code ("-22")} they were: a {@code ProjectionException} says "this coordinate", and these are
 * both statements about the definition.
 *
 * <p>{@code +W} is not a {@code Proj4Keyword}, so {@link #setW} is reachable only from Java; the
 * bridge reports the corpus's {@code +W=} blocks as not implemented rather than running them at the
 * default.
 */
public class LagrangeProjection extends Projection {

	private static final long serialVersionUID = 2261795050715571321L;

	/** {@code Q->w}: {@code +W}, kept exactly as supplied. Upstream's default is 2. */
	private double w = 2.0;

	/** {@code Q->hw = 0.5*w}, {@code Q->rw = 1/w}, {@code Q->hrw = 0.5/w}. All derived. */
	private double hw, rw, hrw;

	/** {@code Q->a1}, {@code Q->a2 = a1*a1}. */
	private double a1, a2;

	private final static double TOL = 1e-10;

	/** {@code lagrng_s_forward}. */
	public ProjCoordinate project(double lplam, double lpphi, ProjCoordinate xy) {
		double v, c;

		final double sinPhi = FastStrictTrig.sin(lpphi);
		// Upstream's test is on the SINE, not on the latitude. See the class javadoc.
		if ( Math.abs(Math.abs(sinPhi) - 1) < TOL) {
			xy.x = 0;
			xy.y = lpphi < 0 ? -2. : 2.;
		} else {
			v = a1 * Math.pow((1. + sinPhi)/(1. - sinPhi), hrw);
			final double lam = lplam * rw;
			if ((c = 0.5 * (v + 1./v) + FastStrictTrig.cos(lam)) < TOL)
				throw new ProjectionException(ErrorCause.COORDINATE_OUT_OF_DOMAIN, this,
						"forward of (" + Math.toDegrees(lplam) + ", " + Math.toDegrees(lpphi)
								+ ") deg is on the map's boundary circle: the denominator " + c
								+ " is at or below " + TOL);
			xy.x = 2. * FastStrictTrig.sin(lam) / c;
			xy.y = (v - 1./v) / c;
		}
		return xy;
	}

	/** {@code lagrng_s_inverse}. */
	public ProjCoordinate projectInverse(double x, double y, ProjCoordinate lp) {
		double c, x2, y2p, y2m;

		if (Math.abs(Math.abs(y) - 2.) < TOL) {
			lp.y = y < 0 ? -ProjectionMath.HALFPI : ProjectionMath.HALFPI;
			lp.x = 0;
		} else {
			x2 = x * x;
			y2p = 2. + y;
			y2m = 2. - y;
			c = y2p * y2m - x2;
			if (Math.abs(c) < TOL) {
				throw new ProjectionException(ErrorCause.COORDINATE_OUT_OF_DOMAIN, this,
						"inverse of (" + x + ", " + y + ") is on the map's boundary circle: the "
								+ "denominator " + c + " is within " + TOL + " of zero");
			}
			lp.y = 2. * Math.atan(Math.pow((y2p * y2p + x2) / (a2 * (y2m * y2m + x2)), hw))
					- ProjectionMath.HALFPI;
			lp.x = w * Math.atan2(4. * x, c);
		}
		return lp;
	}

	/**
	 * {@code +W}: the fraction of a hemisphere the map's boundary circle spans, {@code 2} meaning a
	 * full hemisphere. Kept as supplied; the reciprocals are derived in {@link #initialize()}.
	 *
	 * @param w the parameter, {@code &gt; 0}
	 */
	public void setW( double w ) {
		this.w = w;
	}

	public double getW() {
		return w;
	}

	/**
	 * {@code PJ_PROJECTION(lagrng)}.
	 *
	 * @throws InvalidValueException for upstream's two rejections: {@code +W &lt;= 0}, and a
	 *         {@code +lat_1} at a pole
	 */
	public void initialize() {
		es = 0.;
		e = 0.;
		super.initialize();
		if (w <= 0)
			throw new InvalidValueException(
					"Invalid value for +W: it should be > 0, but is " + w);
		hw = 0.5 * w;
		rw = 1. / w;
		hrw = 0.5 * rw;
		final double sinPhi1 = Math.sin(projectionLatitude1);
		if (Math.abs(Math.abs(sinPhi1) - 1.) < TOL)
			throw new InvalidValueException(
					"Invalid value for +lat_1: |lat_1| should be < 90 degrees, but is "
							+ Math.toDegrees(projectionLatitude1));
		a1 = Math.pow((1. - sinPhi1)/(1. + sinPhi1), hrw);
		a2 = a1 * a1;
	}

	/**
	 * Returns true if this projection is conformal
	 */
	public boolean isConformal() {
		return true;
	}

	public boolean hasInverse() {
		return true;
	}

	public String toString() {
		return "Lagrange";
	}

	@Override
	public boolean equals(Object that) {
			if (this == that) {
					return true;
			}
			if (that instanceof LagrangeProjection) {
					LagrangeProjection p = (LagrangeProjection) that;
					return (w == p.w) && super.equals(that);
			}
			return false;
	}

	@Override
	public int hashCode() {
			return Objects.hash(w, super.hashCode());
	}
}
