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

import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.InvalidValueException;

/**
 * A compound CRS: a horizontal {@link CoordinateReferenceSystem} plus a {@link VerticalCrs},
 * as written {@code EPSG:4326+5773}.
 *
 * <h2>The composition is a proj-string, and that is not a shortcut</h2>
 *
 * <p>PROJ 9.8.1 represents a compound CRS in PROJ.4 form as exactly the horizontal CRS's
 * string with the vertical CRS's tokens appended. {@code projinfo EPSG:4326+5773 -o PROJ}:
 *
 * <pre>
 * +proj=longlat +datum=WGS84 +geoidgrids=us_nga_egm96_15.tif +geoid_crs=WGS84 +vunits=m
 *   +no_defs +type=crs</pre>
 *
 * <p>and {@code +proj=longlat +datum=WGS84 +no_defs} is verbatim what
 * {@code proj4/nad/epsg}'s {@code &lt;4326&gt;} entry says. So composing the two halves is
 * string concatenation in the correct order, and the arithmetic that makes it mean something
 * is the {@code +geoidgrids} auto-step — {@link VGridShiftOperator} — plus the
 * {@code +vunits} scaling, both of which live in the pipeline layer already.
 *
 * <p>{@link #pipelineDefinition()} is therefore the useful output: hand it to
 * {@code PipelineFactory.create} and the compound CRS becomes executable, with the vertical
 * shift applied where PROJ applies it.
 *
 * <h2>Axis order is the horizontal CRS's, unchanged</h2>
 *
 * <p>This class adds a third ordinate and changes nothing about the first two. proj4j is
 * longitude-first, so {@code EPSG:4326+5773} consumes {@code (lon, lat, h)} — <em>not</em>
 * {@code (lat, lon, h)}, which is what {@code cs2cs} would consume for the same name. That
 * is the standing {@code AxisOrderPolicy.LEGACY} promise and this type does not get to break
 * it.
 *
 * <p>Immutable, and thread-safe to the same degree the wrapped
 * {@link CoordinateReferenceSystem} is.
 *
 * @since 1.5
 */
public final class CompoundCrs implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String name;
    private final CoordinateReferenceSystem horizontal;
    private final VerticalCrs vertical;

    /**
     * @param name       a name for the compound, e.g. {@code "EPSG:4326+5773"}; may be
     *                   {@code null}
     * @param horizontal the horizontal half; must not be {@code null}
     * @param vertical   the vertical half; must not be {@code null}
     */
    public CompoundCrs(final String name, final CoordinateReferenceSystem horizontal,
                       final VerticalCrs vertical) {
        if (horizontal == null) {
            throw new InvalidValueException("a compound CRS needs a horizontal CRS");
        }
        if (vertical == null) {
            throw new InvalidValueException("a compound CRS needs a vertical CRS");
        }
        this.name = name;
        this.horizontal = horizontal;
        this.vertical = vertical;
    }

    /** @return the name as supplied, possibly {@code null}. */
    public String getName() {
        return name;
    }

    /** @return the horizontal half; never {@code null}. */
    public CoordinateReferenceSystem getHorizontal() {
        return horizontal;
    }

    /** @return the vertical half; never {@code null}. */
    public VerticalCrs getVertical() {
        return vertical;
    }

    /**
     * The proj-string PROJ 9.8.1 exports for this compound CRS, GeoTIFF grid names and all.
     *
     * <p>Comparable token-for-token against {@code projinfo <name> -o PROJ}, minus its
     * trailing {@code +type=crs}, which is a CRS-parser marker rather than an operation
     * parameter.
     *
     * @return the proj-string
     */
    public String toProjString() {
        return compose(false);
    }

    /**
     * The proj-string to execute, naming the grid file this library can actually open.
     *
     * <p>Differs from {@link #toProjString()} in one token: {@code +geoidgrids} carries the
     * GTX name from {@code proj.db}'s {@code grid_alternatives} rather than the GeoTIFF one,
     * because {@link org.locationtech.proj4j.datum.VerticalGrid} reads GTX only. When the
     * GeoTIFF reader lands the two collapse into one.
     *
     * @return a proj-string suitable for {@code PipelineFactory.create}
     */
    public String pipelineDefinition() {
        return compose(true);
    }

    private String compose(final boolean readable) {
        final StringBuilder sb = new StringBuilder(horizontalProjString());
        final String tokens = vertical.projTokens(readable);
        if (tokens.length() > 0) {
            // Appended, so a token the horizontal string already carries shadows it under
            // pj_param's first-match-wins. A horizontal CRS that already said +vunits keeps
            // its own value, which is what PROJ does for the same input.
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(tokens);
        }
        return sb.toString();
    }

    /**
     * The horizontal CRS's parameters as one string.
     *
     * @return the {@code +key=value} tokens, space separated
     */
    public String horizontalProjString() {
        final String[] params = horizontal.getParameters();
        if (params == null) {
            throw new InvalidValueException(ErrorCause.MISSING_PARAM,
                    "horizontal CRS " + horizontal.getName()
                            + " carries no parameter list, so no compound proj-string can be built");
        }
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < params.length; i++) {
            final String token = params[i];
            if (token == null || token.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(token.startsWith("+") ? token : "+" + token);
        }
        return sb.toString();
    }

    /**
     * Whether the vertical half actually shifts a height.
     *
     * <p>{@code false} means the third ordinate passes through untouched, which is PROJ's
     * "ballpark vertical transformation" — reported rather than hidden, because a caller who
     * asked for {@code EPSG:4326+5714} and got their input height back needs to know the
     * difference between "no correction was needed" and "no correction was available".
     *
     * @return whether a geoid model participates
     */
    public boolean appliesVerticalShift() {
        return vertical.hasGeoidModel();
    }

    @Override
    public String toString() {
        return "CompoundCrs[" + (name == null ? "anonymous" : name) + ": "
                + horizontal.getName() + " + " + vertical.getIdentifier() + "]";
    }
}
