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

import org.locationtech.proj4j.ConvergenceFailureException;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.ProjectionException;
import org.locationtech.proj4j.util.ProjectionMath;

public class MolleweideProjection extends PseudoCylindricalProjection {

	private static final long serialVersionUID = -5486373011751486338L;

	/** PROJ's {@code ONE_TOL} ({@code proj_internal.h}): the arcsine slop band. */
	private static final double ONE_TOL = 1.00000000000001;

	public static final int MOLLEWEIDE = 0;
	public static final int WAGNER4 = 1;
	public static final int WAGNER5 = 2;

	/**
	 * Matches PROJ 9.8.1 {@code moll.cpp}'s {@code MAX_ITER}, which is 30. Proj4J used 10,
	 * which reaches the non-convergence branch on inputs PROJ solves.
	 */
	private static final int MAX_ITER = 30;
	private static final double TOLERANCE = 1e-7;

	private int type = MOLLEWEIDE;
	private double cx, cy, cp;

	public MolleweideProjection() {
		this(Math.PI/2);
	}

	public MolleweideProjection(int type) {
		this.type = type;
		switch (type) {
		case MOLLEWEIDE:
			init(Math.PI/2);
			break;
		case WAGNER4:
			init(Math.PI/3);
			break;
		case WAGNER5:
			init(Math.PI/2);
			cx = 0.90977;
			cy = 1.65014;
			cp = 3.00896;
			break;
		}
	}

	public MolleweideProjection(double p) {
		init(p);
	}

	public void init(double p) {
		double r, sp, p2 = p + p;

		sp = Math.sin(p);
		r = Math.sqrt(Math.PI*2.0 * sp / (p2 + Math.sin(p2)));
		cx = 2. * r / Math.PI;
		cy = r / sp;
		cp = p2 + Math.sin(p2);
	}

	public MolleweideProjection(double cx, double cy, double cp) {
		this.cx = cx;
		this.cy = cy;
		this.cp = cp;
	}

	/**
	 * Forward projection, shared by {@code moll}, {@code wag4} and {@code wag5}.
	 * <p>
	 * <b>Fail-closed.</b> PROJ's {@code moll.cpp} clamps to {@code ±M_HALFPI} — the pole — when
	 * the iteration exhausts {@code MAX_ITER}. Proj4J throws: the pole is a specific plausible
	 * coordinate, indistinguishable from a real result, and this kernel serves three
	 * projections so the clamp was reachable three ways.
	 */
	public ProjCoordinate project(double lplam, double lpphi, ProjCoordinate xy) {
		double k, v = Double.NaN;
		int i;
		final double phi = lpphi;

		k = cp * Math.sin(lpphi);
		for (i = MAX_ITER; i != 0; i--) {
			lpphi -= v = (lpphi + Math.sin(lpphi) - k) / (1. + Math.cos(lpphi));
			if (Math.abs(v) < TOLERANCE)
				break;
		}
		if (i == 0) {
			throw new ConvergenceFailureException(this,
					"forward parametric-latitude iteration did not converge to " + TOLERANCE
							+ " within " + MAX_ITER + " iterations for latitude " + phi
							+ " rad (last correction " + v + ")");
		}
		lpphi *= 0.5;
		xy.x = cx * lplam * Math.cos(lpphi);
		xy.y = cy * Math.sin(lpphi);
		return xy;
	}

	/**
	 * Inverse projection.
	 * <p>
	 * Both arcsines are guarded. They used raw {@link Math#asin}, which returns {@code NaN}
	 * for {@code |arg| > 1} — reachable for any {@code |y| > cy}, i.e. any northing past the
	 * top of the map. The {@code NaN} then passed straight through
	 * {@code Projection.inverseProjectRadians}' &plusmn;&pi; clamp (every comparison against
	 * {@code NaN} is false) and escaped as a {@code NaN} latitude whenever
	 * {@code +lon_0=0}. {@link #aasin} applies PROJ's tolerance band instead: within
	 * {@code ONE_TOL} of &plusmn;1 it clamps, beyond it throws.
	 * <p>
	 * Note this deliberately does <em>not</em> use {@code ProjectionMath.asin}, which clamps
	 * silently for any argument however large and lets {@code NaN} through untouched — a
	 * clamp to the pole is the fail-open shape this whole change exists to remove.
	 */
	public ProjCoordinate projectInverse(double x, double y, ProjCoordinate lp) {
		double lat, lon;

		lat = aasin(y / cy, "y/cy");
		lon = x / (cx * Math.cos(lat));
		lat += lat;
		lat = aasin((lat + Math.sin(lat)) / cp, "(2*lat + sin(2*lat))/cp");
		lp.x = lon;
		lp.y = lat;
		return lp;
	}

	/**
	 * PROJ's {@code aasin} ({@code src/aasincos.cpp}): arcsine with a tolerance band of
	 * {@code ONE_TOL} for arguments that ought to be in [-1, 1] and have drifted out by
	 * rounding, and a hard error beyond it.
	 *
	 * @param v    the arcsine argument
	 * @param what the expression that produced it, for the message
	 * @return the arcsine, radians
	 * @throws org.locationtech.proj4j.ProjectionException if {@code v} is NaN, or further than
	 *                                                     {@code ONE_TOL} outside [-1, 1]
	 */
	private double aasin(double v, String what) {
		if (Double.isNaN(v)) {
			throw new ProjectionException(this, "inverse is not defined: " + what + " is NaN");
		}
		double av = Math.abs(v);
		if (av >= 1.0) {
			if (av > ONE_TOL) {
				throw new ProjectionException(this, "coordinate is outside the projection domain: "
						+ what + " = " + v + ", which is not in [-1, 1]");
			}
			return v < 0.0 ? -ProjectionMath.HALFPI : ProjectionMath.HALFPI;
		}
		return Math.asin(v);
	}

	public boolean hasInverse() {
		return true;
	}

	public boolean isEqualArea() {
	    return true;
	}

	public String toString() {
		switch (type) {
		case WAGNER4:
			return "Wagner IV";
		case WAGNER5:
			return "Wagner V";
		}
		return "Molleweide";
	}

	@Override
	public boolean equals(Object that) {
			if (this == that) {
					return true;
			}
			if (that instanceof MolleweideProjection) {
					MolleweideProjection p = (MolleweideProjection) that;
					return (
						type == p.type &&
						cx == p.cx &&
						cy == p.cy &&
						cp == p.cp &&
						super.equals(that));
			}
			return false;
	}

	@Override
	public int hashCode() {
			return Objects.hash(type, cx, cy, cp, super.hashCode());
	}
}
