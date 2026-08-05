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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.locationtech.proj4j.conformance.bridge.GieFailure;
import org.locationtech.proj4j.conformance.bridge.GieFailureKind;
import org.locationtech.proj4j.conformance.bridge.GieOperation;
import org.locationtech.proj4j.conformance.bridge.GieOperationFactory;
import org.locationtech.proj4j.gie.GieDirection;
import org.locationtech.proj4j.gie.GieIoUnits;

/**
 * A test double for {@link GieOperationFactory} with arithmetic simple enough to compute the expected
 * outcome of a fixture by hand.
 *
 * <h2>The definition language</h2>
 *
 * <p>An argument string is usable iff its first token is {@code fake}. Anything else fails to be
 * created, which is how a fixture exercises {@code expect failure} against a broken definition.
 * Recognised parameters:
 *
 * <table border="1">
 * <caption>Fake operation parameters</caption>
 * <tr><th>parameter</th><th>effect</th></tr>
 * <tr><td>{@code shift=<d>}</td><td>forward adds {@code d} to x and y; inverse subtracts it</td></tr>
 * <tr><td>{@code lossy=<e>}</td><td>forward is {@code 2v + e}, inverse is {@code v / 2}, on x and y</td></tr>
 * <tr><td>{@code fail=always}</td><td>every transform fails</td></tr>
 * <tr><td>{@code fail=always kind=<GieFailureKind>}</td><td>the kind that failing transform reports; defaults to {@link GieFailureKind#COORD_OUT_OF_DOMAIN}</td></tr>
 * <tr><td>{@code kind=<GieFailureKind>}</td><td>on an <em>unusable</em> definition, the kind the construction failure reports; defaults to {@link GieFailureKind#INVALID_DEFINITION}</td></tr>
 * <tr><td>{@code left=<units>}, {@code right=<units>}</td><td>{@code P->left} / {@code P->right}; both default to {@link GieIoUnits#WHATEVER}</td></tr>
 * <tr><td>{@code inv}</td><td>{@code P->inverted}</td></tr>
 * <tr><td>{@code latlon}</td><td>{@code crs_dst_is_lat_lon_or_y_x}</td></tr>
 * </table>
 *
 * <p>{@code kind=} is what lets a fixture tell the two halves of the vacuity rule apart: a definition
 * that fails as {@link GieFailureKind#INVALID_DEFINITION} is proj4j agreeing with PROJ that the
 * definition is bad, whereas one that fails as {@link GieFailureKind#NOT_IMPLEMENTED} is proj4j not
 * having the operator at all. gie scores both as passes; only one of them is one. See
 * {@link ExpectedFailureVerdict}.
 *
 * <p>Both unit sides default to {@link GieIoUnits#WHATEVER} rather than to PROJ's
 * {@code RADIANS}/{@code CLASSIC}, so a fixture's deviations are plain Euclidean distances in the
 * coordinates' own units and can be checked with arithmetic instead of a geodesic solution. A fixture
 * that wants the angular branch says {@code left=RADIANS} explicitly.
 *
 * <h2>The call log</h2>
 *
 * <p>Every {@link GieOperation#transform} is appended to {@link #calls()} as {@code F} or {@code I}.
 * That is what makes {@code proj_roundtrip}'s phasing directly assertable: the correct sequence for
 * {@code n} trips in the forward direction is {@code F} followed by {@code n-1} repetitions of
 * {@code IF} and then a final {@code I} — {@code n} of each, alternating, starting forward and ending
 * inverse.
 */
final class FakeGieOperationFactory implements GieOperationFactory {

    private final List<String> calls = new ArrayList<String>();
    private final List<String> created = new ArrayList<String>();

    @Override
    public GieOperation create(String args) {
        created.add(args);
        return new Fake(args);
    }

    @Override
    public GieOperation createCrsToCrs(String sourceCrs, String targetCrs) {
        String args = sourceCrs + " -> " + targetCrs;
        created.add(args);
        return new Fake(args);
    }

    /** @return the transform log, {@code "F"} for forward and {@code "I"} for inverse. */
    List<String> calls() {
        return Collections.unmodifiableList(calls);
    }

    /** @return the transform log as one string, e.g. {@code "FIFIFI"}. */
    String callSequence() {
        StringBuilder out = new StringBuilder(calls.size());
        for (int i = 0; i < calls.size(); i++) {
            out.append(calls.get(i));
        }
        return out.toString();
    }

    /** @return every definition the runner asked for, in order. */
    List<String> created() {
        return Collections.unmodifiableList(created);
    }

    /** Forgets the log. */
    void reset() {
        calls.clear();
        created.clear();
    }

    /** The single failure type used throughout. */
    private static final class Failure implements GieFailure {

        private final GieFailureKind kind;
        private final String message;

        Failure(GieFailureKind kind, String message) {
            this.kind = kind;
            this.message = message;
        }

        @Override
        public GieFailureKind kind() {
            return kind;
        }

        @Override
        public String message() {
            return message;
        }

        @Override
        public Throwable cause() {
            return null;
        }
    }

    private final class Fake implements GieOperation {

        private final boolean usable;
        private final GieFailure creationFailure;
        private final double shift;
        private final double lossy;
        private final boolean alwaysFails;
        private final GieFailureKind failureKind;
        private final GieIoUnits left;
        private final GieIoUnits right;
        private final boolean inverted;
        private final boolean latLon;
        private GieFailure lastFailure;

        Fake(String args) {
            String text = args == null ? "" : args;
            this.usable = text.equals("fake") || text.startsWith("fake ");
            this.failureKind = kind(token(text, "kind"));
            this.creationFailure = usable
                    ? null
                    : new Failure(
                            failureKind == null ? GieFailureKind.INVALID_DEFINITION : failureKind,
                            "not a fake definition: \"" + text + "\"");
            this.shift = number(text, "shift", 0);
            this.lossy = number(text, "lossy", 0);
            this.alwaysFails = "always".equals(token(text, "fail"));
            this.left = units(token(text, "left"));
            this.right = units(token(text, "right"));
            this.inverted = hasFlag(text, "inv");
            this.latLon = hasFlag(text, "latlon");
        }

        @Override
        public boolean isUsable() {
            return usable;
        }

        @Override
        public GieFailure failure() {
            return creationFailure;
        }

        @Override
        public GieIoUnits leftUnits() {
            return left;
        }

        @Override
        public GieIoUnits rightUnits() {
            return right;
        }

        @Override
        public boolean isInverted() {
            return inverted;
        }

        @Override
        public boolean crsDstIsLatLonOrYX() {
            return latLon;
        }

        @Override
        public double[] transform(double[] in, GieDirection dir) {
            calls.add(dir == GieDirection.FORWARD ? "F" : "I");
            if (alwaysFails) {
                lastFailure = new Failure(
                        failureKind == null ? GieFailureKind.COORD_OUT_OF_DOMAIN : failureKind,
                        "fail=always");
                return null;
            }
            lastFailure = null;
            double[] out = in.clone();
            for (int i = 0; i < 2; i++) {
                if (dir == GieDirection.FORWARD) {
                    out[i] = lossy == 0 ? out[i] + shift : 2 * out[i] + lossy;
                } else {
                    out[i] = lossy == 0 ? out[i] - shift : out[i] / 2;
                }
            }
            return out;
        }

        @Override
        public GieFailure lastFailure() {
            return lastFailure;
        }
    }

    private static GieFailureKind kind(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        return GieFailureKind.valueOf(name);
    }

    private static GieIoUnits units(String name) {
        if (name == null || name.isEmpty()) {
            return GieIoUnits.WHATEVER;
        }
        return GieIoUnits.valueOf(name);
    }

    private static boolean hasFlag(String args, String flag) {
        String[] tokens = args.split(" ");
        for (int i = 0; i < tokens.length; i++) {
            if (tokens[i].equals(flag)) {
                return true;
            }
        }
        return false;
    }

    private static String token(String args, String key) {
        String needle = key + "=";
        String[] tokens = args.split(" ");
        for (int i = 0; i < tokens.length; i++) {
            if (tokens[i].startsWith(needle)) {
                return tokens[i].substring(needle.length());
            }
        }
        return null;
    }

    private static double number(String args, String key, double fallback) {
        String raw = token(args, key);
        if (raw == null || raw.isEmpty()) {
            return fallback;
        }
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
