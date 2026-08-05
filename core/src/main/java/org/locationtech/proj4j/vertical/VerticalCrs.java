/*
 * Copyright 2026 The Proj4J Contributors.
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
package org.locationtech.proj4j.vertical;

import java.io.Serializable;

/**
 * A vertical coordinate reference system — the right-hand half of a compound CRS such as
 * {@code EPSG:4326+5773}.
 *
 * <h2>What a vertical CRS reduces to, in proj-string terms</h2>
 *
 * <p>PROJ 9.8.1 exports a compound CRS as the horizontal CRS's proj-string plus at most
 * three tokens. Verbatim, {@code projinfo EPSG:4326+5773 -o PROJ}:
 *
 * <pre>
 * +proj=longlat +datum=WGS84 +geoidgrids=us_nga_egm96_15.tif +geoid_crs=WGS84 +vunits=m
 *   +no_defs +type=crs</pre>
 *
 * <p>So a vertical CRS contributes exactly {@code +geoidgrids}, {@code +geoid_crs} and
 * {@code +vunits}, and this class carries no more than that plus its identity. In
 * particular it is <em>not</em> a {@code CoordinateReferenceSystem}: it has no projection
 * and no horizontal datum, and pretending otherwise would let it reach code that assumes
 * both.
 *
 * <h2>Two grid names, deliberately</h2>
 *
 * <p>{@link #geoidGrids()} is the name PROJ emits — a GeoTIFF. {@link #legacyGeoidGrids()}
 * is the GTX name {@code proj.db}'s {@code grid_alternatives} table records as its
 * predecessor ({@code egm96_15.gtx} for {@code us_nga_egm96_15.tif}), and it is the one
 * {@link org.locationtech.proj4j.datum.VerticalGrid} can actually read, because this
 * library has no GeoTIFF reader yet. Collapsing the two into one field would have meant
 * either emitting a proj-string PROJ does not emit or naming a file we cannot open.
 *
 * <h2>Down-positive axes are recorded but not expressible</h2>
 *
 * <p>{@code EPSG:5715} (MSL depth) and {@code EPSG:6357} (NAVD88 depth) use coordinate
 * system {@code EPSG:6498}, whose single axis points <em>down</em>. PROJ's own PROJ.4
 * export drops that — {@code projinfo EPSG:4326+5715 -o PROJ} is indistinguishable from
 * {@code EPSG:4326+5714} — because a legacy proj-string can express it only as
 * {@code +axis=end}, which also reorders. {@link #isDepth()} therefore records the fact so
 * a caller can refuse rather than silently return a height where a depth was asked for.
 *
 * <p>Immutable and thread-safe.
 *
 * @since 1.5
 */
public final class VerticalCrs implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String authority;
    private final String code;
    private final String name;
    private final String geoidGrids;
    private final String legacyGeoidGrids;
    private final String geoidCrs;
    private final String verticalUnits;
    private final boolean depth;

    /**
     * @param authority        the authority, conventionally {@code "EPSG"}; may be {@code null}
     * @param code             the authority code, e.g. {@code "5773"}; may be {@code null}
     * @param name             the human-readable name, e.g. {@code "EGM96 height"}
     * @param geoidGrids       the {@code +geoidgrids} list PROJ emits, or {@code null} when the
     *                         vertical CRS has no geoid model expressible as a proj-string
     * @param legacyGeoidGrids the GTX equivalent from {@code grid_alternatives}, or {@code null}
     * @param geoidCrs         the {@code +geoid_crs} value, or {@code null}
     * @param verticalUnits    the {@code +vunits} id; {@code null} is treated as {@code "m"}
     * @param depth            whether the single axis is down-positive
     */
    public VerticalCrs(final String authority, final String code, final String name,
                       final String geoidGrids, final String legacyGeoidGrids,
                       final String geoidCrs, final String verticalUnits, final boolean depth) {
        this.authority = authority;
        this.code = code;
        this.name = name;
        this.geoidGrids = emptyToNull(geoidGrids);
        this.legacyGeoidGrids = emptyToNull(legacyGeoidGrids);
        this.geoidCrs = emptyToNull(geoidCrs);
        this.verticalUnits = verticalUnits == null || verticalUnits.isEmpty() ? "m" : verticalUnits;
        this.depth = depth;
    }

    private static String emptyToNull(final String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    /** @return the authority, or {@code null} for an anonymous vertical CRS. */
    public String getAuthority() {
        return authority;
    }

    /** @return the authority code, or {@code null}. */
    public String getCode() {
        return code;
    }

    /** @return {@code authority:code}, or the name when there is no code. */
    public String getIdentifier() {
        if (authority == null || code == null) {
            return name;
        }
        return authority + ":" + code;
    }

    /** @return the human-readable name. */
    public String getName() {
        return name;
    }

    /**
     * @return the {@code +geoidgrids} list as PROJ 9.8.1 emits it (GeoTIFF names), or
     *         {@code null} when this vertical CRS carries no geoid model in a proj-string
     */
    public String geoidGrids() {
        return geoidGrids;
    }

    /**
     * @return the GTX name {@code proj.db}'s {@code grid_alternatives} gives for
     *         {@link #geoidGrids()}, which is the form this library can read, or {@code null}
     */
    public String legacyGeoidGrids() {
        return legacyGeoidGrids;
    }

    /**
     * @return the grid name to hand to {@link VGridShiftOperator}: the GTX alternative when
     *         there is one, else the name PROJ emits, else {@code null}
     */
    public String readableGeoidGrids() {
        return legacyGeoidGrids != null ? legacyGeoidGrids : geoidGrids;
    }

    /**
     * {@code +geoid_crs}, which {@code pj_init} does not read at all — only the CRS parser
     * honours it, and only when {@code +geoidgrids} is also present
     * ({@code io.cpp}'s hand-coded exception). It is carried so a round-tripped proj-string
     * matches PROJ's, not because it changes any arithmetic here.
     *
     * @return the value, or {@code null}
     */
    public String geoidCrs() {
        return geoidCrs;
    }

    /** @return the {@code +vunits} id; never {@code null}, defaulting to {@code "m"}. */
    public String verticalUnits() {
        return verticalUnits;
    }

    /** @return whether the axis is down-positive, i.e. a depth rather than a height. */
    public boolean isDepth() {
        return depth;
    }

    /** @return whether a geoid model is available in any form. */
    public boolean hasGeoidModel() {
        return geoidGrids != null || legacyGeoidGrids != null;
    }

    /**
     * The tokens this vertical CRS contributes to a proj-string, in PROJ's own order.
     *
     * @param useReadableGridName {@code true} to name the GTX file this library can read,
     *                            {@code false} to reproduce PROJ's GeoTIFF spelling exactly
     * @return a possibly empty token string with no leading or trailing space
     */
    public String projTokens(final boolean useReadableGridName) {
        final StringBuilder sb = new StringBuilder();
        final String grid = useReadableGridName ? readableGeoidGrids() : geoidGrids;
        if (grid != null) {
            sb.append("+geoidgrids=").append(grid);
            if (geoidCrs != null) {
                sb.append(" +geoid_crs=").append(geoidCrs);
            }
        }
        if (sb.length() > 0) {
            sb.append(' ');
        }
        sb.append("+vunits=").append(verticalUnits);
        return sb.toString();
    }

    @Override
    public String toString() {
        return "VerticalCrs[" + getIdentifier() + " " + name
                + (geoidGrids == null ? "" : ", geoidgrids=" + geoidGrids)
                + ", vunits=" + verticalUnits + (depth ? ", down-positive" : "") + "]";
    }
}
