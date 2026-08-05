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

import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Regenerates the two committed input files, {@code probes.tsv} and {@code pairs.tsv}.
 *
 * <p><b>Run this deliberately and rarely.</b> Regenerating the probes moves every probe point, so
 * every one of the ~53,000 golden rows differs and the diff carries no information. The whole reason
 * the probes are a committed file rather than a computation is that a change to
 * {@link Probes#derive} — or to a {@code Projection} getter, had the derivation gone through
 * proj4j — must not be able to silently move them.
 *
 * <p>Regenerate, then <em>immediately</em> regenerate the 1.4.3 baseline from the same inputs, in one
 * commit. A probe file and a baseline that were generated from different probes describe nothing.
 *
 * <h2>Pair curation</h2>
 *
 * {@code pairs.tsv} holds ~200 CRS&rarr;CRS pairs chosen to hit every one of the 25 combinations of
 * {@code Datum.TYPE_UNKNOWN}, {@code TYPE_WGS84}, {@code TYPE_3PARAM}, {@code TYPE_7PARAM} and
 * {@code TYPE_GRIDSHIFT} across source and target. That matters because the datum-transform decision
 * in {@code BasicCoordinateTransform} is a function of exactly this pair of types, and three separate
 * confirmed defects live in it:
 * <ul>
 * <li>{@code TYPE_UNKNOWN} is tested <em>inside</em> an ellipsoid-equality guard
 *     ({@code BasicCoordinateTransform.java:198-205}), where {@code PROJ 5.2.0:src/pj_transform.c:
 *     835-843} tests it unconditionally and <em>before</em> the identical-datums short cut;</li>
 * <li>{@code Datum.isEqual:189} compares the equator radius <em>to itself</em>, so the eccentricity
 *     guard is dead code and unequal ellipsoids can be declared equal;</li>
 * <li>{@code Datum.isEqual} nests the eccentricity check inside the radius check where upstream
 *     {@code pj_compare_datums} is an {@code ||}.</li>
 * </ul>
 * None of those is reachable from a WGS84-hub pair, which is why every curated pair excludes
 * {@code EPSG:4326}. The sources are all geographic CRS so that the probe is a lon/lat pair and the
 * inputs need no separate pinning — they come from {@code probes.tsv} like every other probe, derived
 * from the <em>source</em> CRS's own parameters.
 *
 * <p>Selection is deterministic: candidates are enumerated in the golden total order and picked by a
 * fixed coprime stride, so re-running on unchanged dictionaries reproduces the file byte for byte.
 * It is not random and there is no seed to lose.
 */
public final class GoldenInputs {

    /** Pairs per {@code (srcType, tgtType)} combination. 5 x 5 x 8 = 200 at most. */
    public static final int PER_COMBINATION = 8;

    /** Coprime strides through the candidate lists, so the picks are spread rather than adjacent. */
    private static final int SRC_STRIDE = 7;
    private static final int TGT_STRIDE = 13;

    /** CRS excluded from either end of a pair: the WGS84 geographic hub itself. */
    private static final String[] HUB_CODES = {"epsg:4326", "esri:4326", "epsg:4979", "epsg:4978"};

    private GoldenInputs() {
    }

    /** Usage: {@code GoldenInputs <goldenDir>}. Overwrites {@code probes.tsv} and {@code pairs.tsv}. */
    public static void main(String[] args) throws IOException {
        File goldenDir = new File(args.length > 0 ? args[0] : System.getProperty("golden.dir", "."));
        regenerate(goldenDir);
    }

    public static void regenerate(File goldenDir) throws IOException {
        List<RegistryDict.Def> defs = RegistryDict.readAll();
        Map<String, RegistryDict.Def> byKey = InputSet.index(defs);

        File pairsFile = new File(goldenDir, "pairs.tsv");
        int pairCount = curatePairs(defs, pairsFile);

        List<Probes.Entry> entries = new ArrayList<Probes.Entry>();
        entries.addAll(InputSet.registryProbes(defs));
        entries.addAll(InputSet.syntheticProbes());
        entries.addAll(InputSet.pairProbes(InputSet.pairs(pairsFile, byKey), byKey));

        File probesFile = new File(goldenDir, "probes.tsv");
        Probes.write(probesFile, entries);

        System.out.println("wrote " + pairsFile + " (" + pairCount + " pairs)");
        System.out.println("wrote " + probesFile + " (" + entries.size() + " keys, "
                + (entries.size() * Probes.PROBE_COUNT) + " probes)");
    }

    // ------------------------------------------------------------------------ pair curation

    private static final class Candidate {
        final String key;
        final int type;
        final boolean geographic;

        Candidate(String key, int type, boolean geographic) {
            this.key = key;
            this.type = type;
            this.geographic = geographic;
        }
    }

    private static int curatePairs(List<RegistryDict.Def> defs, File out) throws IOException {
        CRSFactory f = new CRSFactory();

        // Five datum types, 0..4. Spelled as literals rather than referencing Datum.TYPE_* so this
        // file needs no import of datum internals; the names are in the javadoc above.
        List<List<Candidate>> geoByType = newBuckets();
        List<List<Candidate>> projByType = newBuckets();

        for (int i = 0; i < defs.size(); i++) {
            RegistryDict.Def d = defs.get(i);
            if (isHub(d.key())) continue;
            int type;
            try {
                CoordinateReferenceSystem crs = f.createFromName(d.crsName());
                if (crs == null || crs.getDatum() == null) continue;
                type = crs.getDatum().getTransformType();
            } catch (Throwable t) {
                if (t instanceof VirtualMachineError) throw (VirtualMachineError) t;
                continue; // a def proj4j cannot even parse is no use as a curated endpoint
            }
            if (type < 0 || type > 4) continue;
            type = correctErasedGridshift(d, type);
            boolean geo = InputSet.isGeographic(d.proj());
            Candidate c = new Candidate(d.key(), type, geo);
            (geo ? geoByType : projByType).get(type).add(c);
        }

        TreeMap<String, String[]> rows = new TreeMap<String, String[]>();
        for (int st = 0; st <= 4; st++) {
            List<Candidate> srcs = geoByType.get(st);
            if (srcs.isEmpty()) continue;
            for (int tt = 0; tt <= 4; tt++) {
                List<Candidate> tgts = projByType.get(tt);
                // Fall back to geographic targets for a type with no projected member, so a sparse
                // combination is still represented rather than silently absent.
                if (tgts.isEmpty()) tgts = geoByType.get(tt);
                if (tgts.isEmpty()) continue;
                int taken = 0;
                for (int j = 0; taken < PER_COMBINATION && j < PER_COMBINATION * 4; j++) {
                    Candidate s = srcs.get((j * SRC_STRIDE) % srcs.size());
                    Candidate t = tgts.get((j * TGT_STRIDE + 5) % tgts.size());
                    if (s.key.equals(t.key)) continue;
                    String key = "t" + st + tt + "/" + s.key + ">" + t.key;
                    if (rows.containsKey(key)) continue;
                    rows.put(key, new String[]{key, s.key, t.key,
                            Integer.toString(st), Integer.toString(tt)});
                    taken++;
                }
            }
        }

        Writer w = GoldenFormat.writer(out);
        try {
            w.write(InputSet.PAIRS_HEADER);
            w.write('\n');
            for (String[] r : rows.values()) {
                GoldenFormat.writeRow(w, r[0], r[1], r[2], r[3], r[4]);
            }
        } finally {
            w.close();
        }
        return rows.size();
    }

    /**
     * Restores the {@code TYPE_GRIDSHIFT} classification that {@code parser/Proj4Parser.java:53}
     * erases.
     *
     * <p><b>Measured while curating this file, and it is the reason this method exists:</b> across all
     * 9,013 defs there is <b>not one geographic CRS that proj4j reports as {@code TYPE_GRIDSHIFT}</b>,
     * so five of the twenty-five source/target type combinations were unreachable. The cause is the
     * confirmed defect at {@code Proj4Parser.java:53}:
     * <pre>
     *   Datum datum = datumParam.getDatum();      // the STATIC Datum.NAD27 singleton
     *   datum.setGrids(datumParam.getGrids());    // mutates it, process-wide
     * </pre>
     * {@code EPSG:4267} is {@code +proj=longlat +datum=NAD27 +no_defs} with no {@code +nadgrids}
     * token, so {@code getGrids()} is null and the first parse of it executes
     * {@code Datum.NAD27.setGrids(null)} permanently, JVM-wide, flipping
     * {@code TYPE_GRIDSHIFT -> TYPE_UNKNOWN} for all 205 EPSG codes on that datum.
     *
     * <p>So the type recorded in {@code pairs.tsv} is the <em>declared</em> type: observed wherever
     * proj4j reports something other than {@code TYPE_UNKNOWN}, and corrected to
     * {@code TYPE_GRIDSHIFT} where the definition declares a grid-shifted datum. Curating on the
     * observed value would have selected pairs that cannot exercise the grid-shift path at all — and
     * would then have had nothing to say when the defect is fixed, which is the single change most
     * likely to move this section.
     */
    private static int correctErasedGridshift(RegistryDict.Def d, int observed) {
        if (observed != 0) return observed;          // 0 == Datum.TYPE_UNKNOWN
        String datum = d.value("datum");
        if ("NAD27".equals(datum)) return 4;         // 4 == Datum.TYPE_GRIDSHIFT
        String grids = d.value("nadgrids");
        if (grids != null && !grids.isEmpty()) return 4;
        return observed;
    }

    private static List<List<Candidate>> newBuckets() {
        List<List<Candidate>> b = new ArrayList<List<Candidate>>(5);
        for (int i = 0; i < 5; i++) b.add(new ArrayList<Candidate>());
        return b;
    }

    private static boolean isHub(String key) {
        for (int i = 0; i < HUB_CODES.length; i++) {
            if (HUB_CODES[i].equals(key)) return true;
        }
        return false;
    }
}
