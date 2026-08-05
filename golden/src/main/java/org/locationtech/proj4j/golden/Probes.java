/*
 * Copyright 2026 the Proj4J contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package org.locationtech.proj4j.golden;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Domain-derived probe points, and the checked-in table of them.
 *
 * <h2>Why probes are derived from parameters</h2>
 *
 * There is no area-of-use database in proj4j (that arrives with {@code proj4j-db}, several stages
 * away), so a CRS's plausible domain has to be inferred from the CRS's own definition. The existing
 * in-repo attempt at this is {@code core/src/test/java/org/locationtech/proj4j/proj/
 * ProjectionGridRoundTripper.gridExtent}, and the alternative — the one
 * {@code core/src/test/resources/proj4-epsg.csv} takes — is worse: <b>all 4,280 of its rows probe the
 * single point (1.0, -1.0)</b>, in the Gulf of Guinea, so a Malaysian {@code omerc} is evaluated at
 * {@code x = -1.24e7} and every row in the file is far outside its CRS's domain.
 *
 * <h2>The four defects in {@code gridExtent} that are NOT reproduced here</h2>
 *
 * <ol>
 * <li><b>{@code Double.MIN_VALUE} as a max-tracker seed.</b> {@code gridExtent:123} writes
 *     {@code new double[] {Double.MAX_VALUE, Double.MIN_VALUE}}. {@code Double.MIN_VALUE} is
 *     {@code +4.9e-324} — the smallest <em>positive</em> subnormal, not the most negative double. So
 *     for a CRS whose latitudes are all negative, {@code latExtent[1]} never moves, the guard at
 *     {@code :132} ({@code latExtent[1] > Double.MIN_VALUE}) is false, and the method silently falls
 *     back to a 10&deg; box centred on the equator. <b>Every southern-hemisphere CRS is probed in the
 *     wrong hemisphere.</b> Here there is no sentinel at all: {@link #candidates} counts what it
 *     found.</li>
 * <li><b>{@code lat == 0.0} conflated with "absent".</b> {@code updateLat:150} is
 *     {@code if (lat == 0.0) return;}. {@code +lat_0=0} is explicit, legal and extremely common —
 *     every UTM zone and every equatorial Mercator has it — and it is not the same as an absent
 *     {@code +lat_0}. Here presence is decided by whether the <em>token</em> is in the definition
 *     text and parses, so {@code 0} is a value.</li>
 * <li><b>Unbounded box height.</b> {@code gridExtent:136} is {@code gridWidth = 2 * dlat} with no
 *     cap, so {@code +lat_1=-70 +lat_2=70} yields a 280&deg;-tall box whose corners are past both
 *     poles. Here the half-height is clamped to {@link #MAX_LAT_HALF} and the resulting latitudes to
 *     &plusmn;{@link #LAT_LIMIT}.</li>
 * <li><b>No {@code cos(lat)} scaling on the longitude half-width.</b> {@code gridExtent:140-143}
 *     uses the same {@code gridWidth/2} for both axes, so a 5&deg;-wide box at 70&deg;N spans 190 km
 *     of longitude and 555 km of latitude. Here the longitude half-width is divided by
 *     {@code cos(latC)} so the box is roughly square on the ground, with a floor on the cosine
 *     ({@link #COS_FLOOR}) so it stays finite at the poles.</li>
 * </ol>
 *
 * <h2>Why the result is checked in</h2>
 *
 * {@code probes.tsv} is a committed input, written once by {@code GoldenInputs} and read by
 * {@code GoldenGenerator}, which never recomputes. Two reasons:
 * <ul>
 * <li>The derivation reads the definition <em>text</em>, not a parsed {@code Projection}, so it does
 *     not go through the code under test — but even so, a change to <em>this</em> file would move
 *     every probe and make all ~53,000 rows differ at once, hiding whatever real change was under
 *     review. Freezing the probes makes the input set an input.</li>
 * <li>A run against released 1.4.3 and a run against the working tree must use bit-identical probes,
 *     or the comparison means nothing. Hex doubles in a file guarantee that; agreeing derivation code
 *     across two classpaths does not.</li>
 * </ul>
 * Regenerate deliberately, and expect the whole table to move:
 * {@code mvn -Pgolden -pl golden -am test -Dtest=GoldenInputsTest -Dgolden.regenerate.inputs=true}.
 */
public final class Probes {

    /** Probes per key. Fixed at 5 by the plan: the centre plus the four corners. */
    public static final int PROBE_COUNT = 5;

    /** Half-height when the definition carries no usable latitude parameter, in degrees. */
    public static final double DEFAULT_LAT_HALF = 5.0;
    /** Floor on the half-height, so a degenerate span still produces five distinct points. */
    public static final double MIN_LAT_HALF = 1.0;
    /** Cap on the half-height. Fixes {@code gridExtent}'s unbounded {@code 2 * dlat}. */
    public static final double MAX_LAT_HALF = 15.0;
    /** Cap on the half-width, after cosine scaling. */
    public static final double MAX_LON_HALF = 60.0;
    /** Floor on {@code cos(latC)}, so a polar CRS does not get an infinite half-width. */
    public static final double COS_FLOOR = 0.05;
    /**
     * Latitudes are clamped here rather than to exactly &plusmn;90. Not for numerical safety —
     * proj4j is entitled to be asked for a pole — but because a probe exactly at a pole makes a large
     * family of projections degenerate to the same singular answer, so the row stops distinguishing
     * anything. The poles are covered explicitly by the synthetic matrix instead.
     */
    public static final double LAT_LIMIT = 89.0;

    /**
     * The angular parameters consulted, in precedence order for the longitude centre and as a set for
     * the latitude span. These are the eight the plan names.
     */
    public static final String[] LAT_KEYS = {"lat_0", "lat_1", "lat_2", "lat_ts"};

    private Probes() {
    }

    /** One key's five probe points; {@code [i][0]} is longitude, {@code [i][1]} latitude, degrees. */
    public static double[][] derive(List<String> params) {
        Def d = new Def(params);

        // ---- latitude centre and half-height, from however many of the four we found ------------
        int found = 0;
        double lo = 0.0;
        double hi = 0.0;
        for (int i = 0; i < LAT_KEYS.length; i++) {
            double v = d.angle(LAT_KEYS[i]);
            if (Double.isNaN(v)) continue;          // absent or unparseable -- NOT "is zero"
            if (v < -90.0 || v > 90.0) continue;    // nonsense in the def; ignore rather than propagate
            if (found == 0) {
                lo = v;
                hi = v;
            } else {
                if (v < lo) lo = v;
                if (v > hi) hi = v;
            }
            found++;
        }

        double latC;
        double latHalf;
        if (found == 0) {
            // No latitude information in the definition at all. This is the honest answer for a
            // geographic CRS, which has no domain to declare. Two parameter-derived hints still
            // apply, both of them properties of the declared datum rather than guesses:
            //   +south          -> the southern hemisphere, which is the entire point of the flag
            //   +datum=NAD27/83 -> North America, which is where those datums are defined, and which
            //                      is the difference between exercising the grid-shift path and not.
            String datum = d.value("datum");
            if ("NAD27".equals(datum) || "NAD83".equals(datum)) {
                latC = 45.0;
                latHalf = DEFAULT_LAT_HALF;
            } else if (d.has("south")) {
                latC = -25.0;
                latHalf = DEFAULT_LAT_HALF;
            } else {
                latC = 0.0;
                latHalf = DEFAULT_LAT_HALF;
            }
        } else {
            latC = (lo + hi) / 2.0;
            double span = hi - lo;
            latHalf = span > 0.0 ? clamp(span, MIN_LAT_HALF, MAX_LAT_HALF) : DEFAULT_LAT_HALF;
        }

        // ---- longitude centre -------------------------------------------------------------------
        // +lonc beats +lon_0 in omerc, so it is consulted first; +zone is a last resort and is the
        // only place a longitude can come from an integer. TransverseMercatorProjection.setUTMZone
        // overwrites lon_0 from the zone anyway, so a def carrying both is probed on the zone's
        // meridian either way -- but +lon_0 is what the text says, so the text wins here.
        double lonC = d.angle("lon_0");
        if (Double.isNaN(lonC)) lonC = d.angle("lonc");
        if (Double.isNaN(lonC)) {
            int zone = d.integer("zone");
            // PROJ's zone->central-meridian relation. Range-checked, because
            // TransverseMercatorProjection.setUTMZone does not check it and +zone=0 or +zone=99 must
            // not silently produce a probe on a meridian that does not exist.
            if (zone >= 1 && zone <= 60) lonC = -183.0 + 6.0 * zone;
        }
        if (Double.isNaN(lonC)) {
            String datum = d.value("datum");
            lonC = ("NAD27".equals(datum) || "NAD83".equals(datum)) ? -100.0 : 0.0;
        }
        if (lonC < -360.0 || lonC > 360.0) lonC = 0.0;

        // ---- the box ----------------------------------------------------------------------------
        double cos = Math.cos(Math.toRadians(latC));
        if (cos < COS_FLOOR) cos = COS_FLOOR;
        double lonHalf = clamp(latHalf / cos, MIN_LAT_HALF, MAX_LON_HALF);

        double latLo = Math.max(latC - latHalf, -LAT_LIMIT);
        double latHi = Math.min(latC + latHalf, LAT_LIMIT);
        double latMid = clamp(latC, -LAT_LIMIT, LAT_LIMIT);
        double lonLo = wrapLon(lonC - lonHalf);
        double lonHi = wrapLon(lonC + lonHalf);
        double lonMid = wrapLon(lonC);

        double[][] p = new double[PROBE_COUNT][2];
        p[0][0] = lonMid; p[0][1] = latMid;   // centre
        p[1][0] = lonLo;  p[1][1] = latLo;    // SW
        p[2][0] = lonHi;  p[2][1] = latLo;    // SE
        p[3][0] = lonLo;  p[3][1] = latHi;    // NW
        p[4][0] = lonHi;  p[4][1] = latHi;    // NE
        return p;
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /**
     * Wraps into {@code [-180, 180)}. Uses {@code floor} rather than a {@code while} loop: proj4j's
     * own {@code ProjectionMath.normalizeLongitude} is an unbounded {@code while} that runs ~1.6e17
     * iterations for 1e18 radians, and there is no reason for the golden suite to import that shape.
     */
    static double wrapLon(double lon) {
        double w = lon - 360.0 * Math.floor((lon + 180.0) / 360.0);
        // floor() can leave exactly +180.0 for inputs a hair below a multiple of 360 due to the
        // division; normalise so the wrap is total.
        if (w >= 180.0) w -= 360.0;
        if (w < -180.0) w += 360.0;
        return w;
    }

    /** Read-only view of a parameter list, for the derivation only. */
    static final class Def {
        private final List<String> params;

        Def(List<String> params) {
            this.params = params;
        }

        String value(String key) {
            String prefix = "+" + key + "=";
            for (int i = 0; i < params.size(); i++) {
                String p = params.get(i);
                // PROJ takes the FIRST occurrence of a duplicated key; proj4j's HashMap takes the
                // last. The probe derivation follows PROJ, because the probe is a property of the
                // definition, not of whichever parser is being tested.
                if (p.startsWith(prefix)) return p.substring(prefix.length());
            }
            return null;
        }

        boolean has(String key) {
            String bare = "+" + key;
            String prefix = bare + "=";
            for (int i = 0; i < params.size(); i++) {
                String p = params.get(i);
                if (p.equals(bare) || p.startsWith(prefix)) return true;
            }
            return false;
        }

        double angle(String key) {
            String v = value(key);
            return v == null ? Double.NaN : Angles.parseDegrees(v);
        }

        int integer(String key) {
            String v = value(key);
            if (v == null) return -1;
            for (int i = 0; i < v.length(); i++) {
                if (v.charAt(i) < '0' || v.charAt(i) > '9') return -1;
            }
            try {
                return Integer.parseInt(v);
            } catch (NumberFormatException e) {
                return -1;
            }
        }
    }

    // ----------------------------------------------------------------- the table

    /** The checked-in probe table: {@code (section, key)} to five {@code (lon, lat)} pairs. */
    public static final class Table {
        private final Map<String, double[][]> byKey = new HashMap<String, double[][]>();

        public boolean has(String section, String key) {
            return byKey.containsKey(section + '\t' + key);
        }

        public double[][] get(String section, String key) {
            return byKey.get(section + '\t' + key);
        }

        public int size() {
            return byKey.size();
        }

        void put(String section, String key, double[][] p) {
            byKey.put(section + '\t' + key, p);
        }
    }

    public static final String HEADER = "section\tkey\tprobe\tlon\tlat";

    public static Table read(File f) throws IOException {
        Table t = new Table();
        BufferedReader r = GoldenFormat.reader(f);
        try {
            String line = r.readLine();
            if (line == null) throw new IOException(f + " is empty");
            if (!line.equals(HEADER)) throw new IOException(f + ": unexpected header: " + line);
            while ((line = r.readLine()) != null) {
                if (line.isEmpty()) continue;
                String[] c = GoldenFormat.split(line, 5);
                int idx = Integer.parseInt(c[2]);
                if (idx < 0 || idx >= PROBE_COUNT) {
                    throw new IOException(f + ": probe index out of range: " + line);
                }
                double[][] p = t.get(c[0], c[1]);
                if (p == null) {
                    p = new double[PROBE_COUNT][2];
                    for (int i = 0; i < PROBE_COUNT; i++) {
                        p[i][0] = Double.NaN;
                        p[i][1] = Double.NaN;
                    }
                    t.put(c[0], c[1], p);
                }
                p[idx][0] = GoldenFormat.unhex(c[3]);
                p[idx][1] = GoldenFormat.unhex(c[4]);
            }
        } finally {
            r.close();
        }
        return t;
    }

    /** Writes the table in the golden total order. */
    public static void write(File f, List<Entry> entries) throws IOException {
        List<Entry> sorted = new ArrayList<Entry>(entries);
        Collections.sort(sorted, new java.util.Comparator<Entry>() {
            public int compare(Entry a, Entry b) {
                int c = a.section.compareTo(b.section);
                if (c != 0) return c;
                return a.key.compareTo(b.key);
            }
        });
        Writer w = GoldenFormat.writer(f);
        try {
            w.write(HEADER);
            w.write('\n');
            for (int i = 0; i < sorted.size(); i++) {
                Entry e = sorted.get(i);
                for (int p = 0; p < PROBE_COUNT; p++) {
                    GoldenFormat.writeRow(w, e.section, e.key, Integer.toString(p),
                            GoldenFormat.hex(e.probes[p][0]), GoldenFormat.hex(e.probes[p][1]));
                }
            }
        } finally {
            w.close();
        }
    }

    public static final class Entry {
        public final String section;
        public final String key;
        public final double[][] probes;

        public Entry(String section, String key, double[][] probes) {
            this.section = section;
            this.key = key;
            this.probes = probes;
        }
    }
}
