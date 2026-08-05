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

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Self-tests for the format's four load-bearing properties. Each of these would have caught a real
 * class of golden-suite failure — a whole-table false diff, a lost sign, an unreproducible baseline.
 */
public class GoldenFormatTest {

    /** Property 1: bijective on bit patterns, over a large random sample plus every special case. */
    @Test
    public void hexRoundTripsEveryBitPattern() {
        Random r = new Random(20260731L);
        for (int i = 0; i < 200000; i++) {
            double d = Double.longBitsToDouble(r.nextLong());
            String s = GoldenFormat.hex(d);
            double back = GoldenFormat.unhex(s);
            if (Double.isNaN(d)) {
                assertTrue("NaN must render as NaN, got " + s, Double.isNaN(back));
            } else {
                assertEquals("round trip failed for " + s,
                        Double.doubleToRawLongBits(d), Double.doubleToRawLongBits(back));
            }
        }
        double[] special = {
                0.0, -0.0, 1.0, -1.0, Double.MIN_VALUE, -Double.MIN_VALUE,
                Double.MIN_NORMAL, Double.MAX_VALUE, -Double.MAX_VALUE,
                Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                Math.PI, 500000.0, 6378137.0, 1e-300, 1e300,
        };
        for (int i = 0; i < special.length; i++) {
            assertEquals(Double.doubleToRawLongBits(special[i]),
                    Double.doubleToRawLongBits(GoldenFormat.unhex(GoldenFormat.hex(special[i]))));
        }
    }

    /**
     * Property 2: {@code +0.0} and {@code -0.0} are distinguishable. This is not pedantry — it is the
     * difference between the two sides of the equator and of the antimeridian, and sign handling in
     * the inverse projections is under active change.
     */
    @Test
    public void signedZeroIsPreserved() {
        assertEquals("0x0.0p0", GoldenFormat.hex(0.0));
        assertEquals("-0x0.0p0", GoldenFormat.hex(-0.0));
        assertFalse("+0.0 and -0.0 must not render identically",
                GoldenFormat.hex(0.0).equals(GoldenFormat.hex(-0.0)));
        assertEquals(Double.doubleToRawLongBits(-0.0),
                Double.doubleToRawLongBits(GoldenFormat.unhex("-0x0.0p0")));
        // And for contrast, what a decimal format would have done to it:
        assertEquals("0.00000000000000000", String.format(java.util.Locale.ROOT, "%.17f", 0.0));
    }

    /** Property 4: one ULP differs in one character, so the diff is readable without arithmetic. */
    @Test
    public void oneUlpIsOneCharacter() {
        // A value with a full 13-digit hex significand, so toHexString does not trim trailing
        // zeros and change the string length as well.
        double a = Math.PI;
        double b = Math.nextUp(a);
        String sa = GoldenFormat.hex(a);
        String sb = GoldenFormat.hex(b);
        assertEquals("same length", sa.length(), sb.length());
        int diffs = 0;
        for (int i = 0; i < sa.length(); i++) {
            if (sa.charAt(i) != sb.charAt(i)) diffs++;
        }
        assertEquals("1 ULP should differ in exactly one hex digit: " + sa + " vs " + sb, 1, diffs);
    }

    /** Property 3: locale-immune. A German locale must not put a comma in a golden file. */
    @Test
    public void hexIsLocaleImmune() {
        java.util.Locale saved = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY);
            assertEquals("0x1.91eb851eb851fp1", GoldenFormat.hex(3.14));
            assertEquals(3.14, GoldenFormat.unhex("0x1.91eb851eb851fp1"), 0.0);
        } finally {
            java.util.Locale.setDefault(saved);
        }
    }

    @Test
    public void writeRowEmitsLfAndNothingElse() throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        Writer w = new OutputStreamWriter(bos, GoldenFormat.ASCII);
        GoldenFormat.writeRow(w, "REG", "epsg:4326", "0");
        w.flush();
        assertArrayEquals(new byte[]{'R', 'E', 'G', '\t', 'e', 'p', 's', 'g', ':', '4', '3', '2', '6',
                '\t', '0', '\n'}, bos.toByteArray());
    }

    @Test
    public void assertCleanRejectsAnythingNeedingQuoting() {
        // A space is legal (0x20 is printable ASCII); tab, the line terminators, NUL and any byte
        // outside 0x20-0x7e are not.
        String[] bad = {"a\tb", "a\nb", "a\rb", "caf\u00e9", "\u0000"};
        for (int i = 0; i < bad.length; i++) {
            try {
                GoldenFormat.assertClean(bad[i]);
                fail("should have rejected: " + bad[i]);
            } catch (IllegalArgumentException expected) {
                // the format promises no quoting; the only honest way to promise that is to refuse
            }
        }
        assertEquals("ok", GoldenFormat.assertClean("ok"));
    }

    /** {@code String.split} drops trailing empty fields; a golden row's last column may be empty. */
    @Test
    public void splitKeepsTrailingEmptyFields() {
        assertArrayEquals(new String[]{"a", "b", ""}, GoldenFormat.split("a\tb\t", 3));
        assertArrayEquals(new String[]{"", "", ""}, GoldenFormat.split("\t\t", 3));
        // Documents the trap this method exists to avoid: split() reports 2 fields, not 3.
        assertEquals(2, "a\tb\t".split("\t").length);
    }

    @Test
    public void totalOrderIsSectionThenKeyThenProbe() {
        assertTrue(cmp("CSV", "z", "9", "PAIR", "a", "0") < 0);
        assertTrue(cmp("REG", "epsg:4326", "0", "REG", "epsg:4326", "1") < 0);
        assertTrue(cmp("REG", "epsg:10000", "0", "REG", "epsg:2000", "0") < 0); // ASCII, not numeric
        assertEquals(0, cmp("SYN", "proj/merc", "3", "SYN", "proj/merc", "3"));
    }

    private static int cmp(String s1, String k1, String p1, String s2, String k2, String p2) {
        return GoldenFormat.compareRows(new String[]{s1, k1, p1}, new String[]{s2, k2, p2});
    }
}
