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
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;

/**
 * The on-disk format of a golden table, and the total order every file in it is sorted by.
 *
 * <h2>Why {@code Double.toHexString} and not {@code %.17g}</h2>
 *
 * Every float in every golden file is written with {@link Double#toHexString(double)}. This is not a
 * stylistic preference; four separate properties are load-bearing, and decimal formatting fails all
 * four:
 *
 * <ol>
 * <li><b>It is bijective on bit patterns.</b> Each of the 2^64 double bit patterns has exactly one
 *     hex-significand rendering, and {@link Double#parseDouble} inverts it exactly. So a diff of two
 *     golden files is a diff of raw bits, and — crucially — <em>a change to the formatting code
 *     cannot make every row differ</em>. There is no rounding decision to change. With {@code %.17g}
 *     a future tweak from 17 to 16 significant digits rewrites all ~53,000 rows and buries whatever
 *     real change was in there.</li>
 * <li><b>It distinguishes {@code +0.0} from {@code -0.0}.</b> {@code %.17g} of {@code -0.0} is
 *     {@code "-0.00000000000000000"} in some locales and {@code "0.00000000000000000"} after a
 *     {@code Math.abs} slips in; more importantly {@code Double.compare} and {@code ==} disagree
 *     about them. The distinction is real proj4j behaviour, not noise: it is the difference between
 *     approaching the equator from the north and from the south, and between the two sides of the
 *     antimeridian, and it flips on sign-handling changes in exactly the code this project is
 *     rewriting. {@code toHexString(-0.0)} is {@code "-0x0.0p0"}.</li>
 * <li><b>It is locale-immune.</b> proj4j itself has live locale-dependent formatting defects —
 *     {@code ProjCoordinate.DECIMAL_FORMAT} is a public mutable static {@code DecimalFormat} with no
 *     {@code Locale} (so {@code 1,5} under {@code de_DE}), and {@code units/Unit} and
 *     {@code units/AngleFormat} use {@code NumberFormat.getNumberInstance()}. A golden suite that
 *     built its own formatting on {@code String.format} would inherit the same class of bug and its
 *     baseline would be unreproducible on a colleague's machine. {@code toHexString} consults no
 *     {@code Locale}.</li>
 * <li><b>It makes a 1-ULP difference exact by construction.</b> The whole point of the suite is to
 *     see small movements. Two doubles one ULP apart differ in the last hex digit and nowhere else,
 *     so the diff is trivially readable; at 17 significant decimal digits they differ in a way that
 *     requires arithmetic to interpret.</li>
 * </ol>
 *
 * {@code NaN}, {@code Infinity} and {@code -Infinity} render as those three literals, which are
 * US-ASCII, canonical, and parse back. NaN payloads are <em>not</em> preserved — {@code toHexString}
 * collapses every NaN to {@code "NaN"} — which is deliberate: proj4j mints NaNs from many places and
 * the payload is a JVM implementation detail, not behaviour.
 *
 * <h2>The file format</h2>
 *
 * TSV, LF line endings, US-ASCII, no quoting and no escaping of any kind. Tab and newline cannot
 * occur in any field: keys are drawn from registry codes and proj-string tokens, statuses are class
 * names, and floats are hex. {@link #assertClean} enforces that rather than trusting it.
 *
 * Three files per table:
 * <ul>
 * <li>{@code golden.tsv} — the numbers. Columns: {@code section, key, probe, status, fx, fy, fz, ix,
 *     iy, iz, inside}. {@code f*} is the forward transform's output; {@code i*} is the inverse
 *     applied to that forward output; {@code inside} is the advisory
 *     {@code Projection.inside(lon,lat)} boolean ({@code T}/{@code F}, or {@code E} when it throws —
 *     it can, because it routes through {@code normalizeLongitude}).</li>
 * <li>{@code golden-index.tsv} — one row per {@code (section, key)} carrying the metadata the rules
 *     engine matches on: source and target {@code +proj=} names, the sorted parameter key set, and
 *     the sorted set of {@code +datum=} <em>values</em>.
 *     Kept out of {@code golden.tsv} so that a def-text edit does not rewrite numeric rows.
 *     <p>The {@code datums} column exists because {@code params_present} matches parameter
 *     <em>keys</em> only, and the single largest honest {@code UNEXPLAINED} cluster this suite has
 *     had — 907 {@code REG} rows on {@code datum=NAD27} — could not be scoped without it. The
 *     alternative was enumerating ~205 keys, which would also have swept in ~1,066 rows belonging
 *     to a live stream. It is a set rather than a scalar because {@code CSV} and {@code PAIR} cases
 *     have two sides; see {@code InputSet.datumsOf}.</li>
 * <li>{@code golden-messages.tsv} — exception messages, keyed by {@code (section, key, probe)}.
 *     Separate file so message text (which is churn-prone, long, and frequently reworded for reasons
 *     that are not behavioural) never bloats or perturbs the numeric table.</li>
 * </ul>
 *
 * <h2>The total order</h2>
 *
 * {@code (section, key, probe)}, each compared as raw US-ASCII bytes via {@link String#compareTo}.
 * Since every field is ASCII, that is byte order, which is the order {@code sort} and {@code git
 * diff} use, and it is the order the streaming merge join in {@code GoldenDiff} depends on.
 * {@code probe} is a single digit 0-4 so lexicographic and numeric order coincide. The order is not
 * numeric on the code ({@code epsg:10000} sorts before {@code epsg:2000}); that is fine — a total
 * order only has to be total and stable, and rules that need numeric code ranges parse the code.
 */
public final class GoldenFormat {

    private GoldenFormat() {
    }

    /** US-ASCII, not UTF-8: a non-ASCII byte in a golden file is a bug, and this makes it loud. */
    public static final Charset ASCII = Charset.forName("US-ASCII");

    public static final String GOLDEN_FILE = "golden.tsv";
    public static final String INDEX_FILE = "golden-index.tsv";
    public static final String MESSAGES_FILE = "golden-messages.tsv";

    public static final String HEADER_GOLDEN =
            "section\tkey\tprobe\tstatus\tfx\tfy\tfz\tix\tiy\tiz\tinside";
    public static final String HEADER_INDEX =
            "section\tkey\tsrcproj\ttgtproj\tparams\tdatums";

    /**
     * The header this file wrote before the {@code datums} column existed.
     *
     * <p>{@code baseline/1.4.3/golden-index.tsv} is a <b>committed baseline artefact</b> and is not
     * rewritten by a normal run, so the reader has to accept it. It is read only as a fallback for
     * a {@code (section, key)} the current run did not produce — i.e. for {@code REMOVED} rows —
     * and a legacy row supplies an empty {@code datums} set, which no {@code datums:} predicate can
     * match. That is the safe direction: a rule under-claims rather than over-claims, and
     * {@code expected_rows} makes under-claiming loud.
     */
    public static final String HEADER_INDEX_V1 =
            "section\tkey\tsrcproj\ttgtproj\tparams";
    public static final String HEADER_MESSAGES =
            "section\tkey\tprobe\tmessage";

    /** Status of a row whose forward transform completed. */
    public static final String OK = "OK";
    /** Status prefix for a row that threw; the remainder is the fully-qualified class name. */
    public static final String EXC = "EXC:";
    /**
     * Status of a row whose probe is absent from {@code probes.tsv}. Not an error and not a
     * transform outcome: it is how a CRS added to the registry dictionaries after the last probe
     * regeneration announces itself, instead of silently acquiring a made-up probe.
     */
    public static final String NO_PROBE = "NO_PROBE";

    public static final int COL_SECTION = 0;
    public static final int COL_KEY = 1;
    public static final int COL_PROBE = 2;
    public static final int COL_STATUS = 3;
    public static final int COL_FX = 4;
    public static final int COL_FY = 5;
    public static final int COL_FZ = 6;
    public static final int COL_IX = 7;
    public static final int COL_IY = 8;
    public static final int COL_IZ = 9;
    public static final int COL_INSIDE = 10;
    public static final int GOLDEN_COLUMNS = 11;

    /** The six numeric dimensions, in column order. Rule {@code expect.dimensions} names these. */
    public static final String[] DIMENSIONS = {"fx", "fy", "fz", "ix", "iy", "iz"};

    public static final String SECTION_CSV = "CSV";
    public static final String SECTION_PAIR = "PAIR";
    public static final String SECTION_REG = "REG";
    public static final String SECTION_SYN = "SYN";

    // ---------------------------------------------------------------- floats

    /** See the class javadoc for why this is {@code toHexString} and not a decimal format. */
    public static String hex(double d) {
        return Double.toHexString(d);
    }

    /** Exact inverse of {@link #hex} for every finite double, plus the three non-finite literals. */
    public static double unhex(String s) {
        return Double.parseDouble(s);
    }

    // ----------------------------------------------------------------- order

    /**
     * The total order. Operates on already-split rows so the merge join never re-splits.
     *
     * @param a a row with at least {@link #COL_PROBE}+1 fields
     * @param b likewise
     */
    public static int compareRows(String[] a, String[] b) {
        int c = a[COL_SECTION].compareTo(b[COL_SECTION]);
        if (c != 0) return c;
        c = a[COL_KEY].compareTo(b[COL_KEY]);
        if (c != 0) return c;
        return a[COL_PROBE].compareTo(b[COL_PROBE]);
    }

    /** The same order restricted to {@code (section, key)}, for the index files. */
    public static int compareKeys(String[] a, String[] b) {
        int c = a[COL_SECTION].compareTo(b[COL_SECTION]);
        if (c != 0) return c;
        return a[COL_KEY].compareTo(b[COL_KEY]);
    }

    // ------------------------------------------------------------------- I/O

    /**
     * Rejects anything that would need quoting, an escape, or a charset decision. Called on every
     * field of every row on the way out. The suite's contract is "no quoting"; the only honest way to
     * offer that is to fail rather than emit a field that violates it.
     */
    public static String assertClean(String field) {
        if (field == null) throw new IllegalArgumentException("null field");
        for (int i = 0; i < field.length(); i++) {
            char ch = field.charAt(i);
            if (ch < 0x20 || ch > 0x7e) {
                throw new IllegalArgumentException(
                        "field is not printable US-ASCII at index " + i + " (0x"
                                + Integer.toHexString(ch) + "): " + describe(field));
            }
        }
        return field;
    }

    private static String describe(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch >= 0x20 && ch <= 0x7e) sb.append(ch);
            else sb.append("\\u").append(String.format("%04x", (int) ch));
        }
        return sb.toString();
    }

    /**
     * Sanitises a field for use in a key or an index column: replaces any character that cannot
     * appear in the format with {@code '_'}. Used only on text originating in the registry
     * dictionaries (a handful of {@code world} entries carry Latin-1 bytes) and on exception
     * messages.
     */
    public static String sanitise(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch >= 0x20 && ch <= 0x7e) sb.append(ch);
            else sb.append('_');
        }
        return sb.toString();
    }

    /** LF only, US-ASCII, no BOM. */
    public static Writer writer(File f) throws IOException {
        File parent = f.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("cannot create " + parent);
        }
        final OutputStream out = new java.io.FileOutputStream(f);
        return new BufferedWriter(new OutputStreamWriter(out, ASCII), 1 << 16);
    }

    public static void writeRow(Writer w, String... fields) throws IOException {
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) w.write('\t');
            w.write(assertClean(fields[i]));
        }
        // Explicit '\n', never println() and never System.lineSeparator(): a CRLF golden file
        // generated on Windows would diff against every single row of an LF baseline.
        w.write('\n');
    }

    public static BufferedReader reader(File f) throws IOException {
        return new BufferedReader(new InputStreamReader(new java.io.FileInputStream(f), ASCII), 1 << 16);
    }

    public static BufferedReader reader(InputStream in) {
        return new BufferedReader(new InputStreamReader(in, ASCII), 1 << 16);
    }

    /**
     * Splits on tab without regex and without dropping trailing empty fields, which
     * {@code String.split("\t")} does.
     */
    public static String[] split(String line, int expected) {
        String[] out = new String[expected];
        int field = 0;
        int start = 0;
        for (int i = 0; i <= line.length(); i++) {
            if (i == line.length() || line.charAt(i) == '\t') {
                if (field >= expected) {
                    throw new IllegalArgumentException(
                            "expected " + expected + " fields, found more: " + line);
                }
                out[field++] = line.substring(start, i);
                start = i + 1;
            }
        }
        if (field != expected) {
            throw new IllegalArgumentException(
                    "expected " + expected + " fields, found " + field + ": " + line);
        }
        return out;
    }
}
