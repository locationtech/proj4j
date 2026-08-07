/*******************************************************************************
 * Copyright 2009, 2017 Martin Davis
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
package org.locationtech.proj4j;

import org.locationtech.proj4j.datum.Datum;
import org.locationtech.proj4j.datum.Ellipsoid;
import org.locationtech.proj4j.datum.PrimeMeridian;
import org.locationtech.proj4j.proj.LongLatProjection;
import org.locationtech.proj4j.proj.Projection;
import org.locationtech.proj4j.units.Unit;
import org.locationtech.proj4j.units.Units;

import java.util.Arrays;
import java.util.Objects;
import org.locationtech.proj4j.util.ProjectionMath;

/**
 * Represents a projected or geodetic geospatial coordinate system,
 * to which coordinates may be referenced.
 * A coordinate system is defined by the following things:
 * <ul>
 * <li>an {@link Ellipsoid} specifies how the shape of the Earth is approximated
 * <li>a {@link Datum} provides the mapping from the ellipsoid to
 * actual locations on the earth
 * <li>a {@link Projection} method maps the ellpsoidal surface to a planar space.
 * (The projection method may be null in the case of geodetic coordinate systems).
 * <li>a {@link Unit} indicates how the ordinate values
 * of coordinates are interpreted
 * </ul>
 *
 * @author Martin Davis
 * @see CRSFactory
 */
// CoordinateReferenceSystem corresponds to the PJ struct from proj.4
public class CoordinateReferenceSystem implements java.io.Serializable {

    private static final long serialVersionUID = 3023636591117313777L;

    /**
     * A sentinel naming "geographic coordinates on whatever datum the other CRS uses", for
     * specifying a transformation to or from geographic coordinates without a datum shift.
     * <p>
     * It is a marker, not a usable CRS: its datum and projection are both null, so
     * {@link #getProjection()} returns null and {@link #isGeographic()} and
     * {@link #createGeographic()} throw {@link NullPointerException}. Pass it to
     * {@link CoordinateTransformFactory}, which recognises it; do not transform with it directly.
     */
    // allows specifying transformations which convert to/from Geographic coordinates on the same datum
    public static final CoordinateReferenceSystem CS_GEO = new CoordinateReferenceSystem("CS_GEO", null, null, null);

    //TODO: add metadata like authority, id, name, parameter string, datum, ellipsoid, datum shift parameters

    /** Display name; never null after construction, since the constructor derives one when given null. */
    private String name;
    /** The PROJ.4 parameter tokens this CRS was built from, or null if it was not built from any. */
    private String[] params;
    /** The geodetic datum. Null only for the {@link #CS_GEO} sentinel. */
    private Datum datum;
    /** The projection method, or null for the {@link #CS_GEO} sentinel. */
    private Projection proj;

    /**
     * Creates a CRS from its parts.
     *
     * @param name   the display name; when null, one is derived from the projection's name
     * @param params the PROJ.4 parameter tokens this CRS was built from, may be null
     * @param datum  the geodetic datum
     * @param proj   the projection method, or null for a geodetic (unprojected) CRS
     */
    public CoordinateReferenceSystem(String name, String[] params, Datum datum, Projection proj) {
        this.name = name;
        this.params = params;
        this.datum = datum;
        this.proj = proj;

        if (name == null) {
            String projName = "null-proj";
            if (proj != null)
                projName = proj.getName();
            this.name = projName + "-CS";
        }
    }

    /**
     * Gets the display name of this CRS.
     *
     * @return the name; never null
     */
    public String getName() {
        return name;
    }

    /**
     * Gets the PROJ.4 parameter tokens this CRS was built from, such as {@code +proj=utm} and
     * {@code +zone=10}.
     * <p>
     * The array is returned directly rather than copied, so modifying it modifies this CRS. Treat it
     * as read-only.
     *
     * @return the parameter tokens, or null if this CRS was not built from a parameter list
     */
    public String[] getParameters() {
        return params;
    }

    /**
     * Gets the geodetic datum, which maps the ellipsoid onto actual locations on the Earth.
     *
     * @return the datum; null only for the {@link #CS_GEO} sentinel
     */
    public Datum getDatum() {
        return datum;
    }

    /**
     * Gets the projection method, which maps the ellipsoidal surface onto a plane.
     *
     * @return the projection, or null for the {@link #CS_GEO} sentinel
     */
    public Projection getProjection() {
        return proj;
    }

    /**
     * Gets the PROJ.4 parameters as a single string, each token followed by a space.
     *
     * @return the parameters space-separated, or the empty string if there are none
     */
    public String getParameterString() {
        if (params == null) return "";

        StringBuffer buf = new StringBuffer();
        for (int i = 0; i < params.length; i++) {
            buf.append(params[i]);
            buf.append(" ");
        }
        return buf.toString();
    }

    /**
     * Whether this CRS is geographic, i.e. holds longitude and latitude rather than projected
     * ordinates.
     *
     * @return true if the projection is a geographic one
     * @throws NullPointerException if this is the {@link #CS_GEO} sentinel, which has no projection
     */
    public Boolean isGeographic() {
        return proj.isGeographic();
    }

    /**
     * Creates a geographic (unprojected) {@link CoordinateReferenceSystem}
     * based on the {@link Datum} of this CRS.
     * This is useful for defining {@link CoordinateTransform}s
     * to and from geographic coordinate systems,
     * where no datum transformation is required.
     * The {@link Units} of the geographic CRS are set to {@link Units#DEGREES}.
     *
     * <h4>The prime meridian travels with the datum</h4>
     *
     * <p>It has to, and this method used to drop it. A prime meridian is a property of the geodetic
     * datum, not of the projected coordinate system laid over it: EPSG:27563 (NTF (Paris) / Lambert
     * Sud France, {@code +pm=paris}) has EPSG:4807 as its geographic CRS, and EPSG:4807 is
     * {@code +proj=longlat … +pm=paris} too. Rebuilding it as a Greenwich CRS silently turned every
     * "project this geographic coordinate into its own projected CRS" call into a
     * <em>Greenwich-to-Paris</em> conversion: on EPSG:27563 at (3.005E, 43.89N) that is 653,653.763
     * where the same-meridian answer is 841,393.487, an error of <b>187,739.724 m</b> of easting
     * delivered as an entirely plausible coordinate. 94 of the shipped EPSG definitions carry
     * {@code +pm=}.
     *
     * <p>Axis order is deliberately <em>not</em> copied: {@code +axis} describes the projected
     * coordinate system's own axes, and the geographic CRS this returns is east-north-up by
     * definition of its being a lon/lat CRS in degrees.
     *
     * @return a geographic CoordinateReferenceSystem based on the datum of this CRS
     */
    public CoordinateReferenceSystem createGeographic() {
        Datum datum = getDatum();
        Projection geoProj = new LongLatProjection();
        geoProj.setEllipsoid(getProjection().getEllipsoid());
        geoProj.setUnits(Units.DEGREES);
        String pm = primeMeridianSpec(getProjection().getPrimeMeridian());
        if (pm != null) {
            geoProj.setPrimeMeridian(pm);
        }
        geoProj.initialize();
        return new CoordinateReferenceSystem("GEO-" + datum.getCode(), null, datum, geoProj);
    }

    /**
     * Renders a {@link PrimeMeridian} as something {@link PrimeMeridian#forName(String)} will turn
     * back into the same meridian, since that is the only setter {@link Projection} exposes.
     * <p>
     * The name works for the thirteen well known meridians and for Greenwich; it does not work for a
     * numeric {@code +pm=}, whose name is the literal {@code "user-provided"} and which
     * {@code forName} would resolve to Greenwich. Those travel as decimal degrees instead.
     *
     * @param pm the meridian to render, may be null
     * @return a string {@code forName} maps back to {@code pm}, or null if there is nothing to do
     */
    private static String primeMeridianSpec(PrimeMeridian pm) {
        if (pm == null || pm.getOffsetFromGreenwich() == 0.0) {
            return null;
        }
        if (PrimeMeridian.forName(pm.getName()).equals(pm)) {
            return pm.getName();
        }
        return Double.toString(ProjectionMath.toDeg(pm.getOffsetFromGreenwich()));
    }

    public String toString() {
        return name;
    }

    @Override
    public boolean equals(Object that) {
        if (this == that) {
            return true;
        }
        if (that instanceof CoordinateReferenceSystem) {
            CoordinateReferenceSystem cr = (CoordinateReferenceSystem) that;
            // Projection equality contains Ellipsoid and Unit equality
            return datum.isEqual(cr.getDatum()) && proj.equals(cr.proj);
        }
        return false;
    }

	/**
	 * {@inheritDoc}
	 *
	 * <p><b>Bit-identical to the {@code Objects.hash(datum, proj)} it replaces.</b>
	 * {@code Objects.hash} is specified as {@code Arrays.hashCode(new Object[]{...})} and treats a
	 * null element as 0, so the chain below returns the same value for every input, null included.
	 * What is gone is the {@code Object[2]} it allocated — <b>measured at 24 B/op</b> — on the
	 * transform-cache lookup path, which the consumer hits once per geometry.
	 *
	 * <p>See {@code Projection.hashCode}, which was converted from {@code Objects.hash} earlier for
	 * the same reason, and {@code units.Unit.hashCode}, converted alongside this one. Measured with
	 * {@code ThreadMXBean.getThreadAllocatedBytes} over 2e6 warm calls, the three sites accounted
	 * for the whole of {@code AllocationBenchmark.crsHashCode}: one {@code CRS.hashCode} was 80 B/op
	 * = 24 here + 56 in {@code Unit}, and the benchmark hashes two CRSs.
	 */
	@Override
	public int hashCode() {
		int h = 1;
		h = 31 * h + (datum == null ? 0 : datum.hashCode());
		h = 31 * h + (proj == null ? 0 : proj.hashCode());
		return h;
	}
}
