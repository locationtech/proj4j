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
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the MetaCRS-format CSV test files that live in {@code core/src/test/resources}.
 *
 * <p>Five files, 4,996 data rows in total as of this commit:
 * <table>
 * <tr><td>{@code proj4-epsg.csv}</td><td>4,280</td>
 *     <td>every row probes the single point (1.0, -1.0)</td></tr>
 * <tr><td>{@code PROJ4_SPCS_EPSG_nad83.csv}</td><td>225</td>
 *     <td>220 live plus 5 commented-out {@code ESRI:102631} {@code omerc} rows</td></tr>
 * <tr><td>{@code PROJ4_SPCS_nad27.csv}</td><td>265</td>
 *     <td>orphaned; NAD27 SPCS coverage that exists nowhere else</td></tr>
 * <tr><td>{@code PROJ4_SPCS_ESRI_nad83.csv}</td><td>225</td>
 *     <td>orphaned; byte-identical to the EPSG file bar retargeting</td></tr>
 * <tr><td>{@code TestData.csv}</td><td>1</td><td></td></tr>
 * </table>
 *
 * <p><b>Commented rows are included.</b> A leading {@code '#'} is stripped and the row is read
 * normally. Five rows in {@code PROJ4_SPCS_EPSG_nad83.csv} are commented out, four of them tagged
 * <i>"Bug in Proj4J Obl Merc"</i>, and they are among the few in-repo witnesses for the {@code omerc}
 * defect. Hiding a known-bad case behind {@code '#'} is how it stops being tracked; the golden suite
 * takes no view on whether a row is <em>right</em>, only on whether it <em>moved</em>, so a
 * known-broken row is a perfectly good baseline row.
 *
 * <p><b>These files are read from the working tree, not from the classpath.</b> They are
 * {@code core}'s test resources and {@code core} publishes no test jar. That means the input set
 * floats with the tree while the <em>code</em> under baseline is pinned at 1.4.3 — a deliberate
 * choice, documented in README.md: a change to the input set shows up as ADDED/REMOVED rows, which
 * the rules file can declare, rather than being invisible.
 */
public final class MetaCrsCsv {

    /** The files read, in the order the CSV section walks them. Keys sort within a file by line. */
    public static final String[] FILES = {
            "PROJ4_SPCS_EPSG_nad83.csv",
            "PROJ4_SPCS_ESRI_nad83.csv",
            "PROJ4_SPCS_nad27.csv",
            "TestData.csv",
            "proj4-epsg.csv",
    };

    /** One data row. Only the columns the golden suite uses are retained. */
    public static final class Row {
        public final String file;
        public final int line;
        public final boolean commented;
        public final String srcAuth;
        public final String srcCode;
        public final String tgtAuth;
        public final String tgtCode;
        public final double srcX;
        public final double srcY;
        public final double srcZ;

        Row(String file, int line, boolean commented, String srcAuth, String srcCode,
            String tgtAuth, String tgtCode, double srcX, double srcY, double srcZ) {
            this.file = file;
            this.line = line;
            this.commented = commented;
            this.srcAuth = srcAuth;
            this.srcCode = srcCode;
            this.tgtAuth = tgtAuth;
            this.tgtCode = tgtCode;
            this.srcX = srcX;
            this.srcY = srcY;
            this.srcZ = srcZ;
        }

        /**
         * The golden key: {@code "proj4-epsg.csv:00042"}. The line number is zero-padded to five
         * digits so that the US-ASCII total order coincides with line order — without the padding
         * line 100 would sort before line 20 and a reviewer reading a diff would have to reorder it
         * mentally.
         */
        public String key() {
            return file + ":" + pad(line);
        }

        public String srcName() {
            return srcAuth.toLowerCase(java.util.Locale.ROOT) + ":" + srcCode;
        }

        public String tgtName() {
            return tgtAuth.toLowerCase(java.util.Locale.ROOT) + ":" + tgtCode;
        }
    }

    static String pad(int n) {
        String s = Integer.toString(n);
        StringBuilder sb = new StringBuilder(5);
        for (int i = s.length(); i < 5; i++) sb.append('0');
        return sb.append(s).toString();
    }

    private MetaCrsCsv() {
    }

    /** Reads all five files from {@code dir}. A missing file is skipped, not an error. */
    public static List<Row> readAll(File dir) throws IOException {
        List<Row> out = new ArrayList<Row>(5000);
        for (int i = 0; i < FILES.length; i++) {
            File f = new File(dir, FILES[i]);
            if (!f.isFile()) continue;
            read(f, FILES[i], out);
        }
        return out;
    }

    private static void read(File f, String name, List<Row> out) throws IOException {
        BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), "ISO-8859-1"), 1 << 16);
        try {
            String line;
            int lineNo = 0;
            while ((line = r.readLine()) != null) {
                lineNo++;
                String s = line;
                boolean commented = false;
                // A leading '#' is either a file-level comment (the MetaCRS licence banner in
                // TestData.csv) or a disabled data row. Distinguish by whether what follows parses.
                if (s.startsWith("#")) {
                    commented = true;
                    s = s.substring(1);
                }
                if (s.trim().isEmpty()) continue;
                String[] c = splitCsv(s);
                if (c.length < 12) continue;
                if ("testName".equals(c[0])) continue;          // header
                double x = parse(c[6]);
                double y = parse(c[7]);
                if (Double.isNaN(x) || Double.isNaN(y)) continue;   // banner / prose line
                if (c[2].isEmpty() || c[3].isEmpty() || c[4].isEmpty() || c[5].isEmpty()) continue;
                double z = parse(c[8]);
                if (Double.isNaN(z)) z = 0.0;
                out.add(new Row(name, lineNo, commented, c[2], c[3], c[4], c[5], x, y, z));
            }
        } finally {
            r.close();
        }
    }

    private static double parse(String s) {
        String t = s.trim();
        if (t.isEmpty()) return Double.NaN;
        // Deliberately strict: no hex, no "NaN", no "Infinity", no leading '+'. Double.parseDouble
        // accepts all of those and a prose column that happened to start with a digit would sail
        // through.
        int i = 0;
        if (t.charAt(0) == '-') i = 1;
        boolean digit = false;
        boolean dot = false;
        for (; i < t.length(); i++) {
            char ch = t.charAt(i);
            if (ch >= '0' && ch <= '9') digit = true;
            else if (ch == '.' && !dot) dot = true;
            else return Double.NaN;
        }
        if (!digit) return Double.NaN;
        try {
            return Double.parseDouble(t);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /**
     * Minimal RFC-4180 field splitter. Necessary rather than {@code split(",")} because the
     * {@code dataCmnts} and {@code maintenanceCmnts} columns hold free prose containing commas, and
     * because most rows quote every field while {@code PROJ4_SPCS_nad27.csv} leaves the empty
     * {@code srcOrd3} unquoted.
     */
    static String[] splitCsv(String line) {
        List<String> out = new ArrayList<String>(20);
        StringBuilder cur = new StringBuilder();
        boolean inQuote = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (inQuote) {
                if (ch == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        inQuote = false;
                    }
                } else {
                    cur.append(ch);
                }
            } else if (ch == '"') {
                inQuote = true;
            } else if (ch == ',') {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        out.add(cur.toString());
        return out.toArray(new String[0]);
    }
}
