/*******************************************************************************
 * Copyright 2009, 2017 Martin Davis
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
package org.locationtech.proj4j;

import java.io.Serializable;
import java.text.DecimalFormat;


/**
 * Stores a the coordinates for a position
 * defined relative to some {@link CoordinateReferenceSystem}.
 * The coordinate is defined via X, Y, and optional Z ordinates.
 * Provides utility methods for comparing the ordinates of two positions and
 * for creating positions from Strings/storing positions as strings.
 * <p>
 * The primary use of this class is to represent coordinate
 * values which are to be transformed
 * by a {@link CoordinateTransform}.
 */
public class ProjCoordinate implements Serializable {

    private static final long serialVersionUID = -2978758815712780733L;

    /** The pattern {@link #DECIMAL_FORMAT} is built from: up to 16 fractional digits, at least one. */
    public static String DECIMAL_FORMAT_PATTERN = "0.0###############";

    /**
     * The format {@link #toString()} and {@link #toShortString()} render ordinates with.
     * <p>
     * Public and mutable for historical reasons. Two consequences worth knowing: assigning
     * {@link #DECIMAL_FORMAT_PATTERN} after class initialisation has no effect, because this field
     * was already built from it; and {@link DecimalFormat} is not thread-safe, so concurrent
     * {@code toString()} calls share one formatter.
     */
    public static DecimalFormat DECIMAL_FORMAT = new DecimalFormat(DECIMAL_FORMAT_PATTERN);

    /**
     * The X ordinate for this point.
     * <p>
     * Note: This member variable
     * can be accessed directly. In the future this direct access should
     * be replaced with getter and setter methods. This will require
     * refactoring of the Proj4J code base.
     */
    public double x;

    /**
     * The Y ordinate for this point.
     * <p>
     * Note: This member variable
     * can be accessed directly. In the future this direct access should
     * be replaced with getter and setter methods. This will require
     * refactoring of the Proj4J code base.
     */
    public double y;

    /**
     * The Z ordinate for this point.
     * If this variable has the value <code>Double.NaN</code>
     * then this coordinate does not have a Z value.
     * <p>
     * Note: This member variable
     * can be accessed directly. In the future this direct access should
     * be replaced with getter and setter methods. This will require
     * refactoring of the Proj4J code base.
     */
    public double z;

    /**
     * Creates a ProjCoordinate with default ordinate values.
     */
    public ProjCoordinate() {
        this(0.0, 0.0);
    }

    /**
     * Creates a ProjCoordinate using the provided double parameters.
     * The first double parameter is the x ordinate (or easting),
     * the second double parameter is the y ordinate (or northing),
     * and the third double parameter is the z ordinate (elevation or height).
     * <p>
     * Valid values should be passed for all three (3) double parameters. If
     * you want to create a horizontal-only point without a valid Z value, use
     * the constructor defined in this class that only accepts two (2) double
     * parameters.
     *
     * @param argX the x ordinate, or easting
     * @param argY the y ordinate, or northing
     * @param argZ the z ordinate, an elevation or height
     * @see #ProjCoordinate(double argX, double argY)
     */
    public ProjCoordinate(double argX, double argY, double argZ) {
        this.x = argX;
        this.y = argY;
        this.z = argZ;
    }

    /**
     * Creates a ProjCoordinate using the provided double parameters.
     * The first double parameter is the x ordinate (or easting),
     * the second double parameter is the y ordinate (or northing).
     * This constructor is used to create a "2D" point, so the Z ordinate
     * is automatically set to Double.NaN.
     *
     * @param argX the x ordinate, or easting
     * @param argY the y ordinate, or northing
     */
    public ProjCoordinate(double argX, double argY) {
        this.x = argX;
        this.y = argY;
        this.z = Double.NaN;
    }

    /**
     * Create a ProjCoordinate by parsing a String in the same format as returned
     * by the toString method defined by this class.
     *
     * @param argToParse the string to parse
     */
    public ProjCoordinate(String argToParse) {
        // Make sure the String starts with "ProjCoordinate: ".
        boolean startsWith = argToParse.startsWith("ProjCoordinate: ");

        if (startsWith == false) {
            IllegalArgumentException toThrow = new IllegalArgumentException
                    ("The input string was not in the proper format.");

            throw toThrow;
        }

        // 15 characters should cut out "ProjCoordinate: ".
        String chomped = argToParse.substring(16);

        // Get rid of the starting and ending square brackets.

        String withoutFrontBracket = chomped.substring(1);

        // Calc the position of the last bracket.
        int length = withoutFrontBracket.length();
        int positionOfCharBeforeLast = length - 2;
        String withoutBackBracket = withoutFrontBracket.substring(0,
                positionOfCharBeforeLast);

        // We should be left with just the ordinate values as strings,
        // separated by spaces. Split them into an array of Strings.
        String[] parts = withoutBackBracket.split(" ");

        // Get number of elements in Array. There should be two (2) elements
        // or three (3) elements.
        // If we don't have an array with two (2) or three (3) elements,
        // then we need to throw an exception.
        if (parts.length != 2) {
            if (parts.length != 3) {
                IllegalArgumentException toThrow = new IllegalArgumentException
                        ("The input string was not in the proper format.");

                throw toThrow;
            }
        }

        // Convert strings to doubles.
        this.x = Double.parseDouble(parts[0]);
        this.y = Double.parseDouble(parts[0]);

        // You might not always have a Z ordinate. If you do, set it.
        if (parts.length == 3) {
            this.z = Double.parseDouble(parts[0]);
        }
    }

    /**
     * Sets the value of this coordinate to
     * be equal to the given coordinate's ordinates.
     *
     * @param p the coordinate to copy
     */
    public void setValue(ProjCoordinate p) {
        this.x = p.x;
        this.y = p.y;
        this.z = p.z;
    }

    /**
     * Sets the value of this coordinate to
     * be equal to the given ordinates.
     * The Z ordinate is set to <code>NaN</code>.
     *
     * @param x the x ordinate
     * @param y the y ordinate
     */
    public void setValue(double x, double y) {
        this.x = x;
        this.y = y;
        this.z = Double.NaN;
    }

    /**
     * Sets the value of this coordinate to
     * be equal to the given ordinates.
     *
     * @param x the x ordinate
     * @param y the y ordinate
     * @param z the z ordinate
     */
    public void setValue(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    /**
     * Discards the z ordinate, making this a 2D coordinate again by setting z to
     * {@code Double.NaN} — this class's sentinel for "no height".
     */
    public void clearZ() {
        z = Double.NaN;
    }

    /**
     * Returns a boolean indicating if the X ordinate value of the
     * ProjCoordinate provided as an ordinate is equal to the X ordinate
     * value of this ProjCoordinate. Because we are working with floating
     * point numbers the ordinates are considered equal if the difference
     * between them is less than the specified tolerance.
     *
     * <h4>The comparison is one-sided, and has been since 1.x</h4>
     *
     * <p>The implementation tests {@code argToCompare.x - this.x > argTolerance}, without taking the
     * absolute value. So this returns true whenever the argument's x exceeds this coordinate's by no
     * more than the tolerance — <em>including when it is smaller by any amount whatever</em>. With a
     * tolerance of {@code 0.001}, {@code new ProjCoordinate(100, 0).areXOrdinatesEqual(new
     * ProjCoordinate(0, 0), 0.001)} is true, and reversing the two operands makes it false. The
     * relation is therefore not symmetric.
     *
     * <p>Documented rather than corrected because callers may depend on it. For a symmetric test of
     * all three ordinates, use {@link #equals(Object)}, which compares exactly.
     *
     * @param argToCompare the coordinate to compare against
     * @param argTolerance the largest amount by which {@code argToCompare}'s x may exceed this one's
     * @return true if {@code argToCompare.x - this.x} is not greater than {@code argTolerance}
     */
    public boolean areXOrdinatesEqual(ProjCoordinate argToCompare,
                                      double argTolerance) {
        // Subtract the x ordinate values and then see if the difference
        // between them is less than the specified tolerance. If the difference
        // is less, return true.
        double difference = argToCompare.x - this.x;

        if (difference > argTolerance) {
            return false;
        } else {
            return true;
        }
    }

    /**
     * Returns a boolean indicating if the Y ordinate value of the
     * ProjCoordinate provided as an ordinate is equal to the Y ordinate
     * value of this ProjCoordinate. Because we are working with floating
     * point numbers the ordinates are considered equal if the difference
     * between them is less than the specified tolerance.
     * <p>
     * The comparison is one-sided and not symmetric, exactly as in
     * {@link #areXOrdinatesEqual(ProjCoordinate, double)} — see there for why.
     *
     * @param argToCompare the coordinate to compare against
     * @param argTolerance the largest amount by which {@code argToCompare}'s y may exceed this one's
     * @return true if {@code argToCompare.y - this.y} is not greater than {@code argTolerance}
     */
    public boolean areYOrdinatesEqual(ProjCoordinate argToCompare,
                                      double argTolerance) {
        // Subtract the y ordinate values and then see if the difference
        // between them is less than the specified tolerance. If the difference
        // is less, return true.
        double difference = argToCompare.y - this.y;

        if (difference > argTolerance) {
            return false;
        } else {
            return true;
        }
    }

    /**
     * Returns a boolean indicating if the Z ordinate value of the
     * ProjCoordinate provided as an ordinate is equal to the Z ordinate
     * value of this ProjCoordinate. Because we are working with floating
     * point numbers the ordinates are considered equal if the difference
     * between them is less than the specified tolerance.
     * <p>
     * If both Z ordinate values are Double.NaN this method will return
     * true. If one Z ordinate value is a valid double value and one is
     * Double.Nan, this method will return false.
     * <p>
     * Where both values are valid, the comparison is one-sided and not symmetric, exactly as in
     * {@link #areXOrdinatesEqual(ProjCoordinate, double)} — see there for why.
     *
     * @param argToCompare the coordinate to compare against
     * @param argTolerance the largest amount by which {@code argToCompare}'s z may exceed this one's
     * @return true if both z values are NaN, or if neither is and
     *         {@code argToCompare.z - this.z} is not greater than {@code argTolerance};
     *         false if exactly one of them is NaN
     */
    public boolean areZOrdinatesEqual(ProjCoordinate argToCompare,
                                      double argTolerance) {
        // We have to handle Double.NaN values here, because not every
        // ProjCoordinate will have a valid Z Value.
        if (Double.isNaN(z)) {
            if (Double.isNaN(argToCompare.z)) {
                // Both the z ordinate values are Double.Nan. Return true.
                return true;
            } else {
                // We've got one z ordinate with a valid value and one with
                // a Double.NaN value. Return false.
                return false;
            }
        }

        // We have a valid z ordinate value in this ProjCoordinate object.
        else {
            if (Double.isNaN(argToCompare.z)) {
                // We've got one z ordinate with a valid value and one with
                // a Double.NaN value. Return false.
                return false;
            }

            // If we get to this point in the method execution, we have to
            // z ordinates with valid values, and we need to do a regular
            // comparison. This is done in the remainder of the method.
        }

        // Subtract the z ordinate values and then see if the difference
        // between them is less than the specified tolerance. If the difference
        // is less, return true.
        double difference = argToCompare.z - this.z;

        if (difference > argTolerance) {
            return false;
        } else {
            return true;
        }
    }

    /**
     * Whether this coordinate and another are the same point, on <b>all three</b> ordinates.
     *
     * <h4>{@code z} used to be ignored, and that quietly weakened every coordinate comparison</h4>
     *
     * <p>This method compared {@code x} and {@code y} only, and {@link #hashCode()} hashed the same
     * two. Nothing in the library announced that, so every {@code assertEquals} on a
     * {@code ProjCoordinate} was silently a 2D assertion — {@code RepeatedTransformTest} among them,
     * where the transform under test invents a {@code z} from a 2D input (EPSG:4326 to EPSG:27700
     * returns {@code z ~= -49.85}), so a drifting height could not have been seen. The fix belongs
     * here rather than at the call sites: a caller that genuinely wants a 2D comparison already has
     * {@link #areXOrdinatesEqual(ProjCoordinate, double)} and
     * {@link #areYOrdinatesEqual(ProjCoordinate, double)}, and asking every caller to opt in to
     * comparing {@code z} is the arrangement that produced the blind spot.
     *
     * <h4>{@code NaN} compares equal to {@code NaN}, per ordinate</h4>
     *
     * <p>Required, not cosmetic. {@code NaN} is this class's <em>sentinel for "no height"</em> — the
     * two-argument constructor, {@link #setValue(double, double)} and {@link #clearZ()} all set
     * {@code z = Double.NaN} deliberately — so under {@code z != p.z} a horizontal-only coordinate
     * would never have equalled another horizontal-only coordinate, and adding {@code z} to a raw
     * {@code !=} comparison would have made {@code equals} almost always false. It also removes the
     * standing {@code equals}/{@code hashCode} contract violation: {@code hashCode} has always used
     * {@link Double#doubleToLongBits(double)}, which gives all {@code NaN}s one canonical hash, so
     * two objects that hashed alike could not compare alike.
     *
     * <p>{@code -0.0} still equals {@code 0.0}, as it did, and {@link #hashCode()} normalises
     * {@code -0.0} to {@code 0.0} to keep the two consistent in that direction as well.
     *
     * @param other the object to compare against
     * @return true if {@code other} is a ProjCoordinate with the same x, y and z
     */
    public boolean equals(Object other) {
        if (!(other instanceof ProjCoordinate)) {
            return false;
        }
        ProjCoordinate p = (ProjCoordinate) other;
        return sameOrdinate(x, p.x) && sameOrdinate(y, p.y) && sameOrdinate(z, p.z);
    }

    /**
     * Ordinate equality: {@code ==}, widened so that the absent-value sentinel equals itself.
     *
     * @param a one ordinate
     * @param b the other
     * @return true if both are the same value, or both are {@code NaN}
     */
    private static boolean sameOrdinate(double a, double b) {
        return a == b || (Double.isNaN(a) && Double.isNaN(b));
    }

    /**
     * Gets a hashcode for this coordinate.
     *
     * @return a hashcode for this coordinate
     */
    public int hashCode() {
        //Algorithm from Effective Java by Joshua Bloch [Jon Aquino]
        int result = 17;
        result = 37 * result + hashCode(x);
        result = 37 * result + hashCode(y);
        // z is part of equals, so it must be here too. It was in neither.
        result = 37 * result + hashCode(z);
        return result;
    }

    /**
     * Computes a hash code for a double value, using the algorithm from
     * Joshua Bloch's book <i>Effective Java"</i>
     * <p>
     * {@code -0.0} is normalised to {@code 0.0} first, because
     * {@link #equals(Object)} compares with {@code ==} and {@code -0.0 == 0.0}; without the
     * normalisation the two would be equal with different hashes. {@code Double.doubleToLongBits}
     * already collapses every {@code NaN} to one bit pattern, which is what makes the
     * {@code NaN}-equals-{@code NaN} rule in {@code equals} consistent with this.
     *
     * @return a hashcode for the double value
     */
    private static int hashCode(double x) {
        long f = Double.doubleToLongBits(x == 0.0 ? 0.0 : x);
        return (int) (f ^ (f >>> 32));
    }

    /**
     * Returns a string representing the ProjPoint in the format:
     * <code>ProjCoordinate[X Y Z]</code>.
     * <p>
     * Example:
     * <pre>
     *    ProjCoordinate[6241.11 5218.25 12.3]
     * </pre>
     */
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("ProjCoordinate[");
        builder.append(this.x);
        builder.append(" ");
        builder.append(this.y);
        builder.append(" ");
        builder.append(this.z);
        builder.append("]");

        return builder.toString();
    }

    /**
     * Returns a string representing the ProjPoint in the format:
     * <code>[X Y]</code>
     * or <code>[X, Y, Z]</code>.
     * Z is not displayed if it is NaN.
     * <p>
     * Example:
     * <pre>
     *          [6241.11, 5218.25, 12.3]
     * </pre>
     *
     * @return the ordinates in square brackets, comma-separated
     */
    public String toShortString() {
        StringBuilder builder = new StringBuilder();
        builder.append("[");
        builder.append(DECIMAL_FORMAT.format(x).replace(",", "."));
        builder.append(", ");
        builder.append(DECIMAL_FORMAT.format(y).replace(",", "."));
        if (!Double.isNaN(z)) {
            builder.append(", ");
            builder.append(this.z);
        }
        builder.append("]");

        return builder.toString();
    }

    /**
     * Indicates whether this coordinate carries a height.
     * <p>
     * Only {@code Double.NaN} counts as absent; an infinite z is reported as present. Contrast
     * {@link #hasValidXandYOrdinates()}, which rejects infinities too.
     *
     * @return true if z is not {@code Double.NaN}
     */
    public boolean hasValidZOrdinate() {
        if (Double.isNaN(this.z)) {
            return false;
        } else {
            return true;
        }
    }

    /**
     * Indicates if this ProjCoordinate has valid X ordinate and Y ordinate
     * values. Values are considered invalid if they are Double.NaN or
     * positive/negative infinity.
     *
     * @return true if both x and y are finite
     */
    public boolean hasValidXandYOrdinates() {
        if (Double.isNaN(x)) {
            return false;
        } else if (Double.isInfinite(this.x) == true) {
            return false;
        }

        if (Double.isNaN(y)) {
            return false;
        } else if (Double.isInfinite(this.y) == true) {
            return false;
        } else {
            return true;
        }
    }
}
