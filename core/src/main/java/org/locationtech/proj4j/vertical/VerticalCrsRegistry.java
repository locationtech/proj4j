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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The vertical CRSs this library can name, and the seam through which the rest arrive.
 *
 * <h2>Why this exists at all, and how small it deliberately is</h2>
 *
 * <p><b>{@code proj4j-epsg}'s dictionary contains no vertical CRS whatsoever.</b>
 * {@code epsg/src/main/resources/proj4/nad/epsg} holds 5,755 entries and none of
 * {@code 5773}, {@code 3855}, {@code 5798}, {@code 5714}, {@code 5715}, {@code 5703} or
 * {@code 4937} is among them; neither is {@code 4979}, the WGS 84 <em>geographic 3D</em>
 * CRS. That file is a PROJ.4-era {@code +init=epsg:} dictionary and a proj-string cannot
 * express a standalone vertical CRS, so their absence is structural rather than an omission
 * to be patched.
 *
 * <p>The entries below are therefore <b>not invented</b>: each field is read out of PROJ
 * 9.8.1's own {@code proj.db} and its PROJ.4 export, and the provenance is recorded per
 * entry. They exist because {@code EPSG:4326+5773} is the case the consumer actually needs
 * and because a nine-row table is a smaller lie than a missing feature. Anything not listed
 * is reported as unknown, by name, rather than guessed at — see
 * {@link #require(String, String)}.
 *
 * <p>The real fix is {@code proj4j-db}, which will supply all 900-odd EPSG vertical CRSs
 * from the shipped database. When it lands it registers itself through
 * {@link #register(VerticalCrs)} and these built-ins become a fallback of last resort.
 *
 * <h2>How each row was obtained</h2>
 *
 * <pre>
 * name, datum, coordinate system:
 *   sqlite3 proj.db "select code,name,datum_code,coordinate_system_code
 *                    from vertical_crs where auth_name='EPSG'"
 * geoidgrids / geoid_crs / vunits:
 *   projinfo EPSG:4326+&lt;code&gt; -o PROJ            # the last line of its output
 * the GTX alternative:
 *   sqlite3 proj.db "select proj_grid_name, old_proj_grid_name from grid_alternatives"
 * down-positive:
 *   coordinate_system_code 6498 is 'Depth' (axis orientation 'down'); 6499 is
 *   'Gravity-related height' ('up')
 * </pre>
 *
 * <p>Five of the nine rows carry <b>no</b> {@code +geoidgrids}, and that is what PROJ
 * itself emits for them: {@code EPSG:4326+5714}, {@code +5715}, {@code +5703},
 * {@code +6357} and {@code +5798} all export as plain
 * {@code +proj=longlat +datum=WGS84 +vunits=m}. For those, height is passed through
 * unshifted — which is exactly PROJ's "ballpark vertical transformation" and is reported as
 * such by {@link VerticalCrs#hasGeoidModel()} returning {@code false}.
 *
 * <p>Thread-safe; registrations are visible to every subsequent lookup.
 *
 * @since 1.5
 */
public final class VerticalCrsRegistry {

    private static final Map<String, VerticalCrs> BUILT_IN =
            new ConcurrentHashMap<String, VerticalCrs>();

    private static final Map<String, VerticalCrs> REGISTERED =
            new ConcurrentHashMap<String, VerticalCrs>();

    static {
        // EGM96 height. The one EPSG:4326+5773 needs, and the only geoid model for which a
        // grid ships in any form this library can read.
        add(new VerticalCrs("EPSG", "5773", "EGM96 height",
                "us_nga_egm96_15.tif", "egm96_15.gtx", "WGS84", "m", false));
        // EGM2008 height. 80,585,622 B as GeoTIFF - packaging-and-data.md marks it "do not
        // ship" - so naming it here is a promise about identity, not about availability.
        add(new VerticalCrs("EPSG", "3855", "EGM2008 height",
                "us_nga_egm08_25.tif", "egm08_25.gtx", "WGS84", "m", false));
        // ODN height. Great Britain; pairs with EPSG:27700.
        add(new VerticalCrs("EPSG", "5701", "ODN height",
                "uk_os_OSGM15_GB.tif", null, "WGS84", "m", false));
        // EGM84 height. PROJ exports no +geoidgrids for this one: proj.db's geoid model for
        // EPSG:5798 has no grid_alternatives row, so there is nothing to name.
        add(new VerticalCrs("EPSG", "5798", "EGM84 height",
                null, null, null, "m", false));
        // NAVD88 height / depth. Ditto - the NAVD88 geoid models are region-specific
        // (us_noaa_geoid*_conus.tif and friends) and PROJ picks one per operation rather
        // than per CRS, so no single +geoidgrids exists.
        add(new VerticalCrs("EPSG", "5703", "NAVD88 height",
                null, null, null, "m", false));
        add(new VerticalCrs("EPSG", "6357", "NAVD88 depth",
                null, null, null, "m", true));
        // MSL height / depth. Genuinely model-free: EPSG:5100 is "Mean Sea Level" with no
        // realisation, which is why every transformation involving it is ballpark.
        add(new VerticalCrs("EPSG", "5714", "MSL height",
                null, null, null, "m", false));
        add(new VerticalCrs("EPSG", "5715", "MSL depth",
                null, null, null, "m", true));
        // Ellipsoidal height, the vertical half of a geographic 3D CRS. Not an EPSG vertical
        // CRS - EPSG models 3D geographic CRSs as single objects (4979, 4937) rather than as
        // compounds - but it is what "EPSG:4326 with a height" means, and naming it lets
        // CompoundCrs express a 3D geographic CRS without a geoid.
        add(new VerticalCrs(null, null, "Ellipsoidal height",
                null, null, null, "m", false));
    }

    private VerticalCrsRegistry() {
        throw new AssertionError("no instances");
    }

    private static void add(final VerticalCrs crs) {
        if (crs.getAuthority() != null && crs.getCode() != null) {
            BUILT_IN.put(key(crs.getAuthority(), crs.getCode()), crs);
        }
    }

    private static String key(final String authority, final String code) {
        final String auth = authority == null || authority.isEmpty() ? "EPSG" : authority;
        return auth.toUpperCase(Locale.ROOT) + ":" + code.trim();
    }

    /**
     * Adds or replaces a vertical CRS. Intended for {@code proj4j-db} and for an application
     * that has its own authority data; a registration wins over a built-in with the same key.
     *
     * @param crs the vertical CRS; must carry both an authority and a code
     * @throws IllegalArgumentException if it has no authority or no code
     */
    public static void register(final VerticalCrs crs) {
        if (crs == null || crs.getAuthority() == null || crs.getCode() == null) {
            throw new IllegalArgumentException(
                    "a registered VerticalCrs needs both an authority and a code");
        }
        REGISTERED.put(key(crs.getAuthority(), crs.getCode()), crs);
    }

    /** Drops every {@link #register(VerticalCrs)}ed entry, leaving the built-ins. Test hook. */
    public static void clearRegistered() {
        REGISTERED.clear();
    }

    /**
     * @param authority the authority; {@code null} or empty means {@code "EPSG"}
     * @param code      the authority code
     * @return the vertical CRS, or {@code null} when it is not known here
     */
    public static VerticalCrs find(final String authority, final String code) {
        if (code == null) {
            return null;
        }
        final String k = key(authority, code);
        final VerticalCrs registered = REGISTERED.get(k);
        return registered != null ? registered : BUILT_IN.get(k);
    }

    /**
     * @param authority the authority; {@code null} or empty means {@code "EPSG"}
     * @param code      the authority code
     * @return the vertical CRS; never {@code null}
     * @throws UnknownVerticalCrsException naming the code and saying where the data would
     *                                    have to come from
     */
    public static VerticalCrs require(final String authority, final String code) {
        final VerticalCrs found = find(authority, code);
        if (found != null) {
            return found;
        }
        throw new UnknownVerticalCrsException(key(authority, code),
                "vertical CRS " + key(authority, code) + " is not known to proj4j. The shipped "
                        + "proj4j-epsg dictionary (proj4/nad/epsg) contains no vertical CRS at "
                        + "all, because a PROJ.4 +init= dictionary cannot express one; only "
                        + knownCodes() + " are built in. Supply it with "
                        + "VerticalCrsRegistry.register(...), or add the proj4j-db artifact when "
                        + "it ships.");
    }

    /** @return every code this registry can resolve, sorted, in {@code AUTH:CODE} form. */
    public static List<String> knownCodes() {
        final List<String> out = new ArrayList<String>(BUILT_IN.keySet());
        for (final String k : REGISTERED.keySet()) {
            if (!out.contains(k)) {
                out.add(k);
            }
        }
        Collections.sort(out);
        return Collections.unmodifiableList(out);
    }

    /**
     * The vertical half of a geographic 3D CRS: an ellipsoidal height in metres, with no
     * geoid model.
     *
     * @return a shared, anonymous vertical CRS
     */
    public static VerticalCrs ellipsoidalHeight() {
        return new VerticalCrs(null, null, "Ellipsoidal height", null, null, null, "m", false);
    }
}
