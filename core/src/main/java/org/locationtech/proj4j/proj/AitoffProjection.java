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
 *******************************************************************************/

/*
 * This file was semi-automatically converted from the public-domain USGS PROJ source.
 */
package org.locationtech.proj4j.proj;

import java.util.Objects;

import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.ProjectionException;


public class AitoffProjection extends PseudoCylindricalProjection {

	protected final static int AITOFF = 0;
	protected final static int WINKEL = 1;

	private boolean winkel = false;
	private double cosphi1 = 0;

	public AitoffProjection() {
		// NaN marks "+lat_1 not given", so the Winkel Tripel default can be applied
		projectionLatitude1 = Double.NaN;
	}

	protected AitoffProjection(int type) {
		this();
		winkel = type == WINKEL;
	}

	public AitoffProjection(int type, double projectionLatitude) {
		this(type);
		this.projectionLatitude = projectionLatitude;
	}

	public ProjCoordinate project(double lplam, double lpphi, ProjCoordinate out) {
		double c = 0.5 * lplam;
		double d = Math.acos(Math.cos(lpphi) * Math.cos(c));

		if (d != 0) {
			out.x = 2. * d * Math.cos(lpphi) * Math.sin(c) * (out.y = 1. / Math.sin(d));
			out.y *= d * Math.sin(lpphi);
		} else
			out.x = out.y = 0.0;
		if (winkel) {
			out.x = (out.x + lplam * cosphi1) * 0.5;
			out.y = (out.y + lpphi) * 0.5;
		}
		return out;
	}

	public void initialize() {
		super.initialize();
		if (winkel) {
			if (!Double.isNaN(projectionLatitude1)) {
				if ((cosphi1 = Math.cos(projectionLatitude1)) == 0.)
					throw new ProjectionException("Invalid value for lat_1: |lat_1| should be < 90");
			} else /* 50d28' or acos(2/pi) */
				cosphi1 = 0.636619772367581343;
		}
	}

	public boolean hasInverse() {
		return false;
	}

	public String toString() {
		return winkel ? "Winkel Tripel" : "Aitoff";
	}

	@Override
	public boolean equals(Object that) {
			if (this == that) {
					return true;
			}
			if (that instanceof AitoffProjection) {
					AitoffProjection p = (AitoffProjection) that;
					return (winkel == p.winkel && super.equals(that));
			}
			return false;
	}

	@Override
	public int hashCode() {
			return Objects.hash(winkel, super.hashCode());
	}
}
