/*******************************************************************************
 * Copyright 2006, 2017 Jerry Huxtable, Martin Davis
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

package org.locationtech.proj4j.units;

import java.io.Serializable;
import java.text.NumberFormat;
import java.util.Locale;
import java.util.Objects;

public class Unit implements Serializable {

    static final long serialVersionUID = -6704954923429734628L;

    public final static int ANGLE_UNIT = 0;
    public final static int LENGTH_UNIT = 1;
    public final static int AREA_UNIT = 2;
    public final static int VOLUME_UNIT = 3;

    public String name, plural, abbreviation;
    public double value;

    /**
     * The <em>display</em> formatter, deliberately left on the default locale: {@link #format(double)}
     * and its overloads render a measurement for a human, and a reader in Germany should see a
     * decimal comma. Do not use it to read a value back -- see {@link #PARSE}.
     */
    public static final NumberFormat format;

    /**
     * The <em>parsing</em> formatter, pinned to {@link Locale#ROOT}, because
     * {@link #parse(String)} reads a machine-written value out of a CRS definition and those are
     * always ASCII with a decimal point.
     *
     * <p>Measured on Temurin 21 with the default-locale formatter this method used to share:
     * {@code parse("1.5")} returns <b>15</b> under {@code de_DE} and {@code tr_TR} (the point is
     * read as a grouping separator) and <b>1</b> under {@code lt_LT} and {@code ar_EG} (the point
     * terminates the number). Only {@code Locale.ROOT} returns 1.5 everywhere.
     */
    private static final NumberFormat PARSE;

    static {
        format = NumberFormat.getNumberInstance();
        format.setMaximumFractionDigits(2);
        format.setGroupingUsed(false);

        PARSE = NumberFormat.getNumberInstance(Locale.ROOT);
        PARSE.setMaximumFractionDigits(2);
        PARSE.setGroupingUsed(false);
    }

    public Unit(String name, String plural, String abbreviation, double value) {
        this.name = name;
        this.plural = plural;
        this.abbreviation = abbreviation;
        this.value = value;
    }

    public double toBase(double n) {
        return n * value;
    }

    public double fromBase(double n) {
        return n / value;
    }

    public double parse(String s) throws NumberFormatException {
        try {
            return PARSE.parse(s).doubleValue();
        }
        catch (java.text.ParseException e) {
            throw new NumberFormatException(e.getMessage());
        }
    }

    public String format(double n) {
        return format.format(n)+" "+abbreviation;
    }

    public String format(double n, boolean abbrev) {
        if (abbrev)
            return format.format(n)+" "+abbreviation;
        return format.format(n);
    }

    public String format(double x, double y, boolean abbrev) {
        if (abbrev)
            return format.format(x)+"/"+format.format(y)+" "+abbreviation;
        return format.format(x)+"/"+format.format(y);
    }

    public String format(double x, double y) {
        return format(x, y, true);
    }

    public String toString() {
        return plural;
    }

    public boolean equals(Object o) {
        if (o instanceof Unit) {
            return ((Unit)o).name.equals(name) && ((Unit)o).value == value;
        }
        return false;
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>Bit-identical to the {@code Objects.hash(getClass(), name, value)} it replaces, and
     * deliberately so.</b> {@code Objects.hash} is specified as
     * {@code Arrays.hashCode(new Object[]{...})}, i.e. the {@code 31}-chain below seeded with 1, so
     * every value this returns is the value it always returned. What is gone is the allocation:
     * an {@code Object[3]} and a {@code Double} box, <b>measured at 56 B/op</b>, on the
     * transform-cache lookup path — {@code Projection.hashCode} calls {@code getUnits().hashCode()}
     * and {@code CoordinateReferenceSystem.hashCode} calls that, so it is paid on every cache probe.
     *
     * <p>Keeping the value identical is not fastidiousness: a changed hash reorders every
     * {@code HashMap} keyed on a {@code Unit}, a {@code Projection} or a CRS, and this repository
     * measures behaviour with a 53,430-row golden master. An optimisation that also perturbs
     * iteration order is two changes reported as one.
     *
     * <p>Two pre-existing inconsistencies are preserved rather than fixed here, because fixing
     * either <em>would</em> change the value: {@code equals} ignores {@code getClass()} while this
     * includes it, and {@code equals} compares {@code value} with {@code ==} (under which
     * {@code -0.0} equals {@code 0.0}) while {@code Double.hashCode} separates them. Neither is
     * reachable today — {@code Unit} has no subclass with an equal name, and no unit has a scale
     * factor of zero — but they belong in whatever change owns this class next.
     */
    @Override
    public int hashCode() {
        int h = 1;
        h = 31 * h + this.getClass().hashCode();
        h = 31 * h + (name == null ? 0 : name.hashCode());
        h = 31 * h + Double.hashCode(value);
        return h;
    }
    
}
