/*******************************************************************************
 * Copyright 2026
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

package org.locationtech.proj4j.proj.adams;

import org.locationtech.proj4j.Registry;
import org.locationtech.proj4j.datum.Ellipsoid;
import org.locationtech.proj4j.proj.PeirceQuincuncialProjection;
import org.locationtech.proj4j.proj.Projection;
import org.locationtech.proj4j.proj.SpilhausProjection;

/**
 * Builds a {@link Projection} from the proj-string on a {@code .gie} {@code operation} line,
 * for the six operators of the adams family only.
 *
 * <p><b>Why this does not go through {@code Proj4Parser}.</b> {@code Proj4Parser} has no
 * dispatch for {@code +shape}, {@code +scrollx}, {@code +scrolly}, {@code +azi} or
 * {@code +rot}. In its default {@code PROJ_COMPATIBLE} mode an unrecognised key is retained
 * and ignored, exactly as PROJ's {@code init.cpp} does, so
 * {@code +proj=peirce_q +shape=square} parses cleanly and then projects as a
 * <em>diamond</em> — the default. Routing this test through the parser would therefore
 * measure the parser's gap rather than the projection's arithmetic, and would report every
 * non-default {@code peirce_q} block as a numeric failure with no indication of why. The gap
 * itself is asserted separately by {@link ParserParameterGapTest}, which is where a future
 * parser change will show up.
 *
 * <p>The registry <em>is</em> used to resolve {@code +proj=}, so that a missing
 * {@code register(...)} line fails this test rather than being papered over by a {@code new}.
 */
final class AdamsOperations {

    private static final Registry REGISTRY = new Registry();

    private AdamsOperations() {
    }

    /**
     * @param definition the text after {@code operation}, e.g.
     *                   {@code "+proj=peirce_q +R=6370997 +shape=square"}
     */
    static Projection build(String definition) {
        String[] token = definition.trim().split("\\s+");

        String name = null;
        Double radius = null;
        String ellps = null;
        Double lon0 = null;
        Double lat0 = null;
        Double k0 = null;
        String shape = null;
        Double scrollx = null;
        Double scrolly = null;
        Double azi = null;
        Double rot = null;

        for (String t : token) {
            String kv = t.startsWith("+") ? t.substring(1) : t;
            int eq = kv.indexOf('=');
            String key = eq < 0 ? kv : kv.substring(0, eq);
            String value = eq < 0 ? null : kv.substring(eq + 1);
            if ("proj".equals(key)) {
                name = value;
            } else if ("R".equals(key)) {
                radius = Double.valueOf(value);
            } else if ("ellps".equals(key)) {
                ellps = value;
            } else if ("lon_0".equals(key)) {
                lon0 = Double.valueOf(value);
            } else if ("lat_0".equals(key)) {
                lat0 = Double.valueOf(value);
            } else if ("k_0".equals(key) || "k".equals(key)) {
                k0 = Double.valueOf(value);
            } else if ("shape".equals(key)) {
                shape = value;
            } else if ("scrollx".equals(key)) {
                scrollx = Double.valueOf(value);
            } else if ("scrolly".equals(key)) {
                scrolly = Double.valueOf(value);
            } else if ("azi".equals(key)) {
                azi = Double.valueOf(value);
            } else if ("rot".equals(key)) {
                rot = Double.valueOf(value);
            } else {
                throw new IllegalArgumentException(
                        "unhandled key +" + key + " in operation: " + definition);
            }
        }

        Projection projection = REGISTRY.getProjection(name);
        if (projection == null) {
            throw new IllegalStateException("+proj=" + name + " is not registered");
        }

        // ell_set.cpp:92-100: +R short-circuits everything else and declares a sphere. Without
        // +R or +ellps, init.cpp:327 supplies +ellps=GRS80.
        if (radius != null) {
            projection.setEllipsoid(Ellipsoid.SPHERE);
            projection.setRadius(radius.doubleValue());
        } else if (ellps != null) {
            Ellipsoid resolved = REGISTRY.getEllipsoid(ellps);
            if (resolved == null) {
                throw new IllegalStateException("+ellps=" + ellps + " is not known");
            }
            projection.setEllipsoid(resolved);
        } else {
            projection.setEllipsoid(Ellipsoid.GRS80);
        }

        if (lon0 != null) {
            projection.setProjectionLongitudeDegrees(lon0.doubleValue());
        }
        if (lat0 != null) {
            projection.setProjectionLatitudeDegrees(lat0.doubleValue());
        }
        if (k0 != null) {
            projection.setScaleFactor(k0.doubleValue());
        }
        if (shape != null) {
            ((PeirceQuincuncialProjection) projection).setShape(shape);
        }
        if (scrollx != null) {
            ((PeirceQuincuncialProjection) projection).setScrollX(scrollx.doubleValue());
        }
        if (scrolly != null) {
            ((PeirceQuincuncialProjection) projection).setScrollY(scrolly.doubleValue());
        }
        if (azi != null) {
            ((SpilhausProjection) projection).setAziDegrees(azi.doubleValue());
        }
        if (rot != null) {
            ((SpilhausProjection) projection).setRotDegrees(rot.doubleValue());
        }

        projection.initialize();
        return projection;
    }
}
