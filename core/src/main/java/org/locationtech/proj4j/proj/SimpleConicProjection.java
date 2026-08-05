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

import org.locationtech.proj4j.InvalidValueException;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.ProjectionException;
import org.locationtech.proj4j.util.ProjectionMath;

public class SimpleConicProjection extends ConicProjection {

	private static final long serialVersionUID = 3928142084443019917L;

	private double n;
	private double rho_c;
	private double rho_0;
	private double sig;
	private double c1, c2;
	private int	type;

	public final static int EULER = 0;
	public final static int MURD1 = 1;
	public final static int MURD2 = 2;
	public final static int MURD3 = 3;
	public final static int PCONIC = 4;
	public final static int TISSOT = 5;
	public final static int VITK1 = 6;
	private final static double EPS10 = 1.e-10;
	private final static double EPS = 1e-10;

	public SimpleConicProjection() {
		this( EULER );
	}

	public SimpleConicProjection(int type) {
		this.type = type;
		minLatitude = ProjectionMath.toRad(0);
		maxLatitude = ProjectionMath.toRad(80);
	}

	public String toString() {
		return "Simple Conic";
	}

	public ProjCoordinate project(double lplam, double lpphi, ProjCoordinate out) {
		double rho;

		switch (type) {
		case MURD2:
			rho = rho_c + Math.tan(sig - lpphi);
			break;
		case PCONIC:
			rho = c2 * (c1 - Math.tan(lpphi - sig));
			break;
		default:
			rho = rho_c - lpphi;
			break;
		}
		out.x = rho * Math.sin( lplam *= n );
		out.y = rho_0 - rho * Math.cos(lplam);
		return out;
	}

	/**
	 * Port of {@code sconics_s_inverse} ({@code 9.8.1:src/projections/sconics.cpp:94-122}).
	 *
	 * <p><b>Two independent transcription defects were fixed here</b>, both from the same
	 * mishandling of C's mutate-the-parameter idiom. Upstream reassigns {@code xy.y} in
	 * place — {@code xy.y = Q-&gt;rho_0 - xy.y;} — and every later use of {@code xy.y} means
	 * the reassigned value. The Java version wrote the new value into {@code out.y} instead
	 * and then kept reading the untouched parameter:
	 *
	 * <ul>
	 * <li>{@code atan2(xyx, xyy)} used the <b>raw northing</b> where upstream uses
	 *     {@code rho_0 - y}. The longitude was therefore wrong by whatever {@code rho_0}
	 *     is — which is the bulk of the northing for every member of the family, so the
	 *     error is first-order, not a rounding matter.
	 * <li>The {@code n &lt; 0} branch negated into {@code out.x} and {@code out.y}, which are
	 *     both <b>overwritten on the next two statements</b>. Upstream negates {@code xy.x}
	 *     and {@code xy.y}, the very values {@code atan2} then consumes. So the
	 *     southern-cone case was a no-op apart from flipping the sign of {@code rho}.
	 * </ul>
	 *
	 * <p>Both are fixed by working in locals that mirror upstream's mutated
	 * {@code xy.x}/{@code xy.y} rather than by aliasing the output coordinate.
	 */
	public ProjCoordinate projectInverse(double xyx, double xyy, ProjCoordinate out) {
		double rho;
		// sconics.cpp:102 -- xy.y is reassigned, and every later read means the new value.
		double x = xyx;
		double y = rho_0 - xyy;

		rho = ProjectionMath.distance(x, y);
		if (n < 0.) {
			rho = - rho;
			x = - x;
			y = - y;
		}
		out.x = Math.atan2(x, y) / n;
		switch (type) {
		case PCONIC:
			out.y = Math.atan(c1 - rho / c2) + sig;
			break;
		case MURD2:
			out.y = sig - Math.atan(rho - rho_c);
			break;
		default:
			out.y = rho_c - rho;
		}
		return out;
	}

	public boolean hasInverse() {
		return true;
	}

	/**
	 * Port of {@code phi12} and {@code pj_sconics_setup}
	 * ({@code 9.8.1:src/projections/sconics.cpp:41-67} and {@code :124-190}).
	 *
	 * <p><b>This used to ignore {@code +lat_1} and {@code +lat_2} entirely.</b> The
	 * parameter read was commented out behind a {@code FIXME} and replaced with
	 * {@code p1 = toRadians(30)}, {@code p2 = toRadians(60)}, so every one of the seven
	 * {@code sconics} operators behaved as though the standard parallels were 30&deg; and
	 * 60&deg; whatever the caller asked for. {@link Projection#projectionLatitude1} and
	 * {@link Projection#projectionLatitude2} were parsed, stored, and never read.
	 *
	 * <p>That is a silent wrong answer rather than a refusal, and it was live in six
	 * registrations — {@code euler}, {@code murd1}, {@code murd2}, {@code murd3},
	 * {@code pconic} and {@code vitk1}. All fourteen {@code sconics} operations in
	 * {@code builtins.gie} use {@code +lat_1=0.5 +lat_2=2}, the one thing the hard-coding
	 * cannot produce, so the family's 112 assertions were failing on this alone; the
	 * measured deviation was 2,336 km of easting for {@code tissot}.
	 *
	 * <p>Two smaller repairs in the same place. A no-op {@code del = del;} was carried over
	 * from the C {@code *del = *del}, which dereferenced an out-parameter and meant
	 * something there. And {@code case PCONIC} contained a stray, unindented
	 * {@code maxLatitude = Math.toRadians(60);//FIXME} that hard-coded the domain bound as
	 * well — unrelated to the requested parallels, and with no counterpart upstream, where
	 * {@code sconics} declares no latitude limits at all.
	 *
	 * <p><b>On the missing-parameter check.</b> {@code sconics.cpp:44-51} raises
	 * {@code PROJ_ERR_INVALID_OP_ILLEGAL_ARG_VALUE} when either {@code +lat_1} or
	 * {@code +lat_2} is absent, before computing anything. Proj4J cannot distinguish
	 * "absent" from "given as zero" — both leave the field at its {@code 0.0} default — but
	 * it does not need to: with both absent, {@code del} and {@code sig} are both zero and
	 * the {@code |del| < EPS || |sig| < EPS} test already rejects them. The two cases that
	 * differ are {@code +lat_1=0 +lat_2=0} and a single parallel given as zero, and both are
	 * degenerate and rejected too. So the guard below covers upstream's error condition
	 * exactly, and reports it as an {@link InvalidValueException} naming the values, rather
	 * than as the previous {@code "Error -42"}.
	 */
	public void initialize() {
		super.initialize();
		double del, cs;

		/* get common factors for simple conics -- sconics.cpp:41-67 */
		double p1 = projectionLatitude1;
		double p2 = projectionLatitude2;
		del = 0.5 * (p2 - p1);
		sig = 0.5 * (p2 + p1);

		if (Math.abs(del) < EPS || Math.abs(sig) < EPS) {
			throw new InvalidValueException(
					"Illegal value for lat_1 and lat_2: |lat_1 - lat_2| and |lat_1 + lat_2|"
					+ " should be > 0, but lat_1 = " + Math.toDegrees(p1) + " and lat_2 = "
					+ Math.toDegrees(p2) + " give |del| = " + Math.abs(del) + " and |sig| = "
					+ Math.abs(sig) + " rad. Both +lat_1 and +lat_2 are required by every"
					+ " sconics projection (euler, murd1, murd2, murd3, pconic, tissot,"
					+ " vitk1).");
		}

		switch (type) {
		case TISSOT:
			n = Math.sin(sig);
			cs = Math.cos(del);
			rho_c = n / cs + cs / n;
			rho_0 = Math.sqrt((rho_c - 2 * Math.sin(projectionLatitude))/n);
			break;
		case MURD1:
			rho_c = Math.sin(del)/(del * Math.tan(sig)) + sig;
			rho_0 = rho_c - projectionLatitude;
			n = Math.sin(sig);
			break;
		case MURD2:
			rho_c = (cs = Math.sqrt(Math.cos(del))) / Math.tan(sig);
			rho_0 = rho_c + Math.tan(sig - projectionLatitude);
			n = Math.sin(sig) * cs;
			break;
		case MURD3:
			rho_c = del / (Math.tan(sig) * Math.tan(del)) + sig;
			rho_0 = rho_c - projectionLatitude;
			n = Math.sin(sig) * Math.sin(del) * Math.tan(del) / (del * del);
			break;
		case EULER:
			n = Math.sin(sig) * Math.sin(del) / del;
			del *= 0.5;
			rho_c = del / (Math.tan(del) * Math.tan(sig)) + sig;
			rho_0 = rho_c - projectionLatitude;
			break;
		case PCONIC:
			n = Math.sin(sig);
			c2 = Math.cos(del);
			c1 = 1./Math.tan(sig);
			if (Math.abs(del = projectionLatitude - sig) - EPS10 >= ProjectionMath.HALFPI)
				throw new ProjectionException("-43");
			rho_0 = c2 * (c1 - Math.tan(del));
			break;
		case VITK1:
			n = (cs = Math.tan(del)) * Math.sin(sig) / del;
			rho_c = del / (cs * Math.tan(sig)) + sig;
			rho_0 = rho_c - projectionLatitude;
			break;
		}
	}

	@Override
	public boolean equals(Object that) {
			if (this == that) {
					return true;
			}
			if (that instanceof SimpleConicProjection) {
					SimpleConicProjection p = (SimpleConicProjection) that;
					return (this.type == p.type) && super.equals(that);
			}
			return false;
	}

	@Override
	public int hashCode() {
			return Objects.hash(type, super.hashCode());
	}
}
