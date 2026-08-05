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
 *******************************************************************************/
package org.locationtech.proj4j.proj;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * Projects a grid of geographic coordinates into each of ~200 CRSs and back, asserting that the
 * round-trip returns to where it started. By point count this is the broadest test in the repository.
 * <p>
 * Migrated from JUnit 3 ({@code extends TestCase}) to JUnit 4, and the probe-box logic it depends on
 * had four defects fixed — see {@link ProjectionGridRoundTripper#gridExtent(Projection)}. The most
 * consequential was that the latitude accumulator was seeded with {@link Double#MIN_VALUE}
 * ({@code +4.9e-324}, not a negative number), so <b>every all-negative-latitude CRS silently fell back
 * to a 10&deg; box on the equator</b> and was never probed anywhere near its own area of use.
 * <p>
 * The original code list — EPSG:3005, 2759-2930, 2265 — is entirely North American and therefore
 * entirely northern-hemisphere, so it never exercised that defect at all. {@link #testSouthernHemisphere()}
 * is added for exactly that reason: without it, the fix would be untested and the box logic could
 * regress to the equator without a single assertion noticing.
 * <p>
 * A failure here is reported with every missed point and its miss distance, rather than aborting on
 * the first one: "1 of 25 points missed by 3e-5 degrees" and "25 of 25 points missed by 40 degrees"
 * are completely different findings and the old {@code assertTrue(isOK)} could not distinguish them.
 *
 * @author Martin Davis
 */
public class ProjectionGridTest {

    /**
     * Round-trip tolerance, in degrees. ~1.1 m at the equator.
     */
    static final double TOLERANCE = 0.00001;

    private final CRSFactory csFactory = new CRSFactory();

    @Test
    public void testAlbers() {
        runEPSG(3005, 3005, 1);
    }

    @Test
    public void testStatePlane() {
        // State-plane EPSG defs. 172 of the 172 codes in the range resolve.
        runEPSG(2759, 2930, 172);
    }

    @Test
    public void testStatePlaneND() {
        runEPSG(2265, 2265, 1);
    }

    /**
     * Southern-hemisphere CRSs, which the {@link Double#MIN_VALUE} seed described in the class javadoc
     * used to push onto the equator. Added with the fix, because without it the fix has no witness.
     * <p>
     * Deliberately a mix of {@code utm +south} (lat_0 = 0, so it also exercises the
     * "is 0.0 absent or real?" question), {@code lcc} with two negative standard parallels,
     * {@code tmerc} with a negative {@code lat_0}, and a polar {@code stere}. Measured effect of the
     * fix on where these are probed:
     * <pre>
     *   EPSG:3112  lcc   lat_1 -18, lat_2 -36   old box [129,-5 : 139,5]     new [124,-37 : 144,-17]
     *   EPSG:22185 tmerc lat_0 -90              old box [-65,-5 : -55,5]     new [-70,-89 : -50,-79]
     *   EPSG:3031  stere lat_0 -90              old box [-5,-5 : 5,5]        new [-10,-89 : 10,-79]
     * </pre>
     * The old boxes for those three sat on the equator — 27&deg;, 85&deg; and 90&deg; of latitude away
     * from the CRS's own origin, EPSG:3031's landing in the Gulf of Guinea. The {@code utm +south}
     * entries are unchanged and that is correct: their {@code lat_0} really is 0.
     * <p>
     * All 12 pass. The fix did not turn up a new round-trip failure, but it did change the worst
     * observed error by eight orders of magnitude where it moved the box: EPSG:22185 probed at its real
     * origin reaches 1.0e-6 deg (~0.11 m at lat &minus;89, near-polar {@code tmerc}) against
     * 5e-15 deg for the equatorial UTM zones.
     */
    @Test
    public void testSouthernHemisphere() {
        int[] codes = {
                2736,   // UTM 36S / clrk66 -- lat_0 = 0, +south
                32718,  // UTM 18S / WGS84
                32733,  // UTM 33S / WGS84
                32755,  // UTM 55S / WGS84
                22185,  // Campo Inchauspe / Argentina zone 5, tmerc, lat_0 = -90
                29181,  // SAD69 / UTM 21S
                20355,  // AGD66 / AMG zone 55
                2193,   // NZGD2000 / New Zealand Transverse Mercator, lat_0 = 0
                3112,   // GDA94 / Geoscience Australia Lambert, lat_1 = -18, lat_2 = -36
                5361,   // SIRGAS-Chile / Chile zone
                3031,   // WGS84 / Antarctic Polar Stereographic, lat_0 = -90
                2039,   // northern control: Israel 1993 / Israeli TM Grid
        };
        runAll(codes, codes.length);
    }

    private void runEPSG(int codeStart, int codeEnd, int expectedResolved) {
        int[] codes = new int[codeEnd - codeStart + 1];
        for (int i = 0; i < codes.length; i++) {
            codes[i] = codeStart + i;
        }
        runAll(codes, expectedResolved);
    }

    /**
     * Runs every code and reports all of them together. Collecting rather than failing fast is the
     * point: with 172 CRSs in one method, "the build is red" has to come with a list.
     * <p>
     * {@code expectedResolved} is asserted exactly. Not every integer in an EPSG range is an assigned
     * code, so unresolvable ones have to be skipped — but a skip is not a pass, and without pinning the
     * count a registry change could quietly reduce this from 172 CRSs to 2 while still reporting
     * green.
     */
    private void runAll(int[] codes, int expectedResolved) {
        List<String> broken = new ArrayList<String>();
        int probed = 0;
        int points = 0;
        for (int code : codes) {
            String name = "epsg:" + code;
            CoordinateReferenceSystem cs;
            try {
                cs = csFactory.createFromName(name);
            } catch (RuntimeException ex) {
                // Not every integer in a range is an assigned code; an unassigned one is not a defect.
                continue;
            }
            if (cs == null) {
                continue;
            }
            probed++;

            ProjectionGridRoundTripper tripper = new ProjectionGridRoundTripper(cs);
            boolean isOK;
            try {
                isOK = tripper.runGrid(TOLERANCE);
            } catch (RuntimeException ex) {
                broken.add(name + " (" + cs.getParameterString() + ") threw "
                        + ex.getClass().getSimpleName() + ": " + ex.getMessage());
                continue;
            }
            points += tripper.getTransformCount();

            if (!isOK) {
                double[] e = tripper.getExtent();
                StringBuilder sb = new StringBuilder();
                sb.append(name).append(" (").append(cs.getParameterString()).append(")\n")
                  .append("      probe box [").append(e[0]).append(", ").append(e[1])
                  .append(" : ").append(e[2]).append(", ").append(e[3]).append("]")
                  .append("  worst error ").append(tripper.getWorstError()).append(" deg")
                  .append("  (").append(tripper.getFailures().size()).append(" of ")
                  .append(tripper.getTransformCount()).append(" points missed)");
                for (String f : tripper.getFailures()) {
                    sb.append("\n        ").append(f);
                }
                broken.add(sb.toString());
            }
        }

        assertEquals("number of CRSs that resolved -- a skip is not a pass, so this is pinned "
                + "rather than left to drift", expectedResolved, probed);

        if (!broken.isEmpty()) {
            StringBuilder msg = new StringBuilder();
            msg.append(broken.size()).append(" of ").append(probed)
               .append(" CRSs failed to round-trip within ").append(TOLERANCE)
               .append(" degrees (").append(points).append(" points probed):\n");
            for (String b : broken) {
                msg.append("  ").append(b).append("\n");
            }
            fail(msg.toString());
        }
    }
}
