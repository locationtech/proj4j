/*
 * Copyright 2026 The Proj4J Contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.locationtech.proj4j.conformance.parse;

import java.util.Arrays;

/**
 * A coordinate as written on an {@code accept} or {@code expect} line: up to
 * four ordinates plus the count actually present in the text. Immutable.
 *
 * <p>{@link #dimensionsGiven()} is not cosmetic — the comparator uses it to
 * decide how many ordinates to compare, so a 2D {@code expect} against a 4D
 * result must not be judged on z or t.
 *
 * <p>An {@link #isError() error} coordinate is what {@code proj_coord_error()}
 * returns: all four ordinates {@code HUGE_VAL}. It means "this line did not
 * contain two parseable numbers", and it must never be confused with a
 * successfully parsed coordinate.
 */
public final class GieCoord {

    /** C {@code HUGE_VAL}. */
    public static final double HUGE_VAL = Double.POSITIVE_INFINITY;

    private final double[] v;
    private final int dimensionsGiven;
    private final boolean error;

    GieCoord(double[] v, int dimensionsGiven, boolean error) {
        this.v = new double[] {v[0], v[1], v[2], v[3]};
        this.dimensionsGiven = dimensionsGiven;
        this.error = error;
    }

    /** {@code proj_coord_error()}: all four ordinates {@code HUGE_VAL}. */
    static GieCoord error(int dimensionsGiven) {
        return new GieCoord(new double[] {HUGE_VAL, HUGE_VAL, HUGE_VAL, HUGE_VAL},
                dimensionsGiven, true);
    }

    /** Ordinate {@code i}, {@code 0 <= i < 4}. */
    public double v(int i) {
        return v[i];
    }

    public double x() {
        return v[0];
    }

    public double y() {
        return v[1];
    }

    public double z() {
        return v[2];
    }

    public double t() {
        return v[3];
    }

    /** A defensive copy of all four ordinates. */
    public double[] toArray() {
        return new double[] {v[0], v[1], v[2], v[3]};
    }

    /** How many numbers the source line actually carried, 0 to 4. */
    public int dimensionsGiven() {
        return dimensionsGiven;
    }

    /** True when fewer than two numbers parsed. */
    public boolean isError() {
        return error;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof GieCoord)) {
            return false;
        }
        GieCoord other = (GieCoord) o;
        return dimensionsGiven == other.dimensionsGiven
                && error == other.error
                && Arrays.equals(v, other.v);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(v) * 31 + dimensionsGiven + (error ? 1 : 0);
    }

    @Override
    public String toString() {
        if (error) {
            return "GieCoord[error]";
        }
        StringBuilder sb = new StringBuilder("GieCoord[");
        for (int i = 0; i < dimensionsGiven; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }
}
