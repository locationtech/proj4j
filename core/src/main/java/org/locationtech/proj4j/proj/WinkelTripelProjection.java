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


/**
 * Winkel Tripel ({@code +proj=wintri}): the {@link AitoffProjection} kernel in its
 * {@link AitoffProjection#WINKEL} mode. See that class for the {@code +lat_1} handling and the
 * Newton&ndash;Raphson inverse.
 */
public class WinkelTripelProjection extends AitoffProjection {

	private static final long serialVersionUID = 1512736703194945907L;

	/**
	 * The second argument is {@code lat_0}, in radians, and <b>not</b> {@code cosphi1}. This used to
	 * pass {@code 0.636619772367581343} — {@code acos(2/pi)} as a cosine — into that slot, which set
	 * {@code lat_0} to 36.47&deg;. Nothing in the kernel reads {@code lat_0}, so it changed no
	 * coordinate, but it did enter {@code equals}/{@code hashCode}, so a {@code wintri} built here
	 * and a {@code wintri} built as {@code new AitoffProjection(WINKEL, 0)} compared unequal.
	 * {@code +lat_1} is handled where upstream handles it, in
	 * {@link AitoffProjection#initialize()}.
	 */
	public WinkelTripelProjection() {
		super( WINKEL, 0.0 );
	}

	public String toString() {
		return "Winkel Tripel";
	}

}
