/*
 * Copyright 2026, PROJ4J contributors
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
package org.locationtech.proj4j.io.wkt;

/**
 * A geodetic or vertical reference frame: a name, and for a geodetic frame an ellipsoid and a
 * prime meridian.
 * <p>
 * WKT2 spells the prime meridian as a sibling of {@code DATUM[]} inside the CRS while WKT1 nests
 * it there too, but PROJJSON nests it inside the datum. It is held here, on the datum, in all
 * three cases.
 */
public final class DatumDefinition {

    private String name;
    private EllipsoidDefinition ellipsoid;
    private PrimeMeridianDefinition primeMeridian;
    private Identifier id;
    private String anchor;
    private double frameEpoch = Double.NaN;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public EllipsoidDefinition getEllipsoid() {
        return ellipsoid;
    }

    public void setEllipsoid(EllipsoidDefinition ellipsoid) {
        this.ellipsoid = ellipsoid;
    }

    public PrimeMeridianDefinition getPrimeMeridian() {
        return primeMeridian;
    }

    public void setPrimeMeridian(PrimeMeridianDefinition primeMeridian) {
        this.primeMeridian = primeMeridian;
    }

    public Identifier getId() {
        return id;
    }

    public void setId(Identifier id) {
        this.id = id;
    }

    public String getAnchor() {
        return anchor;
    }

    public void setAnchor(String anchor) {
        this.anchor = anchor;
    }

    /**
     * The reference epoch of a dynamic reference frame, or {@code NaN} for a static one. Retained
     * for round-tripping only; proj4j has no time dimension.
     */
    public double getFrameEpoch() {
        return frameEpoch;
    }

    public void setFrameEpoch(double frameEpoch) {
        this.frameEpoch = frameEpoch;
    }

    public boolean isDynamic() {
        return !Double.isNaN(frameEpoch);
    }

    public String toString() {
        return String.valueOf(name);
    }
}
