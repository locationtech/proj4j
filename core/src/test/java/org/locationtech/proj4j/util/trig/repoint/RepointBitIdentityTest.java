/*******************************************************************************
 * Copyright 2026
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
 *******************************************************************************/

package org.locationtech.proj4j.util.trig.repoint;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * The end-to-end half of the proof that re-pointing 50 {@code StrictMath.sin/cos/tan} call sites
 * to {@link org.locationtech.proj4j.util.FastStrictTrig} moved <strong>no bit of output</strong>.
 *
 * <h2>Why a digest, and why these numbers can be trusted</h2>
 *
 * <p>Each expected digest below is a SHA-256 over the concatenated {@code doubleToRawLongBits} of
 * every {@code double} one code path produced, and <strong>every one was captured by running
 * {@link RepointDump} against the tree as it stood before the re-point</strong> — a frozen copy at
 * {@code 7362c85}, compiled separately, with the ten changed files being the only difference
 * between the two builds. So this is a genuine before/after comparison that survives the change,
 * not a formula that agrees with the implementation by construction. Non-negotiable 4 of the
 * upgrade notes, and the rule against re-pinning a reference from proj4j's own current output,
 * both point at exactly this distinction.
 *
 * <h2>Why raw bits, and why a digest is enough</h2>
 *
 * <p>{@code ==} is not the test: it calls {@code -0.0} equal to {@code 0.0} and every {@code NaN}
 * unequal to itself, and a tolerance would defeat the point — the entire justification for
 * treating this change as risk-free is that nothing can cross a {@code gie} bar in either
 * direction, which is only true if no bit moved. A single flipped bit anywhere in the
 * 1,654,464 values changes the digest.
 *
 * <p>The count after the {@code /} is part of the assertion. Without it, a path that started
 * throwing on the first point would produce a short, stable, wrong digest; with it, the failure
 * message says how many values were compared.
 *
 * <h2>What is covered</h2>
 *
 * <table>
 *   <caption>call sites reached by each pinned path</caption>
 *   <tr><th>path</th><th>sites</th></tr>
 *   <tr><td>{@code clenshaw6}</td><td>{@code Clenshaw6:170} {@code convert(zeta)},
 *       {@code :190-191} {@code convertSinCos} — the highest-traffic trig in the library,
 *       reached from {@code utm tmerc lcc cass poly aea laea}</td></tr>
 *   <tr><td>{@code conformalLat}</td><td>{@code ConformalLat:203, 240, 241, 259}</td></tr>
 *   <tr><td>{@code authalicLat}</td><td>{@code AuthalicLat:196, 224, 227, 228} — the last three
 *       only via the non-series branch, so two high-{@code n} ellipsoids are included
 *       deliberately</td></tr>
 *   <tr><td>the eight projection specs</td><td>all 31 sites in {@code Spilhaus},
 *       {@code AdamsWorldInASquareI/II}, {@code AdamsHemisphere}, {@code Guyou},
 *       {@code PeirceQuincuncial} and {@code Adams} ({@code ellipticTail})</td></tr>
 *   <tr><td>{@code lsatForward}</td><td>no re-pointed site; pinned to show that adding
 *       {@code LandsatProjection.projectInverse} disturbed the forward not at all</td></tr>
 * </table>
 *
 * <h2>The one re-pin, and exactly what it costs</h2>
 *
 * <p>Five of the twelve digests were re-pinned once, when {@code Projection}'s inverse stopped
 * <em>dividing</em> the projected ordinate by {@code totalScale} and started <em>multiplying</em> it
 * by the reciprocal, which is what {@code 9.8.1:src/inv.cpp:85-93} does
 * ({@code coo.xyz.x *= P->ra;}, with a comment upstream explaining the choice). The two are not the
 * same number:
 *
 * <pre>
 * -20037508.342789244 / 6378137           = -pi - 1 ulp   -&gt;  atan2 gives  +pi
 * -20037508.342789244 * (1.0 / 6378137)   = exactly -pi    -&gt;  atan2 gives  -pi
 * </pre>
 *
 * <p>The five are {@code spilhaus} (both specs), {@code adams_ws2} and {@code peirce_q} (both
 * shapes). Seven paths &mdash; {@code clenshaw6}, {@code conformalLat}, {@code authalicLat},
 * {@code adams_ws1}, {@code adams_hemi}, {@code guyou}, {@code lsatForward} &mdash; are still the
 * original {@code 7362c85} values, and the total value count is still 1,654,464, so no path stopped
 * producing values.
 *
 * <h3>What the movement was checked against, before it was pinned</h3>
 *
 * <p>Re-pinning a guard from our own output is how it stops meaning anything, so the new values were
 * verified against PROJ 9.8.1 first &mdash; the installed {@code proj} and {@code cs2cs}, both
 * reporting <i>Rel. 9.8.1, April 10th, 2026</i> &mdash; and not against a previous Proj4J build.
 *
 * <p><b>The decisive case is qualitative and unambiguous.</b> {@code cs2cs EPSG:3857 EPSG:4055} at
 * {@code -20037508.342789244 0} gives longitude {@code -180}. Proj4J gave {@code +180.0} before and
 * gives {@code -180.0} after, bit-exactly. Separately, {@code proj -I -f "%.17g" +proj=merc
 * +a=6378137 +b=6378137} on the same easting prints exactly {@code -180}, which is <em>itself</em>
 * evidence that upstream multiplies: the divide is {@code -pi - 1 ulp}, which in degrees is
 * {@code -180.00000000000003} and would have printed as such.
 *
 * <p><b>The bulk movement was checked for an accuracy regression, not just for a sign.</b> For each
 * of the five specs, every graticule point whose inverse bits moved was re-run through
 * {@code proj -I -f "%.17g"} on the same proj-string, restricted to the well-conditioned subset
 * ({@code |input latitude| <= 84}, since near the poles these inverses amplify a 1-ulp input change
 * by seven orders of magnitude in either direction, and a win/loss count there measures conditioning
 * rather than accuracy). Both sides of the comparison are geographic, so the metric is angular
 * &mdash; degrees of the geographic output, where {@code 1e-13} deg is about {@code 1.1e-8} m:
 *
 * <table>
 * <caption>agreement with {@code proj} 9.8.1, well-conditioned points, degrees</caption>
 * <tr><th>spec</th><th>moved</th><th>after closer</th><th>before closer</th>
 *     <th>max |&Delta;| after</th><th>max |&Delta;| before</th></tr>
 * <tr><td>{@code spilhaus +ellps=WGS84}</td><td>3,417</td><td>1,746</td><td>1,608</td>
 *     <td>1.680e-9</td><td>1.397e-9</td></tr>
 * <tr><td>{@code spilhaus +R=6371000}</td><td>425</td><td>229</td><td>186</td>
 *     <td>2.753e-11</td><td>2.758e-11</td></tr>
 * <tr><td>{@code adams_ws2}</td><td>4,410</td><td>2,242</td><td>2,062</td>
 *     <td>1.555e-9</td><td>3.807e-9</td></tr>
 * <tr><td>{@code peirce_q} square</td><td>3,957</td><td>2,109</td><td>1,719</td>
 *     <td>5.526e-9</td><td>5.526e-9</td></tr>
 * <tr><td>{@code peirce_q} diamond</td><td>3,874</td><td>1,994</td><td>1,748</td>
 *     <td>6.277e-10</td><td>6.286e-10</td></tr>
 * <tr><td><b>total</b></td><td></td><td><b>8,320</b></td><td><b>7,323</b></td>
 *     <td colspan="2">ties: 440</td></tr>
 * </table>
 *
 * <p>So: the after value is closer to {@code proj} 9.8.1 more often than the before value in
 * <em>every one</em> of the five specs; the largest deviation is unchanged or improved in four of
 * five ({@code adams_ws2} improves 2.4&times;) and grows by 0.28 nanodegrees &mdash; about 0.03 mm
 * &mdash; in {@code spilhaus +ellps=WGS84}; and the whole movement is bounded by
 * {@code 3.8e-9} deg, i.e. it is an ulp-level reshuffle. Nothing crosses a {@code gie} bar, which
 * the corpus confirms independently: <b>0 fail&rarr;pass and 0 pass&rarr;fail</b> across all 7,851
 * measured assertions.
 *
 * @see RepointedSiteDomainTest for the per-site half of the proof
 */
public class RepointBitIdentityTest {

    /**
     * The pinned digests: seven still at {@code 7362c85}, five re-pinned once, for a reason recorded
     * below. Do not regenerate these from the current tree wholesale &mdash; that would make them
     * agree with whatever the code does today, which is the one thing this file must not do.
     */
    private static final Map<String, String> BEFORE = new LinkedHashMap<String, String>();

    /**
     * The keys whose digest is no longer the {@code 7362c85} value, so that
     * {@link #sevenOfTheTwelvePathsAreStillTheOriginalPreRepointDigests()} can say how much of the
     * original claim survives.
     */
    private static final int REPINNED = 5;

    static {
        // --- still the original pre-re-point digests, captured at 7362c85 -------------------
        BEFORE.put("clenshaw6",
                "bc3602656e97948a98cb906e058404e87e892f6fb50e96ca95d61f2113b9c0ab/1036944");
        BEFORE.put("conformalLat",
                "867fe1d432e19ce5284b3c1c808cb409ebc3d14338ffe383de69e1eedd5eba14/270015");
        BEFORE.put("authalicLat",
                "4a6b9b6a8bb6fe32104744829ab11c4b263dbadc52fc1d021e04b955c598fa8b/144012");
        BEFORE.put("+proj=adams_ws1 +ellps=WGS84",
                "870f7595f543ce5fcaa2ed5967e08a30aef89f4cdc9c62bb5b0af298f79b1caf/22143");
        BEFORE.put("+proj=adams_hemi +ellps=WGS84",
                "6a220a3953f140084c5b6f2cbe926c27fc90efa91e5daab1451a4575464c8b8c/14701");
        BEFORE.put("+proj=guyou +ellps=WGS84",
                "6c9c71ef8754432d21110223cc798a4edabd70d788005d14368489475a613887/14701");
        BEFORE.put("lsatForward",
                "096426eaa8f948e6bbee241bb96d6b2c7102c0114b280103f053ca53148d1181/4818");

        // --- re-pinned once, for the `*= ra` change. See "The one re-pin" in the class javadoc ---
        BEFORE.put("+proj=spilhaus +ellps=WGS84",
                "a7a24db7afce579c08ac7d3d1fa7b1f3c8637be74823dfada1390da79f1c385f/29524");
        BEFORE.put("+proj=spilhaus +R=6371000",
                "1cd19930c620e2a8baced1674337dac13475a484b0b98215eef04aec9e4b7146/29524");
        BEFORE.put("+proj=adams_ws2 +ellps=WGS84",
                "6a1ed7251c2e285e171ede6cb8062c91d6b50ce625d9bd73320bb880fe0a5efa/29478");
        BEFORE.put("+proj=peirce_q +ellps=WGS84 +lat_0=90 +shape=square",
                "4f9282f7bb96bd938b362cd4666575409ef43cffb305fcffbb16f781bb341ec3/29502");
        BEFORE.put("+proj=peirce_q +ellps=WGS84 +lat_0=90 +shape=diamond",
                "02bc333404bcf8230c03ac99c56bfb04067d15a938a906b60450d629d6f3faf5/29102");
    }

    /**
     * The {@code 7362c85} digests of the five re-pinned paths, kept so the old and the new values
     * are both on the record and the re-pin is auditable rather than merely asserted.
     */
    private static final Map<String, String> SUPERSEDED = new LinkedHashMap<String, String>();

    static {
        SUPERSEDED.put("+proj=spilhaus +ellps=WGS84",
                "81c17f244ecbd40f885612bd03161c8c48e6f9e77a927de248e50fda1736d127/29524");
        SUPERSEDED.put("+proj=spilhaus +R=6371000",
                "3f83ecaa09ba2db57ce274e47f1fadef45d7a2d807899c36186f4602612bf6ca/29524");
        SUPERSEDED.put("+proj=adams_ws2 +ellps=WGS84",
                "de87c02694de88d39ef49bfefb741a4cc4a76cea68bdce93a716cc731af98fad/29478");
        SUPERSEDED.put("+proj=peirce_q +ellps=WGS84 +lat_0=90 +shape=square",
                "550cff4c8c3b7350d8e01dfbb044e5cdaa1143acbc9b89769e1594a224625b2f/29502");
        SUPERSEDED.put("+proj=peirce_q +ellps=WGS84 +lat_0=90 +shape=diamond",
                "02a2df36b000e8f783a5ab66bdba05185d8d3803aa3a897547d8c5fd177855fb/29102");
    }

    /** The total the twelve counts add up to, restated so a silent drop is visible. */
    private static final int TOTAL_VALUES = 1654464;

    @Test
    public void everyRepointedPathIsBitwiseUnchanged() {
        Map<String, String> now = RepointDump.digests();
        assertEquals("the set of pinned paths changed", BEFORE.keySet(), now.keySet());
        StringBuilder moved = new StringBuilder();
        for (Map.Entry<String, String> e : BEFORE.entrySet()) {
            String actual = now.get(e.getKey());
            if (!e.getValue().equals(actual)) {
                moved.append("\n  ").append(e.getKey())
                        .append("\n    before (7362c85): ").append(e.getValue())
                        .append("\n    now             : ").append(actual);
            }
        }
        if (moved.length() != 0) {
            throw new AssertionError("these pinned paths moved. Seven of the twelve digests are the "
                    + "pre-re-point values from 7362c85; the five spilhaus/adams_ws2/peirce_q "
                    + "digests were re-pinned once for Projection's inverse `*= 1/totalScale` "
                    + "change (9.8.1:src/inv.cpp:85-93), after verifying the new values against the "
                    + "installed proj/cs2cs 9.8.1. Anything moving now is unaccounted for -- do NOT "
                    + "re-pin from this build's own output without the same verification:" + moved);
        }
    }

    /**
     * Says out loud how much of the original pre-re-point claim is still standing, so that a second
     * re-pin cannot quietly erode the file until every digest agrees with the current build by
     * construction. If this number drops, the guard has stopped being a before/after comparison and
     * that has to be a deliberate, visible decision.
     */
    @Test
    public void sevenOfTheTwelvePathsAreStillTheOriginalPreRepointDigests() {
        assertEquals("the number of re-pinned paths changed", REPINNED, SUPERSEDED.size());
        assertEquals("the pinned set changed size", 12, BEFORE.size());
        for (Map.Entry<String, String> e : SUPERSEDED.entrySet()) {
            String current = BEFORE.get(e.getKey());
            if (e.getValue().equals(current)) {
                throw new AssertionError("SUPERSEDED and BEFORE agree for " + e.getKey()
                        + ", so the superseded value is not superseded and this record is a lie");
            }
        }
        for (String key : SUPERSEDED.keySet()) {
            if (!BEFORE.containsKey(key)) {
                throw new AssertionError("superseded key " + key + " is no longer pinned at all");
            }
        }
    }

    /**
     * The superseded digests must not match the current build either &mdash; if one did, the re-pin
     * was unnecessary and the record above is wrong about what moved. A cheap consistency check on
     * the audit trail itself.
     */
    @Test
    public void theSupersededDigestsNoLongerDescribeThisBuild() {
        Map<String, String> now = RepointDump.digests();
        for (Map.Entry<String, String> e : SUPERSEDED.entrySet()) {
            assertFalse("the 7362c85 digest for " + e.getKey() + " still matches this build, so it"
                    + " never moved and should not have been re-pinned",
                    e.getValue().equals(now.get(e.getKey())));
        }
    }

    @Test
    public void theDigestsCoverTheNumberOfValuesClaimed() {
        int total = 0;
        for (String d : RepointDump.digests().values()) {
            total += Integer.parseInt(d.substring(d.indexOf('/') + 1));
        }
        assertEquals("a path stopped producing values, which would make its digest stable "
                + "and meaningless", TOTAL_VALUES, total);
    }
}
