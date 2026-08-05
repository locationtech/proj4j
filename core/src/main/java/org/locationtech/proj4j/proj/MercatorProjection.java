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

import org.locationtech.proj4j.InvalidValueException;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.util.ConformalLat;
import org.locationtech.proj4j.util.MathHelpers;
import org.locationtech.proj4j.util.ProjectionMath;

/**
 * Mercator, a port of {@code 9.8.1:src/projections/merc.cpp}.
 *
 * <p>The northing is the <b>isometric latitude</b> {@code psi} scaled by {@code k0}, and 9.8.1
 * evaluates it directly rather than through {@code -log(tsfn(phi))}:
 * <pre>
 *   psi = asinh(tan(phi)) - e * atanh(e * sin(phi))
 * </pre>
 * ({@code merc.cpp:11-20}). Two things follow.
 *
 * <ul>
 * <li>At {@code phi = 0} both terms are <b>exactly</b> zero, so the northing is exactly zero.
 *     That is what {@code builtins.gie:4262-4265} demands: {@code accept 0 0 / expect 0 0} under
 *     {@code tolerance 0 m}. The old route through {@code ProjectionMath.tsfn} returned
 *     {@code 0.9999999999999999}, whose logarithm is {@code -1.11e-16}, i.e. a northing of
 *     {@code 7.08e-10 m} — small, but not zero, and the row admits nothing but zero.
 * <li>{@code MathHelpers#asinh}'s {@code log1p} branch is what keeps the near-equatorial values
 *     accurate; the naive {@code log(1 + tiny)} form loses every significant bit there.
 * </ul>
 *
 * <p>The inverse is {@code atan(sinhpsi2tanphi(sinh(y/k0), e))} ({@code merc.cpp:29-34}). Working
 * from {@code sinh(psi) = tan(chi)} rather than from {@code ts = exp(-psi)} keeps the large-|y|
 * behaviour exact — {@code builtins.gie:4300-4303} feeds a northing of {@code 1e10} and expects
 * exactly 90 degrees — and replaces a 15-step {@code pow}-per-trip Newton loop that was up to
 * 4,145 nm from the truth with a one-or-two-step Newton on {@code tau} that is about 2 nm. The
 * {@code merc} inverse rows at {@code builtins.gie:4285-4297} are pinned at {@code tolerance
 * 50 nm}; the old path missed them by 202 nm.
 *
 * <p><b>{@code +lat_ts} (Mercator variant B).</b> {@code merc.cpp:47-67} derives the scale factor
 * from the latitude of true scale, {@code k0 = msfn(sin(lat_ts), cos(lat_ts), es)} on an ellipsoid
 * and {@code k0 = cos(lat_ts)} on a sphere, and it does so <em>after</em> the generic {@code +k_0}
 * handling — so {@code +lat_ts} wins when both are given. proj4j read the parameter into
 * {@link Projection#trueScaleLatitude} and then never used it, leaving {@code k0 = 1}: EPSG:3388
 * (Pulkovo 1942 / Caspian Sea Mercator, {@code +lat_ts=42}) was out by a factor of
 * {@code msfn(42 deg, krass) = 0.744260894}, i.e. up to 1.3 million metres, and {@code gigs/5112}
 * passed only its equator rows and its self-consistent round trips.
 */
public class MercatorProjection extends CylindricalProjection {

	private static final long serialVersionUID = 3547566065076844580L;

	public MercatorProjection() {
		minLatitude = ProjectionMath.degToRad(-85);
		maxLatitude = ProjectionMath.degToRad(85);
	}

	/**
	 * Applies {@code +lat_ts} to the scale factor, per {@code 9.8.1:merc.cpp:47-67}.
	 *
	 * <p>A {@code lat_ts} of exactly zero is indistinguishable from the parameter's absence in
	 * proj4j's parameter model, and it does not matter: both branches give {@code k0 = 1} there
	 * ({@code msfn(0, 1, es) = 1} and {@code cos(0) = 1}), so treating zero as "absent"
	 * differs from upstream only for the pathological {@code +lat_ts=0 +k_0=<something else>},
	 * where upstream would discard the {@code k_0}.
	 */
	@Override
	public void initialize() {
		super.initialize();
		if (trueScaleLatitude != 0.0) {
			double phits = Math.abs(trueScaleLatitude);
			if (phits >= ProjectionMath.HALFPI) {
				throw new InvalidValueException(
						"Invalid value for lat_ts: |lat_ts| should be <= 90 degrees");
			}
			scaleFactor = spherical
					? Math.cos(phits)
					: ProjectionMath.msfn(Math.sin(phits), Math.cos(phits), es);
		}
	}

	public ProjCoordinate project(double lam, double phi, ProjCoordinate out) {
		out.x = scaleFactor * lam;
		if (spherical) {
			// merc.cpp:23 -- asinh(tan(phi)), not log(tan(pi/4 + phi/2)).
			out.y = scaleFactor * MathHelpers.asinh(Math.tan(phi));
		} else {
			// merc.cpp:16-19. sin and cos rather than tan and sin so that a single sincos
			// serves both, and so that phi = 0 yields exactly zero.
			double sphi = Math.sin(phi);
			double cphi = Math.cos(phi);
			out.y = scaleFactor * (MathHelpers.asinh(sphi / cphi) - e * MathHelpers.atanh(e * sphi));
		}
		return out;
	}

	public ProjCoordinate projectInverse(double x, double y, ProjCoordinate out) {
		out.x = x / scaleFactor;
		// sinh(psi) == tan(chi) == tau'. merc.cpp:31 and :38.
		double taup = Math.sinh(y / scaleFactor);
		out.y = spherical
				? Math.atan(taup)
				: Math.atan(ConformalLat.sinhpsi2tanphi(taup, e));
		return out;
	}

	public boolean hasInverse() {
		return true;
	}

	public boolean isRectilinear() {
		return true;
	}

	/**
	 * Returns the ESPG code for this projection, or 0 if unknown.
	 */
	public int getEPSGCode() {
		return 9804;
	}

	public String toString() {
		return "Mercator";
	}

}
