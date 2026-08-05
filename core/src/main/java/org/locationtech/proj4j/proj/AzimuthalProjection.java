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

package org.locationtech.proj4j.proj;

import java.util.Objects;

import org.locationtech.proj4j.util.ProjectionMath;

/**
 * The superclass for all azimuthal map projections
 */
public abstract class AzimuthalProjection extends Projection {

	private static final long serialVersionUID = -5590774380842159499L;

	public final static int NORTH_POLE = 1;
	public final static int SOUTH_POLE = 2;
	public final static int EQUATOR = 3;
	public final static int OBLIQUE = 4;

	protected int mode;
	protected double sinphi0, cosphi0;
	private double mapRadius = 90.0;

	/**
	 * {@code lat_0 = lon_0 = 0}, which is what PROJ defaults them to.
	 * <p>
	 * <b>This used to be 45 degrees on both axes.</b> {@code parser/Proj4Parser} assigns
	 * {@code +lat_0}/{@code +lon_0} only when the keyword is present, so this constructor - not
	 * PROJ - defined the effective default for every azimuthal projection whose definition
	 * omitted them, and every such definition was silently oblique at 45&deg;. Every
	 * {@code +proj=ortho +ellps=WGS84} row in {@code builtins.gie:5658-5738} measured it, and a
	 * frozen A/B puts the figure at <b>40 assertions</b> - see the table below.
	 * <p>
	 * <b>The two numbers this defect was first reported with are both artefacts of a second bug,
	 * and neither is a projection.</b> The report was {@code (170, 10) -> 5145289.58} against
	 * "the equatorial answer {@code 1090725.665}". In fact {@code 5145289.577} is exactly
	 * {@code a*cos(10)*sin(170-45)} and {@code 1090725.665} is exactly {@code a*cos(10)*sin(170)},
	 * i.e. both are the value the old
	 * {@code OrthographicAzimuthalProjection}'s unconditional {@code xy.x = cosphi*sin(lam)} wrote
	 * <em>after</em> the visibility guard had already poisoned both ordinates - the same
	 * expression, evaluated at two different central meridians. There is no "equatorial answer" to
	 * compare against: the visibility dot product is {@code -0.277} at 45/45 and {@code -0.970} at
	 * 0/0, so <em>both</em> aspects reject the point, and {@code proj 9.8.1} prints {@code * *}
	 * for both. The correct default therefore turns a wrong coordinate into a domain refusal here,
	 * not into a different coordinate.
	 * <p>
	 * Only two classes reach this constructor: {@link OrthographicAzimuthalProjection} and the
	 * unregistered {@link EqualAreaAzimuthalProjection}. {@code gnom}, {@code stere}, {@code aeqd}
	 * and {@code ups} already chain through {@code this(0.0, 0.0)} or set their own pole. That
	 * enumeration is pinned, with a positive control, by
	 * {@code proj.AzimuthalCentreDefaultTest} - a superclass default is exactly the kind of change
	 * whose blast radius must be counted rather than assumed.
	 * <p>
	 * The blast radius, measured on a frozen tree by reverting one cause at a time and re-running
	 * the whole gie corpus (7,923 assertions):
	 * <table border="1">
	 * <caption>{@code ortho} passing assertions, 2x2</caption>
	 * <tr><th>{@code ortho} class</th><th>default 45/45</th><th>default 0/0</th></tr>
	 * <tr><td>1.4.3</td><td>97</td><td>105</td></tr>
	 * <tr><td>ported</td><td>108</td><td><b>148</b></td></tr>
	 * </table>
	 * <p>
	 * No other operator moves in either direction - {@code aeqd}, {@code gnom}, {@code stere},
	 * {@code ups} and {@code laea} are bit-for-bit unchanged across all four cells. Note that the
	 * effects are strongly non-additive: {@code +8} for the default alone and {@code +11} for the
	 * class alone, but {@code +51} together, so 32 of the 51 assertions need both. Measuring
	 * either cause on its own would have under-reported it by a factor of five.
	 */
	public AzimuthalProjection() {
		this( 0.0, 0.0 );
	}

	public AzimuthalProjection(double projectionLatitude, double projectionLongitude) {
		this.projectionLatitude = projectionLatitude;
		this.projectionLongitude = projectionLongitude;
		initialize();
	}

	public void initialize() {
		super.initialize();
		if (Math.abs(Math.abs(projectionLatitude) - ProjectionMath.HALFPI) < EPS10)
			mode = projectionLatitude < 0. ? SOUTH_POLE : NORTH_POLE;
		else if (Math.abs(projectionLatitude) > EPS10) {
			mode = OBLIQUE;
			sinphi0 = Math.sin(projectionLatitude);
			cosphi0 = Math.cos(projectionLatitude);
		} else
			mode = EQUATOR;
	}

	public boolean inside(double lon, double lat) {
		return ProjectionMath.greatCircleDistance( ProjectionMath.toRad(lon), ProjectionMath.toRad(lat), projectionLongitude, projectionLatitude) < ProjectionMath.toRad(mapRadius);
	}

	/**
	 * Set the map radius (in degrees). 180 shows a hemisphere, 360 shows the whole globe.
	 */
	public void setMapRadius(double mapRadius) {
		this.mapRadius = mapRadius;
	}

	public double getMapRadius() {
		return mapRadius;
	}

	@Override
	public boolean equals(Object that) {
			if (this == that) {
					return true;
			}
			if (that instanceof AzimuthalProjection) {
					AzimuthalProjection p = (AzimuthalProjection) that;
					return (
						mode == p.mode &&
						sinphi0 == p.sinphi0 &&
						cosphi0 == p.cosphi0 &&
						mapRadius == p.mapRadius &&
						super.equals(that));
			}
			return false;
	}

	@Override
	public int hashCode() {
			return Objects.hash(mode, sinphi0, cosphi0, mapRadius, super.hashCode());
	}
}
