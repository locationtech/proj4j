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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The golden input set: what gets transformed, in what order, in what units.
 *
 * <h2>Four sections</h2>
 * <table>
 * <tr><th>section</th><th>keys</th><th>rows</th><th>what it is</th></tr>
 * <tr><td>{@code CSV}</td><td>~4,996</td><td>1 probe each</td>
 *     <td>the existing MetaCRS CSV rows, at their own pinned coordinates</td></tr>
 * <tr><td>{@code PAIR}</td><td>~200</td><td>5 probes each</td>
 *     <td>curated non-WGS84-hub CRS&rarr;CRS pairs, one group per {@code Datum.TYPE_*} pair</td></tr>
 * <tr><td>{@code REG}</td><td>9,013</td><td>5 probes each</td>
 *     <td>every def in every registry dictionary, WGS84 lon/lat &rarr; CRS</td></tr>
 * <tr><td>{@code SYN}</td><td>~500</td><td>5 probes each</td>
 *     <td>the synthetic parameter matrix</td></tr>
 * </table>
 *
 * <h2>Why the synthetic matrix is not optional</h2>
 *
 * <b>{@code +rf=} appears in no registry dictionary and in no test CSV — zero occurrences across all
 * five dictionaries and all five CSVs, verified by grep.</b> The single largest behavioural fix in
 * this project is the {@code +rf}/{@code +f} transposition in
 * {@code parser/DatumParameters.java:119-133}, where the two setters are exactly swapped so that
 * proj4j's {@code +f} is PROJ's {@code +rf} and {@code +rf} is unusable
 * ({@code +ellps=GRS80 +rf=300} derives {@code es = -89400}). Without a synthetic matrix that fix has
 * <em>no baseline rows to move</em>, and the golden regime would report it as a no-op. The same
 * argument applies to {@code +R}, {@code +R_A}, {@code +es}, {@code +e}, {@code +pm}, {@code +axis},
 * {@code +no_uoff} and {@code +vunits}, none of which the registry data exercises meaningfully.
 *
 * <p>The matrix has three parts:
 * <ol>
 * <li><b>{@code proj/<name>}</b> — every one of PROJ 9.8.1's 188 {@code PROJ_HEAD} names plus
 *     {@code pipeline}, at one canonical parameter set. Names proj4j does not implement produce
 *     {@code EXC:...} rows, which is the point: when a projection is added, its rows change status
 *     from {@code EXC:} to {@code OK}, and the rules file declares that intended. A name list
 *     restricted to what proj4j implements today could not see a new projection arrive.</li>
 * <li><b>{@code mod/<host>/<slug>}</b> — 44 modifier parameters appended to each of 6 host
 *     projections. Appended, not merged: {@code +ellps=clrk66} and {@code +units=us-ft} therefore
 *     appear <em>twice</em> in some rows, which deliberately probes the duplicate-key precedence
 *     defect ({@code Proj4Parser.createParameterMap} uses a {@code HashMap} and keeps the
 *     <em>last</em> occurrence; PROJ keeps the <em>first</em>).</li>
 * <li><b>{@code ellps/<name>}</b> — all 46 of PROJ 9.8.1's {@code ellps.cpp} names plus
 *     {@code NAD27}, {@code NAD83} and {@code australian}. {@code australian} throws today although
 *     {@code Ellipsoid.AUSTRALIAN} exists, and {@code NWL9D}/{@code andrae} are silently degenerate
 *     ({@code Registry.java:71-72} passes the flattening in the {@code poleRadius} slot, giving
 *     {@code e} &asymp; 1); all three have a row here.</li>
 * </ol>
 */
public final class InputSet {

    /**
     * The geographic hub every {@code REG} and {@code SYN} probe starts from. Spelled as a literal
     * proj string rather than {@code createFromName("epsg:4326")} so the hub does not move if the
     * dictionaries do. Byte-identical to {@code epsg:4326}'s definition.
     */
    public static final String WGS84 = "+proj=longlat +datum=WGS84 +no_defs";

    /** The canonical parameter set the {@code proj/} and {@code mod/} sweeps build on. */
    public static final String CANONICAL =
            "+ellps=GRS80 +lat_0=45 +lon_0=10 +lat_1=30 +lat_2=60 +x_0=0 +y_0=0 +units=m";

    /** Host projections for the modifier sweep. */
    public static final String[] HOSTS = {"aea", "lcc", "longlat", "merc", "tmerc", "utm"};

    /**
     * <b>All 188</b> of PROJ 9.8.1's {@code PROJ_HEAD} names, extracted at rev {@code 9.8.1} with
     * {@code git grep -o 'PROJ_HEAD\([a-zA-Z0-9_]+'}. Asserted to be exactly 188 by the self-tests.
     *
     * <p>Deliberately the whole set, including the operation-only entries ({@code helmert},
     * {@code cart}, {@code axisswap}, {@code unitconvert}, {@code noop}, {@code pipeline},
     * {@code push}/{@code pop}/{@code set}/{@code id}/{@code name}) and the 93 projections proj4j does
     * not implement. Names proj4j rejects produce {@code EXC:} rows, and that is the point: when a
     * projection or operation lands, its five rows change status from {@code EXC:} to {@code OK} and
     * the rules engine can match the transition. A list restricted to what proj4j implements today
     * could not see a new projection arrive at all.
     */
    public static final String[] PROJ_NAMES = {
            "adams_hemi", "adams_ws1", "adams_ws2", "aea", "aeqd", "affine", "airocean", "airy",
            "aitoff", "alsk", "apian", "august", "axisswap", "bacon", "bertin1953", "bipc", "boggs",
            "bonne", "calcofi", "cart", "cass", "cc", "ccon", "cea", "chamb", "col_urban", "collg",
            "comill", "crast", "defmodel", "deformation", "denoy", "eck1", "eck2", "eck3", "eck4",
            "eck5", "eck6", "eqc", "eqdc", "eqearth", "etmerc", "euler", "fahey", "fouc", "fouc_s",
            "gall", "geoc", "geocent", "geogoffset", "geos", "gins8", "gn_sinu", "gnom", "goode",
            "gridshift", "gs48", "gs50", "gstmerc", "guyou", "hammer", "hatano", "healpix", "helmert",
            "hgridshift", "horner", "id", "igh", "igh_o", "imoll", "imoll_o", "imw_p", "isea", "kav5",
            "kav7", "krovak", "labrd", "laea", "lagrng", "larr", "lask", "latlon", "latlong", "lcc",
            "lcca", "leac", "lee_os", "longlat", "lonlat", "loxim", "lsat", "mbt_fps", "mbt_s",
            "mbtfpp", "mbtfpq", "mbtfps", "merc", "mil_os", "mill", "misrsom", "mod_krovak", "moll",
            "molobadekas", "molodensky", "murd1", "murd2", "murd3", "name", "natearth", "natearth2",
            "nell",
            "nell_h", "nicol", "noop", "nsper", "nzmg", "ob_tran", "ocea", "oea", "omerc", "ortel",
            "ortho", "patterson", "pconic", "peirce_q", "pipeline", "poly", "putp1", "putp2", "putp3",
            "pop", "push", "putp3p", "putp4p", "putp5", "putp5p", "putp6", "putp6p", "qsc", "qua_aut",
            "rhealpix",
            "robin", "rouss", "rpoly", "s2", "sch", "set", "sinu", "som", "somerc", "spilhaus",
            "stere", "sterea", "tcc", "tcea", "times", "tinshift", "tissot", "tmerc", "tobmerc",
            "topocentric", "tpeqd", "tpers", "unitconvert", "ups", "urm5", "urmfps", "utm", "vandg",
            "vandg2", "vandg3", "vandg4", "vertoffset", "vgridshift", "vitk1", "wag1", "wag2", "wag3",
            "wag4", "wag5", "wag6", "wag7", "webmerc", "weren", "wink1", "wink2", "wintri",
            "xyzgridshift",
    };

    /**
     * PROJ 9.8.1's {@code src/ellps.cpp} names (46), plus the three proj4j-only or proj4j-broken
     * ones. Probed as {@code +proj=merc +ellps=<name> ...}.
     */
    public static final String[] ELLIPSOIDS = {
            "APL4.9", "CPM", "GRS67", "GRS80", "GSK2011", "IAU76", "MERIT", "NAD27", "NAD83",
            "NWL9D", "PZ90", "SEasia", "SGS85", "WGS60", "WGS66", "WGS72", "WGS84", "airy", "andrae",
            "aust_SA", "australian", "bess_nam", "bessel", "clrk66", "clrk80", "clrk80ign", "danish",
            "delmbr", "engelis", "evrst30", "evrst48", "evrst56", "evrst69", "evrstSS", "fschr60",
            "fschr60m", "fschr68", "helmert", "hough", "intl", "kaula", "krass", "lerch", "mod_airy",
            "mprts", "new_intl", "plessis", "sphere", "walbeck",
    };

    /**
     * The modifier sweep: 44 parameters, each appended to each host. {@code slug} must be filesystem-
     * and TSV-safe and must be stable, because it is half the row key.
     */
    public static final String[][] MODIFIERS = {
            // -- ellipsoid shape. The whole reason this section exists. -----------------------------
            {"rf", "+rf=300"},
            {"f", "+f=0.003352810681182"},
            {"es", "+es=0.006694380022901"},
            {"e", "+e=0.081819191042816"},
            {"b", "+b=6356752.314140356"},
            {"a", "+a=6400000"},
            {"R", "+R=6370997"},
            // -- spherification. proj4j has R_A only, and it scales a without clearing es. ---------
            {"R_A", "+R_A"},
            {"R_V", "+R_V"},
            {"R_a", "+R_a"},
            {"R_g", "+R_g"},
            {"R_h", "+R_h"},
            {"R_lat_a", "+R_lat_a=45"},
            {"R_lat_g", "+R_lat_g=45"},
            {"R_C", "+R_C"},
            // -- datum. +datum= swallows every other datum parameter in proj4j. --------------------
            {"ellps_clrk66", "+ellps=clrk66"},
            {"datum_WGS84", "+datum=WGS84"},
            {"datum_NAD27", "+datum=NAD27"},
            {"datum_NAD83", "+datum=NAD83"},
            {"datum_potsdam", "+datum=potsdam"},
            {"datum_nad83_lower", "+datum=nad83"},
            {"towgs84_3", "+towgs84=1,2,3"},
            {"towgs84_7", "+towgs84=1,2,3,4,5,6,7"},
            {"towgs84_zero", "+towgs84=0,0,0,0,0,0,0"},
            {"nadgrids_null", "+nadgrids=@null"},
            {"nadgrids_conus", "+nadgrids=@conus,@alaska"},
            // -- prime meridian and axes ------------------------------------------------------------
            {"pm_paris", "+pm=paris"},
            {"pm_numeric", "+pm=1.5"},
            {"axis_neu", "+axis=neu"},
            {"axis_wsu", "+axis=wsu"},
            // -- units. +vunits is rejected outright today, which kills 158 whole registry defs. ----
            {"units_us_ft", "+units=us-ft"},
            {"units_km", "+units=km"},
            {"vunits_m", "+vunits=m"},
            {"to_meter", "+to_meter=0.3048"},
            {"vto_meter", "+vto_meter=1"},
            // -- projection parameters with known precedence or hard-coding defects -----------------
            {"k", "+k=0.9996"},
            {"k_0", "+k_0=0.9996"},
            {"lat_ts", "+lat_ts=45"},
            {"alpha", "+alpha=-45"},
            {"gamma", "+gamma=10"},
            {"lonc", "+lonc=10"},
            {"no_uoff", "+no_uoff"},
            {"south", "+south"},
            {"zone", "+zone=31"},
            {"h", "+h=35785831"},
            {"x_0_y_0", "+x_0=500000 +y_0=1000000"},
            {"unknown_keyword", "+unknown_keyword=1"},
    };

    /** One transform to evaluate, at one or more probe points. */
    public static final class Case {
        public final String section;
        public final String key;
        /** Diagnostic name of the source CRS. */
        public final String srcName;
        /** Diagnostic name of the target CRS. */
        public final String tgtName;
        /** Parameters of the source CRS, or {@code null} to use {@code createFromName(srcName)}. */
        public final String[] srcParams;
        /** Parameters of the target CRS, or {@code null} to use {@code createFromName(tgtName)}. */
        public final String[] tgtParams;
        /**
         * Pinned input coordinates in source-CRS units, {@code [probe][x, y, z]}, or {@code null} to
         * take the probes from {@code probes.tsv}.
         */
        public final double[][] coords;
        /** {@code +proj=} of the source, for the index file and rule matching. */
        public final String srcProj;
        /** {@code +proj=} of the target. */
        public final String tgtProj;
        /** Sorted union of both sides' parameter key names, for {@code params_present}. */
        public final String paramKeys;
        /** Sorted union of both sides' {@code +datum=} <em>values</em>, for {@code datums}. */
        public final String datums;
        /** True when the input coordinates are lon/lat degrees, so {@code inside} is meaningful. */
        public final boolean geographicInput;

        Case(String section, String key, String srcName, String[] srcParams, String tgtName,
             String[] tgtParams, double[][] coords, String srcProj, String tgtProj,
             String paramKeys, String datums, boolean geographicInput) {
            this.section = section;
            this.key = key;
            this.srcName = srcName;
            this.srcParams = srcParams;
            this.tgtName = tgtName;
            this.tgtParams = tgtParams;
            this.coords = coords;
            this.srcProj = srcProj;
            this.tgtProj = tgtProj;
            this.paramKeys = paramKeys;
            this.datums = datums == null ? "" : datums;
            this.geographicInput = geographicInput;
        }
    }

    private InputSet() {
    }

    // ------------------------------------------------------------------------------ helpers

    public static String[] split(String projString) {
        List<String> out = new ArrayList<String>();
        int start = 0;
        for (int i = 0; i <= projString.length(); i++) {
            if (i == projString.length() || projString.charAt(i) == ' ') {
                if (i > start) out.add(projString.substring(start, i));
                start = i + 1;
            }
        }
        return out.toArray(new String[0]);
    }

    static String keysOf(String[]... groups) {
        TreeSet<String> keys = new TreeSet<String>();
        for (int g = 0; g < groups.length; g++) {
            String[] params = groups[g];
            if (params == null) continue;
            for (int i = 0; i < params.length; i++) {
                String p = params[i];
                if (p.isEmpty()) continue;
                if (p.charAt(0) == '+') p = p.substring(1);
                int eq = p.indexOf('=');
                keys.add(eq < 0 ? p : p.substring(0, eq));
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String k : keys) {
            if (sb.length() > 0) sb.append(',');
            sb.append(k);
        }
        return sb.toString();
    }

    /**
     * The {@code +datum=} <em>values</em> carried by either side, sorted, de-duplicated and
     * comma-joined — the {@code datums} column of {@code golden-index.tsv} and what a
     * {@code datums:} rule predicate matches against.
     *
     * <h2>Why a set and not a scalar</h2>
     *
     * <p>{@code REG} and {@code SYN} cases have a WGS84 source and one interesting target, but a
     * {@code CSV} or {@code PAIR} case has two real CRS and either side can be the one a rule means.
     * A single column holding {@code "NAD27"} for {@code epsg:4267>epsg:26731} and {@code "WGS84"}
     * for the reverse would make the predicate depend on which way the pair happened to be written.
     * The union is direction-independent, and it matches ANY-of, exactly like
     * {@code params_present}.
     *
     * <p>The consequence, stated rather than hidden: {@code datums: [NAD27]} also matches a pair
     * whose <em>source</em> is NAD27. Combine it with {@code src_proj}/{@code tgt_proj} or
     * {@code sections} when that matters — and pin {@code expected_rows}, which is what catches it
     * if it does.
     *
     * <h2>Values are taken verbatim from the definition text</h2>
     *
     * <p>No case folding, no aliasing: {@code +datum=nad83} and {@code +datum=NAD83} are different
     * strings here, because they are different strings in the dictionaries and
     * {@code SYN mod/&#42;/datum_nad83_lower} exists precisely to probe that. A rule that means both
     * must list both.
     */
    static String datumsOf(String[]... groups) {
        TreeSet<String> values = new TreeSet<String>();
        for (int g = 0; g < groups.length; g++) {
            String[] params = groups[g];
            if (params == null) continue;
            for (int i = 0; i < params.length; i++) {
                String p = params[i];
                if (p.isEmpty()) continue;
                if (p.charAt(0) == '+') p = p.substring(1);
                if (p.startsWith("datum=") && p.length() > "datum=".length()) {
                    values.add(p.substring("datum=".length()));
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String v : values) {
            if (sb.length() > 0) sb.append(',');
            sb.append(v);
        }
        return sb.toString();
    }

    /** {@link #datumsOf(String[][])} over a def's parameter list, for the {@code REG} section. */
    static String datumsOf(RegistryDict.Def d) {
        String v = d == null ? null : d.value("datum");
        return v == null ? "" : v;
    }

    static boolean isGeographic(String proj) {
        return "longlat".equals(proj) || "latlong".equals(proj)
                || "latlon".equals(proj) || "lonlat".equals(proj);
    }

    private static final String[] WGS84_PARAMS = split(WGS84);

    // ---------------------------------------------------------------------------- REG section

    /** Every registry def, probed from WGS84 lon/lat. */
    public static List<Case> registry(List<RegistryDict.Def> defs) {
        List<Case> out = new ArrayList<Case>(defs.size());
        for (int i = 0; i < defs.size(); i++) {
            RegistryDict.Def d = defs.get(i);
            out.add(new Case(GoldenFormat.SECTION_REG, d.key(),
                    "wgs84", WGS84_PARAMS,
                    d.crsName(), null,
                    null,
                    "longlat", d.proj(), d.paramKeys(),
                    // The REG source is the WGS84 hub, whose own +datum=WGS84 would appear in every
                    // one of the 9,013 rows and make the column useless. Only the def's datum is
                    // recorded, which is the one a rule about a registry entry means.
                    datumsOf(d), true));
        }
        return out;
    }

    /** The parameter list every REG key's probes are derived from. */
    public static List<Probes.Entry> registryProbes(List<RegistryDict.Def> defs) {
        List<Probes.Entry> out = new ArrayList<Probes.Entry>(defs.size());
        for (int i = 0; i < defs.size(); i++) {
            RegistryDict.Def d = defs.get(i);
            out.add(new Probes.Entry(GoldenFormat.SECTION_REG, d.key(), Probes.derive(d.params)));
        }
        return out;
    }

    // ---------------------------------------------------------------------------- SYN section

    /** {@code (key, proj string)} for every synthetic case, in key order. */
    public static List<String[]> syntheticSpecs() {
        List<String[]> out = new ArrayList<String[]>();
        for (int i = 0; i < ELLIPSOIDS.length; i++) {
            out.add(new String[]{"ellps/" + ELLIPSOIDS[i],
                    "+proj=merc +ellps=" + ELLIPSOIDS[i] + " +lat_0=45 +lon_0=10 +x_0=0 +y_0=0 +units=m"});
        }
        for (int h = 0; h < HOSTS.length; h++) {
            for (int m = 0; m < MODIFIERS.length; m++) {
                out.add(new String[]{"mod/" + HOSTS[h] + "/" + MODIFIERS[m][0],
                        "+proj=" + HOSTS[h] + " " + CANONICAL + " " + MODIFIERS[m][1]});
            }
        }
        for (int i = 0; i < PROJ_NAMES.length; i++) {
            out.add(new String[]{"proj/" + PROJ_NAMES[i], "+proj=" + PROJ_NAMES[i] + " " + CANONICAL});
        }
        Collections.sort(out, new Comparator<String[]>() {
            public int compare(String[] a, String[] b) {
                return a[0].compareTo(b[0]);
            }
        });
        return out;
    }

    public static List<Case> synthetic() {
        List<String[]> specs = syntheticSpecs();
        List<Case> out = new ArrayList<Case>(specs.size());
        for (int i = 0; i < specs.size(); i++) {
            String key = specs.get(i)[0];
            String[] params = split(specs.get(i)[1]);
            String proj = valueOf(params, "proj");
            out.add(new Case(GoldenFormat.SECTION_SYN, key,
                    "wgs84", WGS84_PARAMS,
                    specs.get(i)[1], params,
                    null,
                    "longlat", proj, keysOf(params), datumsOf(params), true));
        }
        return out;
    }

    public static List<Probes.Entry> syntheticProbes() {
        List<String[]> specs = syntheticSpecs();
        List<Probes.Entry> out = new ArrayList<Probes.Entry>(specs.size());
        for (int i = 0; i < specs.size(); i++) {
            List<String> params = java.util.Arrays.asList(split(specs.get(i)[1]));
            out.add(new Probes.Entry(GoldenFormat.SECTION_SYN, specs.get(i)[0], Probes.derive(params)));
        }
        return out;
    }

    static String valueOf(String[] params, String key) {
        String prefix = "+" + key + "=";
        for (int i = 0; i < params.length; i++) {
            if (params[i].startsWith(prefix)) return params[i].substring(prefix.length());
        }
        return "";
    }

    // ---------------------------------------------------------------------------- CSV section

    /** One case per CSV data row, at the row's own pinned coordinate. */
    public static List<Case> csv(List<MetaCrsCsv.Row> rows, Map<String, RegistryDict.Def> byKey) {
        List<Case> out = new ArrayList<Case>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            MetaCrsCsv.Row r = rows.get(i);
            RegistryDict.Def src = byKey.get(r.srcName());
            RegistryDict.Def tgt = byKey.get(r.tgtName());
            String srcProj = src == null ? "" : src.proj();
            String tgtProj = tgt == null ? "" : tgt.proj();
            String keys = InputSet.keysOf(
                    src == null ? null : src.paramArray(),
                    tgt == null ? null : tgt.paramArray());
            out.add(new Case(GoldenFormat.SECTION_CSV, r.key(),
                    r.srcName(), null,
                    r.tgtName(), null,
                    new double[][]{{r.srcX, r.srcY, r.srcZ}},
                    srcProj, tgtProj, keys,
                    datumsOf(src == null ? null : src.paramArray(),
                            tgt == null ? null : tgt.paramArray()),
                    isGeographic(srcProj)));
        }
        return out;
    }

    // --------------------------------------------------------------------------- PAIR section

    public static final String PAIRS_HEADER = "key\tsrc\ttgt\tsrctype\ttgttype";

    /**
     * Reads the curated CRS&rarr;CRS pairs. Probes are lon/lat, so every curated source is a
     * geographic CRS and the input coordinates live in {@code probes.tsv} like every other geographic
     * probe — nothing about the pair needs pinning beyond the two CRS names.
     */
    public static List<Case> pairs(File pairsFile, Map<String, RegistryDict.Def> byKey)
            throws IOException {
        List<Case> out = new ArrayList<Case>();
        if (!pairsFile.isFile()) return out;
        BufferedReader r = GoldenFormat.reader(pairsFile);
        try {
            String line = r.readLine();
            if (line == null || !line.equals(PAIRS_HEADER)) {
                throw new IOException(pairsFile + ": unexpected header: " + line);
            }
            while ((line = r.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                String[] c = GoldenFormat.split(line, 5);
                RegistryDict.Def src = byKey.get(c[1]);
                RegistryDict.Def tgt = byKey.get(c[2]);
                String srcProj = src == null ? "" : src.proj();
                String tgtProj = tgt == null ? "" : tgt.proj();
                out.add(new Case(GoldenFormat.SECTION_PAIR, c[0],
                        c[1], null, c[2], null, null,
                        srcProj, tgtProj,
                        InputSet.keysOf(src == null ? null : src.paramArray(),
                                tgt == null ? null : tgt.paramArray()),
                        datumsOf(src == null ? null : src.paramArray(),
                                tgt == null ? null : tgt.paramArray()),
                        isGeographic(srcProj)));
            }
        } finally {
            r.close();
        }
        return out;
    }

    /** Probes for the PAIR section are derived from the <em>source</em> CRS's definition. */
    public static List<Probes.Entry> pairProbes(List<Case> pairs, Map<String, RegistryDict.Def> byKey) {
        List<Probes.Entry> out = new ArrayList<Probes.Entry>(pairs.size());
        for (int i = 0; i < pairs.size(); i++) {
            Case c = pairs.get(i);
            RegistryDict.Def src = byKey.get(c.srcName);
            List<String> params = src == null
                    ? java.util.Arrays.asList(WGS84_PARAMS) : src.params;
            out.add(new Probes.Entry(GoldenFormat.SECTION_PAIR, c.key, Probes.derive(params)));
        }
        return out;
    }

    /** {@code key -> def} over every dictionary, for the CSV and PAIR sections' index columns. */
    public static Map<String, RegistryDict.Def> index(List<RegistryDict.Def> defs) {
        Map<String, RegistryDict.Def> m = new HashMap<String, RegistryDict.Def>(defs.size() * 2);
        for (int i = 0; i < defs.size(); i++) {
            m.put(defs.get(i).key(), defs.get(i));
        }
        return m;
    }
}
