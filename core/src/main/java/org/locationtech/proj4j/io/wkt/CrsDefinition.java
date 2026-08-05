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

import java.util.ArrayList;
import java.util.List;

/**
 * A coordinate reference system exactly as a WKT or PROJJSON document described it, before any
 * policy has been applied to it.
 * <p>
 * This is the shared spine of this package and of
 * {@code org.locationtech.proj4j.io.projjson}: the WKT reader and the PROJJSON reader both
 * produce one, and the WKT writer and the PROJJSON writer both consume one. Keeping it separate
 * from {@link org.locationtech.proj4j.CoordinateReferenceSystem} is what lets a caller inspect
 * the declared axis order, the authority identifiers and the datum anchor — none of which the
 * proj4j CRS model can hold — and is what makes writing back what was read possible.
 */
public final class CrsDefinition {

    /**
     * What kind of CRS this is. Deliberately a small closed set: these are the kinds this library
     * can either build a {@link org.locationtech.proj4j.CoordinateReferenceSystem} from, or
     * faithfully round-trip.
     */
    public enum Kind {
        /** A 2D or 3D geographic CRS: {@code GEOGCRS}, {@code GEOGCS}, WKT2 {@code GEODCRS} with an ellipsoidal CS. */
        GEOGRAPHIC,
        /** A geocentric CRS: {@code GEODCRS} with a Cartesian CS. */
        GEOCENTRIC,
        /** {@code PROJCRS} / {@code PROJCS}. */
        PROJECTED,
        /** {@code VERTCRS} / {@code VERT_CS}. */
        VERTICAL,
        /** {@code COMPOUNDCRS} / {@code COMPD_CS}. */
        COMPOUND,
        /** {@code BOUNDCRS}: a source CRS plus a transformation to a hub CRS. */
        BOUND,
        /** {@code ENGCRS} / {@code LOCAL_CS}. */
        ENGINEERING
    }

    private Kind kind;
    private String name;
    private DatumDefinition datum;
    private CoordinateSystemDefinition coordinateSystem;
    private CrsDefinition baseCrs;
    private ConversionDefinition conversion;
    private final List<CrsDefinition> components = new ArrayList<CrsDefinition>();
    private CrsDefinition hubCrs;
    private double[] toWgs84;
    private final List<Identifier> ids = new ArrayList<Identifier>();
    private String remark;
    private String scope;
    private String areaDescription;
    private double[] boundingBox;
    private WktDialect sourceDialect;
    private String proj4Extension;

    public Kind getKind() {
        return kind;
    }

    public void setKind(Kind kind) {
        this.kind = kind;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    /**
     * The geodetic or vertical reference frame. For a projected CRS this is {@code null} and the
     * datum is that of {@link #getBaseCrs()}.
     */
    public DatumDefinition getDatum() {
        return datum;
    }

    public void setDatum(DatumDefinition datum) {
        this.datum = datum;
    }

    /**
     * The datum of this CRS or, for a projected or bound CRS, of the CRS it derives from.
     *
     * @throws WktParseException if the definition graph nests more than
     *                           {@link WktLimits#MAX_CRS_DEPTH} deep. The readers already refuse a
     *                           document that would build such a graph; this bounds the walk for a
     *                           graph a caller assembled by hand, including one that contains
     *                           itself.
     */
    public DatumDefinition resolveDatum() {
        return resolveDatum(1);
    }

    private DatumDefinition resolveDatum(int depth) {
        checkDepth(depth);
        if (datum != null) {
            return datum;
        }
        if (baseCrs != null) {
            return baseCrs.resolveDatum(depth + 1);
        }
        for (int i = 0; i < components.size(); i++) {
            DatumDefinition d = components.get(i).resolveDatum(depth + 1);
            if (d != null) {
                return d;
            }
        }
        return null;
    }

    private static void checkDepth(int depth) {
        if (depth > WktLimits.MAX_CRS_DEPTH) {
            throw new WktParseException("CRSs nested more than " + WktLimits.MAX_CRS_DEPTH
                    + " deep; refusing to recurse further");
        }
    }

    public CoordinateSystemDefinition getCoordinateSystem() {
        return coordinateSystem;
    }

    public void setCoordinateSystem(CoordinateSystemDefinition coordinateSystem) {
        this.coordinateSystem = coordinateSystem;
    }

    /**
     * For a projected CRS, the geographic CRS it is derived from. For a bound CRS, the CRS being
     * bound.
     */
    public CrsDefinition getBaseCrs() {
        return baseCrs;
    }

    public void setBaseCrs(CrsDefinition baseCrs) {
        this.baseCrs = baseCrs;
    }

    public ConversionDefinition getConversion() {
        return conversion;
    }

    public void setConversion(ConversionDefinition conversion) {
        this.conversion = conversion;
    }

    /**
     * The components of a compound CRS, in order.
     */
    public List<CrsDefinition> getComponents() {
        return components;
    }

    public void addComponent(CrsDefinition component) {
        components.add(component);
    }

    /**
     * For a bound CRS, the hub CRS the transformation targets — in practice always WGS 84.
     */
    public CrsDefinition getHubCrs() {
        return hubCrs;
    }

    public void setHubCrs(CrsDefinition hubCrs) {
        this.hubCrs = hubCrs;
    }

    /**
     * The Helmert parameters taking this CRS to WGS 84, in PROJ's {@code +towgs84} order and
     * units: three translations in metres, then optionally three rotations in arc-seconds and a
     * scale difference in parts per million. Read from WKT1's {@code TOWGS84[]} or from the
     * abridged transformation of a {@code BOUNDCRS}. {@code null} when the document declared
     * none.
     */
    public double[] getToWgs84() {
        return toWgs84;
    }

    public void setToWgs84(double[] toWgs84) {
        this.toWgs84 = toWgs84;
    }

    /**
     * The Helmert parameters of this CRS or of the CRS it derives from. WKT1 carries
     * {@code TOWGS84} inside the {@code DATUM} of the {@code GEOGCS}, so for a {@code PROJCS} the
     * parameters live on the base CRS, not on the projected one.
     */
    public double[] resolveToWgs84() {
        return resolveToWgs84(1);
    }

    private double[] resolveToWgs84(int depth) {
        checkDepth(depth);
        if (toWgs84 != null) {
            return toWgs84;
        }
        if (baseCrs != null) {
            return baseCrs.resolveToWgs84(depth + 1);
        }
        for (int i = 0; i < components.size(); i++) {
            double[] t = components.get(i).resolveToWgs84(depth + 1);
            if (t != null) {
                return t;
            }
        }
        return null;
    }

    /**
     * Every identifier the document declared, in order. WKT1 allows one {@code AUTHORITY} and
     * WKT2 allows several {@code ID}s.
     */
    public List<Identifier> getIds() {
        return ids;
    }

    public void addId(Identifier id) {
        ids.add(id);
    }

    /**
     * The first identifier, or {@code null}.
     */
    public Identifier getId() {
        return ids.isEmpty() ? null : ids.get(0);
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public String getAreaDescription() {
        return areaDescription;
    }

    public void setAreaDescription(String areaDescription) {
        this.areaDescription = areaDescription;
    }

    /**
     * The geographic bounding box as {@code {southLatitude, westLongitude, northLatitude,
     * eastLongitude}} in degrees, in WKT2's {@code BBOX[]} order, or {@code null}.
     */
    public double[] getBoundingBox() {
        return boundingBox;
    }

    public void setBoundingBox(double[] boundingBox) {
        this.boundingBox = boundingBox;
    }

    /**
     * Which dialect this definition was read from, or {@code null} if it was not read from WKT.
     */
    public WktDialect getSourceDialect() {
        return sourceDialect;
    }

    public void setSourceDialect(WktDialect sourceDialect) {
        this.sourceDialect = sourceDialect;
    }

    /**
     * The PROJ string carried by a WKT1 {@code EXTENSION["PROJ4",...]} clause, which GDAL emits
     * for CRSs WKT1 cannot express. Retained, and used in preference to the reconstructed
     * parameters only when the conversion method is otherwise unmappable.
     */
    public String getProj4Extension() {
        return proj4Extension;
    }

    public void setProj4Extension(String proj4Extension) {
        this.proj4Extension = proj4Extension;
    }

    /**
     * The horizontal component of this CRS: itself if it is geographic, geocentric or projected,
     * the bound CRS's source, or a compound CRS's first non-vertical component.
     */
    public CrsDefinition horizontalComponent() {
        return horizontalComponent(1);
    }

    private CrsDefinition horizontalComponent(int depth) {
        checkDepth(depth);
        switch (kind) {
            case BOUND:
                return baseCrs == null ? null : baseCrs.horizontalComponent(depth + 1);
            case COMPOUND:
                for (int i = 0; i < components.size(); i++) {
                    CrsDefinition c = components.get(i).horizontalComponent(depth + 1);
                    if (c != null) {
                        return c;
                    }
                }
                return null;
            case VERTICAL:
                return null;
            default:
                return this;
        }
    }

    public String toString() {
        return kind + "[" + name + "]";
    }
}
