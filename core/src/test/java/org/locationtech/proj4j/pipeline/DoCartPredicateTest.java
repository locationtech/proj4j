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
package org.locationtech.proj4j.pipeline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.locationtech.proj4j.datum.Ellipsoid;
import org.locationtech.proj4j.util.ProjectionMath;

/**
 * The {@code do_cart} predicate of {@code cs2cs_emulation_setup}, and the two tolerances
 * inside it.
 *
 * <h2>The line under test</h2>
 *
 * <p>{@code 9.8.1:src/create.cpp:135-140}, verbatim, inside the {@code towgs84} loop and
 * reached only when every one of the seven Helmert parameters is zero:
 *
 * <pre>
 * if (!(fabs(P-&gt;a_orig - 6378137.0) &lt; 1e-8 &amp;&amp;
 *       fabs(P-&gt;es_orig - 0.0066943799901413) &lt; 1e-15)) { do_cart = 1; }
 * </pre>
 *
 * <p>{@link Cs2csOperator}'s {@code isWgs84Ellipsoid} reproduces it literally. When the
 * predicate is <em>false</em> — the declared ellipsoid is not WGS84 to within those two
 * tolerances — {@code create.cpp:167-197} builds the pair
 * {@code proj=cart a=<a_orig> es=<es_orig>} and {@code proj=cart ellps=WGS84} and runs the
 * second one inverted, so an all-zero {@code +towgs84} still performs the <em>change of
 * ellipsoid</em>. When it is true, nothing is built and the operation is the identity.
 *
 * <h2>Why this needed a test of its own</h2>
 *
 * <p>PROJ's <em>other</em> code path does the opposite, and a maintainer who reads only that
 * one has a documented-looking reason to change this line.
 * {@code 9.8.1:src/iso19111/io.cpp:9436-9450} deletes an adjacent {@code cart} /
 * {@code inv cart} pair when the two ellipsoids are the literal strings {@code GRS80} and
 * {@code WGS84} — a <b>string</b> comparison, with <b>no tolerance at all</b>. That is the
 * CRS-parser path. This is the {@code pj_init} path, and it is the one the whole gie corpus
 * runs on. They disagree on purpose, and the disagreement is what
 * {@link #grs80KeepsTheCartPairWhichIsWhereTheTwoPROJPathsDisagree()} pins.
 *
 * <p>{@link Cs2csEmulationHelperTest} covers the neighbouring {@code create.cpp:125} branch —
 * a grid shift suppressing both the Helmert and this round trip — but it uses
 * {@code +ellps=bessel} on both arms, so the predicate answers "not WGS84" in both and is
 * never exercised in its discriminating direction.
 *
 * <h2>References</h2>
 *
 * <p>Every expected number below is from <b>{@code cct} (PROJ 9.8.1, April 10th 2026)</b>,
 * never from proj4j's own output. {@code cct} is the right tool here and {@code cs2cs} is
 * not: {@code cs2cs} promotes both sides to full CRSs and therefore runs the
 * {@code iso19111/io.cpp} path, which is precisely the path that behaves differently. Every
 * row was taken with
 *
 * <pre>echo "9 50 0 0" | cct -d 18 &lt;definition&gt;</pre>
 *
 * <table border="1">
 * <caption>{@code cct} 9.8.1, input {@code 9 50 0}</caption>
 * <tr><th>definition (all with {@code +proj=longlat +towgs84=0,0,0})</th>
 *     <th>latitude out</th><th>height out</th><th>{@code do_cart}</th></tr>
 * <tr><td>{@code +ellps=GRS80}</td>
 *     <td>50.000000000928629618</td><td>0.000061428174376488</td><td><b>1</b></td></tr>
 * <tr><td>{@code +ellps=WGS84}</td>
 *     <td>50.000000000000000000</td><td>0.000000000000000000</td><td>0</td></tr>
 * <tr><td>{@code +a=6378137 +es=0.0066943799901413}</td>
 *     <td>50.000000000000000000</td><td>0.000000000000000000</td><td>0</td></tr>
 * <tr><td>{@code +a=6378137.000000005 +es=0.0066943799901413}</td>
 *     <td>50.000000000000000000</td><td>0.000000000000000000</td><td>0</td></tr>
 * <tr><td>{@code +a=6378137.00000002 +es=0.0066943799901413}</td>
 *     <td>49.999999999999992895</td><td>-0.000000019557774067</td><td><b>1</b></td></tr>
 * <tr><td>{@code +a=6378136.999999995 +es=0.0066943799901413}</td>
 *     <td>50.000000000000000000</td><td>0.000000000000000000</td><td>0</td></tr>
 * <tr><td>{@code +a=6378136.99999998 +es=0.0066943799901413}</td>
 *     <td>49.999999999999992895</td><td>0.000000020489096642</td><td><b>1</b></td></tr>
 * <tr><td>{@code +a=6378137 +es=0.0066943799901418}</td>
 *     <td>50.000000000000000000</td><td>0.000000000000000000</td><td>0</td></tr>
 * <tr><td>{@code +a=6378137 +es=0.0066943799901433}</td>
 *     <td>50.000000000000056843</td><td>0.000000003725290298</td><td><b>1</b></td></tr>
 * </table>
 *
 * <p>The four non-zero heights are the whole discrimination, and they are exact zeros on the
 * other side rather than merely small ones: when the predicate holds, no {@code cart} pair is
 * constructed at all, so the operation is <em>bit-identical</em> to the input. That makes
 * "moved / did not move" a crisp observable even where the boundary probes move the point by
 * only 20 pm.
 *
 * <p><b>One known sub-picometre divergence from upstream, recorded rather than asserted
 * away.</b> {@code create.cpp:192} builds the hub leg as {@code proj=cart ellps=WGS84}, whose
 * {@code es} is {@code 0.0066943799901413165} (derived from {@code rf=298.257223563});
 * {@link Cs2csOperator} builds it from the same {@code 0.0066943799901413} literal the
 * predicate compares against. The two differ by 1.6e-17 in {@code es}, i.e. about 31 pm of
 * height at this latitude — five orders below the tightest gie bar, and below the last digit
 * {@code cct -d 18} prints, which is why every row above is reproduced exactly.
 */
public class DoCartPredicateTest {

    private final PipelineFactory factory = new PipelineFactory();

    /** {@code create.cpp:131}. */
    private static final double WGS84_A = 6378137.0;

    /** {@code create.cpp:132} — the truncated literal, not {@code Ellipsoid.WGS84}'s {@code es}. */
    private static final double WGS84_ES = 0.0066943799901413;

    /** {@code create.cpp:135}. */
    private static final double A_TOLERANCE = 1e-8;

    /** {@code create.cpp:136}. */
    private static final double ES_TOLERANCE = 1e-15;

    private static final double DEG = ProjectionMath.DTR;

    // --------------------------------------------------------------- the discriminating pair

    /**
     * The pair that {@link Cs2csEmulationHelperTest} could not make, because both of its arms
     * were {@code +ellps=bessel}: two definitions identical but for the ellipsoid, one of which
     * keeps the {@code cart} / {@code inv cart} round trip and one of which drops it.
     *
     * <p>GRS80 and WGS84 share {@code a = 6378137} exactly, so the {@code a} half of the
     * predicate holds for both and the <em>whole</em> decision rests on the {@code es} half.
     * The gap is 3.28e-11, which is 3.3e4 times the 1e-15 bar — comfortably outside it, which
     * is why the round trip is kept, and why a maintainer who "simplifies" this to the
     * {@code io.cpp} string comparison would silently delete a real change of ellipsoid.
     */
    @Test
    public void grs80KeepsTheCartPairWhichIsWhereTheTwoPROJPathsDisagree() {
        // The premise, measured rather than asserted from memory.
        assertEquals("GRS80 and WGS84 must share a, or this pair does not isolate the es half",
                Ellipsoid.WGS84.getEquatorRadius(), Ellipsoid.GRS80.getEquatorRadius(), 0.0);
        double esGap = Math.abs(Ellipsoid.GRS80.getEccentricitySquared() - WGS84_ES);
        assertEquals("GRS80's es sits this far from create.cpp:132's literal", 3.275949e-11,
                esGap, 1e-17);
        assertTrue("and that is outside the 1e-15 bar by four orders, which is the whole point",
                esGap > 1e4 * ES_TOLERANCE);

        Cs2csOperator grs80 = operatorOf("+proj=longlat +ellps=GRS80 +towgs84=0,0,0");
        assertHasCartPair(true, grs80);

        Cs2csOperator wgs84 = operatorOf("+proj=longlat +ellps=WGS84 +towgs84=0,0,0");
        assertHasCartPair(false, wgs84);

        // cct 9.8.1, "9 50 0" -> 50.000000000928629618  0.000061428174376488
        double[] shifted = forward("+proj=longlat +ellps=GRS80 +towgs84=0,0,0");
        assertEquals(50.000000000928629618, shifted[1] / DEG, 1e-13);
        assertEquals(0.000061428174376488, shifted[2], 5e-19);

        // cct 9.8.1, "9 50 0" -> exactly the input back.
        double[] untouched = forward("+proj=longlat +ellps=WGS84 +towgs84=0,0,0");
        assertEquals("dropping the pair must be bit-identity, not a small number",
                50.0 * DEG, untouched[1], 0.0);
        assertEquals(0.0, untouched[2], 0.0);
    }

    /**
     * The control for the control: without a {@code +towgs84} there is no datum shift declared
     * at all, the {@code while (p)} loop of {@code create.cpp:127} is never entered, and the
     * predicate is never consulted. So the GRS80 assertion above is about the predicate and not
     * merely about the ellipsoid.
     *
     * <p>{@code cct -d 18 +proj=longlat +ellps=GRS80} returns {@code 9 50 0} unchanged.
     */
    @Test
    public void withoutATowgs84ThePredicateIsNeverReached() {
        assertHasCartPair(false, "+proj=longlat +ellps=GRS80");
        double[] out = forward("+proj=longlat +ellps=GRS80");
        assertEquals(50.0 * DEG, out[1], 0.0);
        assertEquals(0.0, out[2], 0.0);
    }

    /**
     * A non-zero {@code +towgs84} takes the other branch entirely: a Helmert is built, and
     * {@code create.cpp:167} then builds the {@code cart} pair because of the Helmert rather
     * than because of the predicate. Pinned so that a future reading of "GRS80 keeps cart"
     * cannot be mistaken for "cart depends only on the ellipsoid".
     */
    @Test
    public void aNonZeroTowgs84BuildsTheCartPairThroughTheHelmertBranchInstead() {
        Cs2csOperator op = operatorOf("+proj=longlat +ellps=WGS84 +towgs84=1,0,0");
        assertTrue("a non-zero towgs84 must build a Helmert: " + op, op.toString().contains("helmert"));
        assertHasCartPair(true, op);
    }

    // ------------------------------------------------------------------- the 1e-8 on a

    /**
     * {@code fabs(P->a_orig - 6378137.0) < 1e-8}, probed on both sides and in both directions.
     *
     * <p>The probe values are chosen so that the tolerance, and not the representability of a
     * double, is what decides: one ULP at 6378137 is 9.31e-10, so 4.66e-9 is five ULP inside the
     * bar and 1.96e-8 is twenty-one ULP outside it. Both are written as the shortest strings
     * that round-trip to the intended doubles, and the parsed value is asserted before the
     * verdict is, so a change in the parser cannot silently move the probe.
     */
    @Test
    public void theSemiMajorAxisToleranceOf1e8Discriminates() {
        // Straddling above.
        assertToleranceProbe("+a=6378137.000000005", 6378137.000000005, WGS84_A, A_TOLERANCE, false);
        assertToleranceProbe("+a=6378137.00000002", 6378137.00000002, WGS84_A, A_TOLERANCE, true);
        // ... and below, because fabs() is symmetric and a one-sided test would not show it.
        assertToleranceProbe("+a=6378136.999999995", 6378136.999999995, WGS84_A, A_TOLERANCE, false);
        assertToleranceProbe("+a=6378136.99999998", 6378136.99999998, WGS84_A, A_TOLERANCE, true);

        // cct 9.8.1: the two outside probes move the height by exactly the change of ellipsoid.
        // The tolerance is 5e-19, i.e. half the last place cct -d 18 printed.
        assertMoved(forward(aProbe("6378137.00000002")), -0.000000019557774067);
        assertMoved(forward(aProbe("6378136.99999998")), 0.000000020489096642);
        // The two inside probes are bit-identity, because nothing was built.
        assertUnmoved(forward(aProbe("6378137.000000005")));
        assertUnmoved(forward(aProbe("6378136.999999995")));

        // And the far field, so a tolerance accidentally widened to "anything" also fails.
        assertHasCartPair(true, operatorOf(aProbe("6378388")));
    }

    // ------------------------------------------------------------------ the 1e-15 on es

    /**
     * {@code fabs(P->es_orig - 0.0066943799901413) < 1e-15}, probed the same way. One ULP at
     * 0.0066943799901413 is 8.67e-19, so 5.00e-16 is 576 ULP inside the bar and 2.00e-15 is
     * 2,306 ULP outside it.
     *
     * <p>The literal itself is used as the zero point, and it is <em>not</em>
     * {@link Ellipsoid#WGS84}'s {@code es}: {@code create.cpp:132} writes a truncated
     * {@code 0.0066943799901413} where the ellipsoid table's {@code rf=298.257223563} derives
     * {@code 0.0066943799901413165}. The 1.6e-17 gap is inside 1e-15, which is why
     * {@code +ellps=WGS84} passes the predicate at all — assert that too, because a predicate
     * that rejected its own namesake ellipsoid would be a very quiet defect.
     */
    @Test
    public void theEccentricitySquaredToleranceOf1e15Discriminates() {
        double wgs84Derived = Ellipsoid.WGS84.getEccentricitySquared();
        assertTrue("Ellipsoid.WGS84's derived es must sit inside the bar against the literal, or "
                        + "+ellps=WGS84 would take the cart branch",
                Math.abs(wgs84Derived - WGS84_ES) < ES_TOLERANCE);
        assertEquals(1.6479873e-17, Math.abs(wgs84Derived - WGS84_ES), 1e-24);

        assertToleranceProbe("+es=0.0066943799901418", 0.0066943799901418, WGS84_ES,
                ES_TOLERANCE, false);
        assertToleranceProbe("+es=0.0066943799901433", 0.0066943799901433, WGS84_ES,
                ES_TOLERANCE, true);

        // cct 9.8.1: 50.000000000000056843  0.000000003725290298
        assertMoved(forward(esProbe("0.0066943799901433")), 0.000000003725290298);
        assertUnmoved(forward(esProbe("0.0066943799901418")));

        // The exact literal, which is the only value with a zero residual on both halves.
        assertHasCartPair(false, "+proj=longlat +a=6378137 +es=0.0066943799901413 "
                + "+towgs84=0,0,0");
    }

    /**
     * The predicate is a conjunction, so each half must be able to fail on its own. Holding one
     * side at the exact literal while the other crosses its bar is what proves that — a
     * predicate that had lost either {@code &amp;&amp;} operand would still pass every test above
     * except this one.
     */
    @Test
    public void bothHalvesOfTheConjunctionAreLoadBearing() {
        // a inside, es outside -> cart.
        assertHasCartPair(true, "+proj=longlat +a=6378137 +es=0.0066943799901433 "
                + "+towgs84=0,0,0");
        // a outside, es inside -> cart.
        assertHasCartPair(true, "+proj=longlat +a=6378137.00000002 "
                + "+es=0.0066943799901413 +towgs84=0,0,0");
        // both inside -> no cart. The only combination that drops it.
        assertHasCartPair(false, "+proj=longlat +a=6378137.000000005 "
                + "+es=0.0066943799901418 +towgs84=0,0,0");
        // both outside -> cart.
        assertHasCartPair(true, "+proj=longlat +a=6378137.00000002 "
                + "+es=0.0066943799901433 +towgs84=0,0,0");
    }

    /**
     * The failure mode this whole class exists to prevent, stated as an assertion.
     *
     * <p>{@code io.cpp:9436-9450} decides the same question by comparing the two {@code +ellps=}
     * <em>names</em>. Three definitions here would each be read differently by such a rule, and
     * all three must keep the pair:
     *
     * <ul>
     * <li>{@code +ellps=GRS80} — a name comparison against {@code WGS84} is what the CRS path
     *     uses to <em>delete</em> the pair;</li>
     * <li>the same ellipsoid written as {@code +a}/{@code +es}, with no {@code +ellps=} token to
     *     compare at all;</li>
     * <li>{@code +ellps=bessel}, whose name matches nothing and whose numbers are far outside
     *     both bars.</li>
     * </ul>
     */
    @Test
    public void thePredicateIsNumericAndNotAComparisonOfEllipsoidNames() {
        assertHasCartPair(true, "+proj=longlat +ellps=GRS80 +towgs84=0,0,0");
        assertHasCartPair(true, "+proj=longlat +a=6378137 +rf=298.257222101 "
                + "+towgs84=0,0,0");
        assertHasCartPair(true, "+proj=longlat +ellps=bessel +towgs84=0,0,0");

        // The nameless spelling of WGS84 must be dropped, for symmetry: the decision cannot be
        // reading the name in either direction.
        assertHasCartPair(false, "+proj=longlat +a=6378137 +rf=298.257223563 "
                + "+towgs84=0,0,0");
    }

    // ----------------------------------------------------------------------------- helpers

    private static String aProbe(String a) {
        return "+proj=longlat +a=" + a + " +es=0.0066943799901413 +towgs84=0,0,0";
    }

    private static String esProbe(String es) {
        return "+proj=longlat +a=6378137 +es=" + es + " +towgs84=0,0,0";
    }

    /**
     * Asserts one side of one tolerance, and asserts the arithmetic that places it there, so the
     * probe cannot drift onto the wrong side of the bar without the test saying so.
     *
     * @param token          the parameter under test, for the failure message
     * @param parsed         the double the token is intended to denote
     * @param reference      {@code create.cpp}'s constant for that half
     * @param tolerance      {@code create.cpp}'s bar for that half
     * @param expectCartPair whether the resulting operation must keep the round trip
     */
    private void assertToleranceProbe(String token, double parsed, double reference,
            double tolerance, boolean expectCartPair) {
        double residual = Math.abs(parsed - reference);
        if (expectCartPair) {
            assertTrue(token + " is meant to sit OUTSIDE " + tolerance + ", but its residual is "
                    + residual, residual > tolerance);
        } else {
            assertTrue(token + " is meant to sit INSIDE " + tolerance + ", but its residual is "
                    + residual, residual < tolerance);
        }
        String def = token.startsWith("+a=")
                ? aProbe(token.substring(3))
                : esProbe(token.substring(4));
        Cs2csOperator op = operatorOf(def);
        // The parser must have produced exactly the double the residual was computed from.
        double actual = token.startsWith("+a=")
                ? op.projection().getEllipsoid().getEquatorRadius()
                : op.projection().getEllipsoid().getEccentricitySquared();
        assertEquals("the parser did not reproduce the probe value for " + token, parsed, actual,
                0.0);
        assertHasCartPair(expectCartPair, op);
    }

    /**
     * The structural observable: whether {@link Cs2csOperator} built the pair at all, read off
     * the description it prints for exactly this purpose. The <em>behavioural</em> observable —
     * whether a point actually moves — is asserted separately by {@link #assertMoved} and
     * {@link #assertUnmoved} at every site where the two are not confounded by a Helmert, and
     * the two must agree; neither is used alone as the sole evidence for a probe.
     */
    private void assertHasCartPair(boolean expected, Cs2csOperator op) {
        assertHasCartPair(expected, op, op.toString());
    }

    private void assertHasCartPair(boolean expected, Cs2csOperator op, String definition) {
        boolean declared = op.toString().contains(", cart");
        assertEquals("create.cpp:135-140's predicate says do_cart=" + (expected ? 1 : 0)
                + " for `" + definition + "` (a=" + op.projection().getEllipsoid().getEquatorRadius()
                + ", es=" + op.projection().getEllipsoid().getEccentricitySquared()
                + "), so the cart/inv-cart round trip must be "
                + (expected ? "KEPT" : "DROPPED") + "; the operator says",
                expected, declared);
    }

    /** Builds the operator and asserts the verdict, naming the definition if it is wrong. */
    private void assertHasCartPair(boolean expected, String definition) {
        assertHasCartPair(expected, operatorOf(definition), definition);
    }

    /**
     * The behavioural half: the pair was built, so the point moved, and it moved to the number
     * {@code cct} 9.8.1 printed. 5e-19 is half the last place of {@code cct -d 18}.
     */
    private static void assertMoved(double[] out, double expectedHeight) {
        assertTrue("the cart pair was supposed to move this point, and it did not",
                out[2] != 0.0 || out[1] != 50.0 * DEG);
        assertEquals(expectedHeight, out[2], 5e-19);
    }

    /**
     * The other half, and the sharper of the two: when the predicate holds, no pair is built and
     * the operation is the <em>identity</em>. Asserted at zero tolerance, because "small" and
     * "not constructed" are different claims and only the second one is being made.
     */
    private static void assertUnmoved(double[] out) {
        assertEquals("latitude must come back bit-identical", 50.0 * DEG, out[1], 0.0);
        assertEquals("height must come back bit-identical", 0.0, out[2], 0.0);
    }

    private Cs2csOperator operatorOf(String definition) {
        Pipeline p = factory.create(definition);
        assertEquals(1, p.steps().size());
        PipelineOperator op = p.steps().get(0).operator();
        assertTrue("expected a Cs2csOperator for " + definition + ", got "
                + op.getClass().getName(), op instanceof Cs2csOperator);
        return (Cs2csOperator) op;
    }

    /** The {@code cct} probe point, 9 degrees east, 50 north, zero height. */
    private double[] forward(String definition) {
        Pipeline p = factory.create(definition);
        double[] out = p.forward(new double[] {9.0 * DEG, 50.0 * DEG, 0.0, 0.0});
        assertFalse("the longitude must be untouched by a pure change of ellipsoid",
                Math.abs(out[0] / DEG - 9.0) > 1e-12);
        return out;
    }
}
