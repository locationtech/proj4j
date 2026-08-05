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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/**
 * Reads the five PROJ.4 init dictionaries that {@code proj4j-epsg} ships, straight off the
 * classpath, and hands back an ordered list of {@code (code, definition text)}.
 *
 * <p>Measured at the pinned 1.4.3 data (byte-identical to the working tree's as of this commit):
 * {@code epsg} 5,755 &middot; {@code esri} 2,954 &middot; {@code world} 47 &middot; {@code nad83} 123
 * &middot; {@code nad27} 134 = <b>9,013 CRS</b>. Those five numbers are asserted by the self-tests,
 * so a dictionary edit is visible immediately rather than as an unexplained mass of ADDED/REMOVED
 * rows two hours later.
 *
 * <p>This is a second, independent parser for a format proj4j already parses
 * ({@code io/Proj4FileReader} uses a {@code StreamTokenizer}). That duplication is intentional: the
 * golden suite must be able to enumerate the input set without depending on the code under test to
 * agree about what the input set <em>is</em>. If proj4j's reader starts skipping a def, this reader
 * still emits its key and the row becomes a visible {@code EXC:} / status transition rather than
 * silently vanishing.
 *
 * <h2>The format, as it actually appears in the files</h2>
 * <pre>
 * # a comment, running to end of line, and legal anywhere including after &lt;code&gt;
 * &lt;3819&gt; +proj=longlat +ellps=bessel +no_defs  &lt;&gt;
 *
 * &lt;101&gt; proj=tmerc  datum=NAD27          # leading '+' is optional
 * lon_0=-85d50 lat_0=30d30 k=.99996      # and definitions span lines
 * no_defs &lt;&gt;
 *
 * &lt;gk2-d&gt; # Gauss Krueger Grid for Germany
 *         proj=tmerc ellps=bessel lon_0=6d0E lat_0=0
 *         no_defs&lt;&gt;                      # and '&lt;&gt;' need not be whitespace-separated
 * </pre>
 * A def starts at {@code <name>} and ends at the empty element {@code <>}.
 */
public final class RegistryDict {

    /** The five dictionaries, in the order the golden table's REG section walks them. */
    public static final String[] AUTHORITIES = {"epsg", "esri", "nad27", "nad83", "world"};

    /** Expected def counts, asserted by the self-tests. */
    public static final int[] EXPECTED_COUNTS = {5755, 2954, 134, 123, 47};

    public static final int EXPECTED_TOTAL = 9013;

    /** One definition: its code within its authority, and its parameter tokens. */
    public static final class Def {
        public final String authority;
        public final String code;
        /** Parameter tokens, each normalised to start with {@code '+'}, in file order. */
        public final List<String> params;

        Def(String authority, String code, List<String> params) {
            this.authority = authority;
            this.code = code;
            this.params = Collections.unmodifiableList(params);
        }

        /** The golden-table key: {@code "epsg:4326"}. Lower-case authority, verbatim code. */
        public String key() {
            return authority + ":" + code;
        }

        /** The name to hand {@code CRSFactory.createFromName}. Identical to {@link #key()}. */
        public String crsName() {
            return key();
        }

        /** The value of {@code +proj=}, or {@code ""} if the def does not carry one. */
        public String proj() {
            String v = value("proj");
            return v == null ? "" : v;
        }

        /** The value of parameter {@code key} (without the {@code '+'}), or null. */
        public String value(String key) {
            String prefix = "+" + key + "=";
            for (int i = 0; i < params.size(); i++) {
                String p = params.get(i);
                if (p.startsWith(prefix)) return p.substring(prefix.length());
            }
            return null;
        }

        public boolean has(String key) {
            String bare = "+" + key;
            String prefix = bare + "=";
            for (int i = 0; i < params.size(); i++) {
                String p = params.get(i);
                if (p.equals(bare) || p.startsWith(prefix)) return true;
            }
            return false;
        }

        /** Sorted, de-duplicated parameter key names, for the index file's {@code params} column. */
        public String paramKeys() {
            Set<String> keys = new java.util.TreeSet<String>();
            for (int i = 0; i < params.size(); i++) {
                String p = params.get(i);
                int eq = p.indexOf('=');
                keys.add(eq < 0 ? p.substring(1) : p.substring(1, eq));
            }
            StringBuilder sb = new StringBuilder();
            for (String k : keys) {
                if (sb.length() > 0) sb.append(',');
                sb.append(k);
            }
            return sb.toString();
        }

        public String[] paramArray() {
            return params.toArray(new String[0]);
        }
    }

    private RegistryDict() {
    }

    /**
     * Reads every def in every dictionary, in a fixed total order: authority in
     * {@link #AUTHORITIES} order, then code in US-ASCII order.
     */
    public static List<Def> readAll() throws IOException {
        List<Def> all = new ArrayList<Def>(EXPECTED_TOTAL);
        for (int i = 0; i < AUTHORITIES.length; i++) {
            all.addAll(read(AUTHORITIES[i]));
        }
        return all;
    }

    /** Reads one dictionary, sorted by code in US-ASCII order. */
    public static List<Def> read(String authority) throws IOException {
        String resource = "proj4/nad/" + authority;
        InputStream in = RegistryDict.class.getClassLoader().getResourceAsStream(resource);
        if (in == null) {
            throw new IOException("cannot find " + resource + " on the classpath; is proj4j-epsg a dependency?");
        }
        String text;
        try {
            text = slurpLatin1(in);
        } finally {
            in.close();
        }

        // A TreeMap gives the US-ASCII sort and, incidentally, tells us about duplicate codes:
        // Proj4FileReader returns the FIRST match, so we keep the first too.
        TreeMap<String, Def> byCode = new TreeMap<String, Def>();
        parse(authority, text, byCode);
        return new ArrayList<Def>(byCode.values());
    }

    /**
     * Reads bytes as Latin-1 rather than UTF-8. A few {@code world} entries carry high bytes in
     * their comments ("Gauss Krueger" attributions with German names). Latin-1 is a total function
     * from bytes to chars, so this cannot throw or insert U+FFFD; the comments are stripped anyway,
     * and any high byte that survives into a key is replaced by {@link GoldenFormat#sanitise}.
     */
    private static String slurpLatin1(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(1 << 20);
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return new String(bos.toByteArray(), "ISO-8859-1");
    }

    private static void parse(String authority, String text, TreeMap<String, Def> out) {
        String current = null;
        List<String> params = new ArrayList<String>();
        StringBuilder tok = new StringBuilder();

        int i = 0;
        int n = text.length();
        while (i < n) {
            char ch = text.charAt(i);

            if (ch == '#') {
                // Comment to end of line. Terminates the token in progress.
                flush(tok, params);
                while (i < n && text.charAt(i) != '\n') i++;
                continue;
            }

            if (ch == '<') {
                flush(tok, params);
                int close = text.indexOf('>', i + 1);
                if (close < 0) break; // truncated file; nothing sane to do
                String name = text.substring(i + 1, close).trim();
                i = close + 1;
                if (name.isEmpty()) {
                    // "<>" closes the current def.
                    if (current != null && !out.containsKey(current)) {
                        out.put(current, new Def(authority, current, new ArrayList<String>(params)));
                    }
                    current = null;
                    params = new ArrayList<String>();
                } else {
                    // A new "<name>" without an intervening "<>" would be malformed; treat it as
                    // closing the previous def so one bad entry cannot swallow the rest of the file.
                    if (current != null && !out.containsKey(current)) {
                        out.put(current, new Def(authority, current, new ArrayList<String>(params)));
                    }
                    current = GoldenFormat.sanitise(name);
                    params = new ArrayList<String>();
                }
                continue;
            }

            if (ch == ' ' || ch == '\t' || ch == '\r' || ch == '\n') {
                flush(tok, params);
                i++;
                continue;
            }

            tok.append(ch);
            i++;
        }
        flush(tok, params);
        if (current != null && !out.containsKey(current)) {
            out.put(current, new Def(authority, current, new ArrayList<String>(params)));
        }
    }

    private static void flush(StringBuilder tok, List<String> params) {
        if (tok.length() == 0) return;
        String t = tok.toString();
        tok.setLength(0);
        // Leading '+' is optional in the nad27/nad83/world dictionaries; normalise so downstream
        // code has exactly one shape to handle.
        params.add(t.charAt(0) == '+' ? t : "+" + t);
    }

    /** Every distinct {@code +proj=} value present across all five dictionaries, sorted. */
    public static Set<String> projNames(List<Def> defs) {
        Set<String> s = new java.util.TreeSet<String>();
        for (int i = 0; i < defs.size(); i++) {
            String p = defs.get(i).proj();
            if (!p.isEmpty()) s.add(p);
        }
        return new LinkedHashSet<String>(s);
    }
}
