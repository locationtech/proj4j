/*******************************************************************************
 * Copyright 2026 Proj4J contributors
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
package org.locationtech.proj4j.errors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.InvalidValueException;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.datum.AxisOrder;

/**
 * {@link AxisOrder}: the {@code Down} asymmetry, and the bare {@code new Error()}.
 *
 * <p>{@code AxisOrder} is one of the ten types {@code proj4j-geoapi} couples to, and
 * {@code AbstractCRS} probes axis order by pushing {@code new ProjCoordinate(1, 2, 3)} through
 * {@code fromENU} and reading the public fields — so these tests also pin the property that probe
 * depends on: {@code fromENU} is a pure sign-preserving permutation.
 */
public class AxisOrderTest {

    /**
     * {@code Axis.Down.toENU} negated but {@code fromENU} did not, so {@code +axis=…d} was not an
     * involution: a coordinate pushed to ENU and pulled back came out with its height negated.
     * Every other reversed axis — {@code Westing}, {@code Southing} — negates both ways, and
     * {@code BasicCoordinateTransform} composes exactly these two calls, {@code toENU} on the
     * source and {@code fromENU} on the target.
     */
    @Test
    public void downIsSymmetricLikeWestingAndSouthing() {
        AxisOrder end = AxisOrder.fromString("end");
        ProjCoordinate c = new ProjCoordinate(10.0, 20.0, 30.0);

        end.toENU(c);
        assertEquals("toENU must flip a Down height into an Up height", -30.0, c.z, 0.0);

        end.fromENU(c);
        assertEquals("fromENU must flip it back", 30.0, c.z, 0.0);
        assertEquals(10.0, c.x, 0.0);
        assertEquals(20.0, c.y, 0.0);
    }

    /**
     * The general property, over every axis triple that names one of each kind: {@code fromENU}
     * after {@code toENU} is the identity.
     */
    @Test
    public void everyAxisOrderRoundTripsThroughEnuAndBack() {
        String[] horizontals = { "e", "w" };
        String[] verticals = { "n", "s" };
        String[] heights = { "u", "d" };
        for (String h : horizontals) {
            for (String v : verticals) {
                for (String z : heights) {
                    String spec = h + v + z;
                    AxisOrder order = AxisOrder.fromString(spec);
                    ProjCoordinate c = new ProjCoordinate(1.0, 2.0, 3.0);
                    order.toENU(c);
                    order.fromENU(c);
                    assertEquals(spec + " x", 1.0, c.x, 0.0);
                    assertEquals(spec + " y", 2.0, c.y, 0.0);
                    assertEquals(spec + " z", 3.0, c.z, 0.0);
                }
            }
        }
    }

    /** And the other way round, since {@code BasicCoordinateTransform} uses both orders. */
    @Test
    public void everyAxisOrderRoundTripsFromEnuAndBack() {
        for (String spec : new String[] { "enu", "end", "wsu", "wsd", "esu", "wnd", "neu", "ned" }) {
            AxisOrder order = AxisOrder.fromString(spec);
            ProjCoordinate c = new ProjCoordinate(1.0, 2.0, 3.0);
            order.fromENU(c);
            order.toENU(c);
            assertEquals(spec + " x", 1.0, c.x, 0.0);
            assertEquals(spec + " y", 2.0, c.y, 0.0);
            assertEquals(spec + " z", 3.0, c.z, 0.0);
        }
    }

    /**
     * {@code fromENU} must remain a pure sign-preserving permutation, because
     * {@code proj4j-geoapi}'s {@code AbstractCRS} discovers axis order by probing it with
     * {@code (1, 2, 3)} and reading the magnitudes back.
     */
    @Test
    public void fromEnuRemainsASignedPermutation() {
        for (String spec : new String[] { "enu", "end", "neu", "wsd" }) {
            ProjCoordinate c = new ProjCoordinate(1.0, 2.0, 3.0);
            AxisOrder.fromString(spec).fromENU(c);
            double[] magnitudes = { Math.abs(c.x), Math.abs(c.y), Math.abs(c.z) };
            java.util.Arrays.sort(magnitudes);
            assertEquals(spec, 1.0, magnitudes[0], 0.0);
            assertEquals(spec, 2.0, magnitudes[1], 0.0);
            assertEquals(spec, 3.0, magnitudes[2], 0.0);
        }
    }

    @Test
    public void enuIsUnaffected() {
        ProjCoordinate c = new ProjCoordinate(1.0, 2.0, 3.0);
        AxisOrder.ENU.toENU(c);
        assertEquals(1.0, c.x, 0.0);
        assertEquals(2.0, c.y, 0.0);
        assertEquals(3.0, c.z, 0.0);
        AxisOrder.ENU.fromENU(c);
        assertEquals(1.0, c.x, 0.0);
        assertEquals(2.0, c.y, 0.0);
        assertEquals(3.0, c.z, 0.0);
    }

    // ------------------------------------------------------------------- the bare new Error()

    /**
     * {@code fromString} threw {@code new Error()} — no message, and an {@link Error}, so outside
     * {@code catch (Proj4jException)} and outside almost every caller's {@code catch (Exception)}
     * too. In a Spark executor that is a killed task rather than a rejected row.
     */
    @Test
    public void aWrongLengthAxisSpecThrowsATypedProj4jException() {
        for (String bad : new String[] { "", "e", "en", "enun" }) {
            try {
                AxisOrder.fromString(bad);
                fail("+axis=" + bad + " must be rejected");
            } catch (InvalidValueException e) {
                assertEquals(ErrorCause.INVALID_PARAM_VALUE, e.cause());
                assertTrue("the message must say what was wrong, and give the length. Was: "
                        + e.getMessage(), e.getMessage().contains("3"));
            }
        }
    }

    @Test
    public void anUnknownAxisDirectionThrowsATypedProj4jExceptionNamingTheCharacter() {
        try {
            AxisOrder.fromString("enx");
            fail("+axis=enx must be rejected");
        } catch (InvalidValueException e) {
            assertEquals(ErrorCause.INVALID_PARAM_VALUE, e.cause());
            assertTrue("the message must name the offending character. Was: " + e.getMessage(),
                    e.getMessage().contains("x"));
        }
    }

    @Test
    public void aNullAxisSpecThrowsATypedProj4jExceptionRatherThanNpe() {
        try {
            AxisOrder.fromString(null);
            fail("a null +axis must be rejected");
        } catch (InvalidValueException e) {
            assertEquals(ErrorCause.INVALID_PARAM_VALUE, e.cause());
        }
    }
}
