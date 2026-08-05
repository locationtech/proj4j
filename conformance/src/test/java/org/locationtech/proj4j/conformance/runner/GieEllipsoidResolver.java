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
package org.locationtech.proj4j.conformance.runner;

import org.locationtech.proj4j.datum.Ellipsoid;
import org.locationtech.proj4j.gie.GieComparator;

/**
 * Picks the ellipsoid gie's {@code proj_lpz_dist} would measure on, from an operation's argument
 * string.
 *
 * <h2>Why the runner has to do this at all</h2>
 *
 * <p>{@code proj_lp_dist} calls {@code geod_inverse(P->geod, ...)}, and {@code P->geod} was
 * initialised from {@code P->a} and {@code P->f} — so the metric depends on the operation's own
 * ellipsoid. Two defaults are in play and they are not the same one
 * ({@code reference/gie-comparator.md}, trap 4):
 *
 * <ul>
 *   <li>a bare {@code operation +proj=X} naming no ellipsoid gets <strong>WGS84</strong>
 *       ({@code 9.8.1:src/init.cpp:576-581});</li>
 *   <li>a {@code +proj=pipeline} with no global {@code +ellps} gets <strong>GRS80</strong>
 *       ({@code 9.8.1:src/pipeline.cpp:338-351}).</li>
 * </ul>
 *
 * <h2>How much precision this actually needs</h2>
 *
 * <p>Less than it looks. The comparator measures the geodesic distance between two coordinates that
 * are, in a passing test, millimetres apart; to first order that distance is proportional to the
 * ellipsoid's local radius of curvature, so an error in {@code a} or {@code f} is a
 * <em>relative</em> error in the deviation, not an additive one. Confusing GRS80 with WGS84 (a
 * flattening difference of 2e-8 relative) perturbs a 1 mm deviation by about 2e-8 mm. Even
 * confusing WGS84 with Clarke 1866 — a 0.5% difference in {@code a} — perturbs it by 0.5%, which
 * changes no verdict in a corpus whose tightest tolerance is 10 nm against deviations that are
 * either ~0 or grossly over.
 *
 * <p>That is why this class reads the argument text rather than asking proj4j to parse it: full
 * fidelity to {@code pj_ellipsoid}'s first-match-wins and modifier rules
 * ({@code reference/param-semantics.md}) buys nothing here, while a second implementation of those
 * rules living in test scope would be a liability. What is reproduced is the part that is not a
 * relative error, namely <em>which default applies</em>, and explicit shape parameters, which are
 * the cases the corpus uses on purpose ({@code gie/ellipsoid.gie} is built out of them).
 *
 * <p>Stateless; not instantiable.
 */
public final class GieEllipsoidResolver {

    /** {@code +proj=pipeline} with no global ellipsoid: GRS80. */
    private static final GieComparator PIPELINE_DEFAULT = GieComparator.grs80();

    /** Everything else with no ellipsoid: WGS84. */
    private static final GieComparator PLAIN_DEFAULT = GieComparator.wgs84();

    private GieEllipsoidResolver() {
        throw new AssertionError("no instances");
    }

    /**
     * @param args a {@code pj_shrink}-normalised operation argument string, e.g.
     *     {@code "proj=merc a=6400000 rf=297"}; may be {@code null} or empty
     * @return the comparator to measure this operation's angular deviations with; never
     *     {@code null}
     */
    public static GieComparator comparatorFor(String args) {
        String globals = globalParameters(args);
        boolean pipeline = isPipeline(globals);

        Double r = value(globals, "R");
        if (r != null && r > 0) {
            // +R makes a sphere; f = 0 exactly.
            return GieComparator.forEllipsoid(r.doubleValue(), 0.0);
        }

        Ellipsoid named = named(globals);

        Double a = value(globals, "a");
        double equatorRadius;
        if (a != null && a > 0) {
            equatorRadius = a.doubleValue();
        } else if (named != null) {
            equatorRadius = named.getEquatorRadius();
        } else {
            equatorRadius = 6378137.0;
        }

        Double f = flattening(globals);
        if (f == null && named != null) {
            // Ellipsoid keeps a and b, not a and rf, so recovering f costs a sqrt. Immaterial: see
            // the class comment on relative error.
            f = Double.valueOf(1.0 - named.getB() / named.getEquatorRadius());
        }

        if (f == null && (a == null || a <= 0)) {
            // Nothing said about the shape at all: take the whole default, with its exact
            // flattening literal rather than one reconstructed from a pole radius.
            return pipeline ? PIPELINE_DEFAULT : PLAIN_DEFAULT;
        }
        if (f == null) {
            // +a alone: PROJ leaves the flattening at the default's.
            f = Double.valueOf(pipeline ? 1.0 / 298.257222101 : 1.0 / 298.257223563);
        }

        // gie/ellipsoid.gie exists to be rejected: it contains "+a=-1", "+R=0", "+a=1 +es=-1" and
        // "+R_a +a=2 +f=2". PROJ refuses each of them, so the operation will not be created and no
        // geodesic will ever be asked for — but GeographicLib validates in its constructor
        // ("Polar semi-axis is not positive" for f >= 1), so building one eagerly would abort the whole
        // file and lose its 40 legitimate assertions along with the 4 broken ones. Fall back instead.
        GieComparator fallback = pipeline ? PIPELINE_DEFAULT : PLAIN_DEFAULT;
        if (!isFinite(equatorRadius) || equatorRadius <= 0
                || !isFinite(f.doubleValue()) || f.doubleValue() >= 1) {
            return fallback;
        }
        try {
            return GieComparator.forEllipsoid(equatorRadius, f.doubleValue());
        } catch (RuntimeException e) {
            // Belt and braces: GeographicLib's validation is not fully documented, and a corpus
            // re-vendor must never be able to turn a bad ellipsoid into a lost file.
            return fallback;
        }
    }

    /**
     * The tokens before the first {@code step}, which for a pipeline are its global parameters and
     * for anything else are the whole definition.
     *
     * @param args a shrunken argument string, may be {@code null}
     * @return the leading globals, space separated; never {@code null}
     */
    static String globalParameters(String args) {
        if (args == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(args.length());
        int i = 0;
        int n = args.length();
        while (i < n) {
            while (i < n && args.charAt(i) == ' ') {
                i++;
            }
            int start = i;
            while (i < n && args.charAt(i) != ' ') {
                i++;
            }
            if (start == i) {
                break;
            }
            String token = args.substring(start, i);
            if ("step".equals(token)) {
                break;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(token);
        }
        return out.toString();
    }

    private static boolean isPipeline(String globals) {
        return "pipeline".equals(text(globals, "proj"));
    }

    private static Ellipsoid named(String globals) {
        String name = text(globals, "ellps");
        if (name == null) {
            return null;
        }
        for (int i = 0; i < Ellipsoid.ellipsoids.length; i++) {
            if (name.equals(Ellipsoid.ellipsoids[i].getShortName())) {
                return Ellipsoid.ellipsoids[i];
            }
        }
        return null;
    }

    /** {@code rf}, {@code f}, {@code b}, {@code es} and {@code e}, in that precedence. */
    private static Double flattening(String globals) {
        Double rf = value(globals, "rf");
        if (rf != null && rf.doubleValue() != 0) {
            return Double.valueOf(1.0 / rf.doubleValue());
        }
        Double f = value(globals, "f");
        if (f != null) {
            return f;
        }
        Double b = value(globals, "b");
        Double a = value(globals, "a");
        if (b != null && a != null && a.doubleValue() != 0) {
            return Double.valueOf(1.0 - b.doubleValue() / a.doubleValue());
        }
        Double es = value(globals, "es");
        if (es != null && es.doubleValue() <= 1) {
            return Double.valueOf(1.0 - Math.sqrt(1.0 - es.doubleValue()));
        }
        Double e = value(globals, "e");
        if (e != null && Math.abs(e.doubleValue()) <= 1) {
            double esq = e.doubleValue() * e.doubleValue();
            return Double.valueOf(1.0 - Math.sqrt(1.0 - esq));
        }
        return null;
    }

    /**
     * The value of the first {@code key=value} token whose key is {@code key}. First match wins,
     * as in {@code pj_param}.
     */
    static String text(String globals, String key) {
        String needle = key + "=";
        int i = 0;
        int n = globals.length();
        while (i < n) {
            while (i < n && globals.charAt(i) == ' ') {
                i++;
            }
            int start = i;
            while (i < n && globals.charAt(i) != ' ') {
                i++;
            }
            if (start == i) {
                break;
            }
            if (globals.startsWith(needle, start)) {
                return globals.substring(start + needle.length(), i);
            }
        }
        return null;
    }

    private static Double value(String globals, String key) {
        String raw = text(globals, key);
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return Double.valueOf(Double.parseDouble(raw));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** {@code Double.isFinite} is Java 8, but spelled out here for clarity at the call sites. */
    private static boolean isFinite(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v);
    }
}
