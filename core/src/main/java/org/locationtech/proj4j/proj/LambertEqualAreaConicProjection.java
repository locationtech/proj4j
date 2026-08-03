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

import org.locationtech.proj4j.util.ProjectionMath;

public class LambertEqualAreaConicProjection extends AlbersProjection {

	private boolean south;

	public LambertEqualAreaConicProjection() {
		this( false );
	}

	public LambertEqualAreaConicProjection( boolean south ) {
		minLatitude = Math.toRadians(0);
		maxLatitude = Math.toRadians(90);
		this.south = south;
		projectionLatitude1 = south ? -ProjectionMath.QUARTERPI : ProjectionMath.QUARTERPI;
		projectionLatitude2 = south ? -ProjectionMath.HALFPI : ProjectionMath.HALFPI;
		initialize();
	}

	/**
	 * {@code +south}: PROJ takes the second parallel at the south pole rather than the north one
	 * ({@code phi1 = south ? -90 : 90}, with {@code +lat_1} as the other parallel).
	 */
	@Override
	public void setSouthernHemisphere(boolean south) {
		this.south = south;
		projectionLatitude2 = south ? -ProjectionMath.HALFPI : ProjectionMath.HALFPI;
	}

	@Override
	public boolean getSouthernHemisphere() {
		return south;
	}

	public String toString() {
		return "Lambert Equal Area Conic";
	}

}
