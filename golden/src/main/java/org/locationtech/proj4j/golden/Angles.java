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

/**
 * A self-contained DMS-capable angle parser, used only to derive probe points from the text of a
 * PROJ.4 definition string.
 *
 * <p>This deliberately does <em>not</em> call into proj4j. Probe derivation reads the definition
 * <em>text</em> — {@code +lat_0}, {@code +lat_1}, {@code +lat_2}, {@code +lat_ts}, {@code +lon_0},
 * {@code +lonc}, {@code +south}, {@code +zone} — rather than the parsed {@code Projection}'s getters,
 * for one reason: probes are checked in, and if they were derived through proj4j's own parser then a
 * change to {@code Proj4Parser}, to a getter, or to the DMS handling would move every probe and make
 * every row of the golden table differ for a reason that has nothing to do with the change under
 * review. Reading the text keeps the input set an input.
 *
 * <p>It has to be DMS-capable because the {@code world}, {@code nad27} and {@code nad83}
 * dictionaries are full of forms like {@code -85d50}, {@code 46d57'8.660"N} and {@code 6d0E}. A
 * plain {@code Double.parseDouble} on those yields NaN, and a NaN latitude candidate treated as
 * "absent" would put every US State Plane zone on a default box.
 *
 * <p>Returns {@link Double#NaN} for anything it cannot parse, and callers must treat NaN as
 * "absent". Note the distinction {@code ProjectionGridRoundTripper.updateLat} fails to make:
 * {@code 0.0} is a perfectly good, explicitly-specified latitude and is <em>not</em> absent.
 */
public final class Angles {

    private Angles() {
    }

    /**
     * Parses a PROJ.4 angle in degrees, minutes and seconds, with an optional trailing cardinal
     * direction and an optional radian suffix.
     *
     * <p>Accepted: {@code 30.5}, {@code -85d50}, {@code 30d30}, {@code 46d57'8.660"N},
     * {@code 7d26'22.500"E}, {@code 6d0E}, {@code 0.5R} (radians), {@code 12&deg;30'}.
     *
     * @return the angle in degrees, or {@link Double#NaN}
     */
    public static double parseDegrees(String raw) {
        if (raw == null) return Double.NaN;
        String s = raw.trim();
        if (s.isEmpty()) return Double.NaN;

        // Trailing cardinal direction sets the sign and is stripped first: "6d0E", "-14d16",
        // "83d10'W". PROJ accepts N/E/S/W in either case.
        double cardinal = 1.0;
        char last = s.charAt(s.length() - 1);
        if (last == 'N' || last == 'n' || last == 'E' || last == 'e') {
            s = s.substring(0, s.length() - 1);
        } else if (last == 'S' || last == 's' || last == 'W' || last == 'w') {
            cardinal = -1.0;
            s = s.substring(0, s.length() - 1);
        }
        if (s.isEmpty()) return Double.NaN;

        // Radian suffix. PROJ's dmstor accepts it; proj4j does not, which is itself a divergence,
        // but here we only need to read what the dictionaries contain.
        boolean radians = false;
        last = s.charAt(s.length() - 1);
        if (last == 'r' || last == 'R') {
            radians = true;
            s = s.substring(0, s.length() - 1);
            if (s.isEmpty()) return Double.NaN;
        }

        double sign = 1.0;
        if (s.charAt(0) == '-') {
            sign = -1.0;
            s = s.substring(1);
        } else if (s.charAt(0) == '+') {
            s = s.substring(1);
        }
        if (s.isEmpty()) return Double.NaN;

        double deg = 0.0;
        double min = 0.0;
        double sec = 0.0;
        boolean sawAny = false;

        int i = 0;
        StringBuilder tok = new StringBuilder();
        int stage = 0; // 0 = degrees, 1 = minutes, 2 = seconds
        for (; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == 'd' || ch == 'D' || ch == '°') {
                double v = num(tok);
                if (Double.isNaN(v)) return Double.NaN;
                deg = v;
                sawAny = true;
                tok.setLength(0);
                stage = 1;
            } else if (ch == '\'') {
                double v = num(tok);
                if (Double.isNaN(v)) return Double.NaN;
                min = v;
                sawAny = true;
                tok.setLength(0);
                stage = 2;
            } else if (ch == '"') {
                double v = num(tok);
                if (Double.isNaN(v)) return Double.NaN;
                sec = v;
                sawAny = true;
                tok.setLength(0);
                stage = 3;
            } else {
                tok.append(ch);
            }
        }
        if (tok.length() > 0) {
            double v = num(tok);
            if (Double.isNaN(v)) return Double.NaN;
            switch (stage) {
                case 0: deg = v; break;
                case 1: min = v; break;
                case 2: sec = v; break;
                default: return Double.NaN; // trailing junk after the seconds mark
            }
            sawAny = true;
        }
        if (!sawAny) return Double.NaN;

        double v = deg + min / 60.0 + sec / 3600.0;
        if (radians) v = Math.toDegrees(v);
        return sign * cardinal * v;
    }

    private static double num(CharSequence cs) {
        if (cs.length() == 0) return 0.0;
        String s = cs.toString();
        // Double.parseDouble is far too permissive for this: it accepts "d", "0x1p3", "NaN",
        // "Infinity", a leading "+", and surrounding whitespace, all of which would silently
        // succeed here and yield a nonsense probe.
        boolean dot = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '.') {
                if (dot) return Double.NaN;
                dot = true;
            } else if (ch < '0' || ch > '9') {
                return Double.NaN;
            }
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }
}
