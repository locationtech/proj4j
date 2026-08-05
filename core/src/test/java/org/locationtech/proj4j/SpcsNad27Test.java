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
package org.locationtech.proj4j;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;
import org.locationtech.proj4j.io.MetaCRSTestCase;
import org.locationtech.proj4j.io.MetaCRSTestFileReader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * Runs {@code PROJ4_SPCS_nad27.csv}, the repository's only NAD27 State Plane coverage. It had been
 * checked in and then never referenced by any test, in any module, for its entire history.
 * <p>
 * <b>A correction to the audit that scheduled this work.</b> The file was recorded as "265 rows of
 * NAD27 SPCS coverage existing nowhere else". It has 265 data rows, but <b>248 of them target the
 * placeholder code {@code EPSG:0}</b> and throw {@link UnknownAuthorityCodeException} — they are rows
 * the PROJ.4 {@code test27} suite had no EPSG code for when the file was written, since the
 * non-Alaska NAD27 zones' expected values are in US survey feet while the corresponding
 * {@code EPSG:267xx} definitions are metric. The real coverage is <b>17 rows over the ten Alaska
 * zones</b> ({@code EPSG:26731}-{@code 26740}), and all 17 pass at the file's own 0.1 ft tolerance.
 * <p>
 * That 17 is still worth having: it is the only NAD27 State Plane assertion in the repository, and
 * {@code EPSG:26731} (Alaska zone 1) is one of only three shipped {@code omerc} definitions and the
 * only NAD27 one — it passes because it carries {@code +no_uoff} with {@code gamma == alpha}, i.e. it
 * avoids both {@code omerc} defects documented in {@code Proj4VariousTest.testRSOBorneo()}.
 * <p>
 * The unresolvable count is asserted exactly rather than skipped silently: if a future registry change
 * makes one of those 248 rows resolvable, the assertion fails and the row gets looked at, instead of
 * a real regression hiding inside a growing "skipped" bucket.
 */
public class SpcsNad27Test {

    /**
     * Rows whose target is the placeholder {@code EPSG:0}. Measured, not assumed: 265 data rows,
     * 17 resolvable, 248 not.
     */
    private static final int EXPECTED_UNRESOLVABLE_ROWS = 248;

    /** Rows that name a real EPSG code. All of them are expected to pass. */
    private static final int EXPECTED_RESOLVABLE_ROWS = 17;

    private static final CRSFactory csFactory = new CRSFactory();

    @Test
    public void testPROJ4_SPCS_NAD27() throws IOException {
        File file = getFile("PROJ4_SPCS_nad27.csv");
        List<MetaCRSTestCase> tests = new MetaCRSTestFileReader(file).readTests();

        List<String> failed = new ArrayList<String>();
        List<String> unexpectedlyUnresolvable = new ArrayList<String>();
        int resolvable = 0;
        int unresolvable = 0;

        for (MetaCRSTestCase test : tests) {
            try {
                boolean passed = test.execute(csFactory);
                resolvable++;
                if (!passed) {
                    failed.add(describe(test));
                }
            } catch (UnknownAuthorityCodeException ex) {
                // The 248 placeholder rows. Counted, never treated as a pass.
                unresolvable++;
                if (!"0".equals(targetCode(test))) {
                    unexpectedlyUnresolvable.add(describe(test) + " -- " + ex.getMessage());
                }
            } catch (Proj4jException ex) {
                resolvable++;
                failed.add(describe(test) + " threw " + ex.getClass().getSimpleName()
                        + ": " + ex.getMessage());
            }
        }

        StringBuilder msg = new StringBuilder();
        if (!failed.isEmpty()) {
            msg.append(failed.size()).append(" of ").append(resolvable)
               .append(" resolvable NAD27 SPCS rows failed:\n");
            for (String f : failed) {
                msg.append("  ").append(f).append("\n");
            }
        }
        if (!unexpectedlyUnresolvable.isEmpty()) {
            msg.append(unexpectedlyUnresolvable.size())
               .append(" rows failed to resolve for a reason other than the EPSG:0 placeholder:\n");
            for (String u : unexpectedlyUnresolvable) {
                msg.append("  ").append(u).append("\n");
            }
        }
        if (msg.length() > 0) {
            fail(msg.toString());
        }

        assertEquals("resolvable NAD27 SPCS rows", EXPECTED_RESOLVABLE_ROWS, resolvable);
        assertEquals("rows targeting the placeholder code EPSG:0 -- if this number has dropped, a "
                + "registry change made one of them resolvable and it should be re-pinned rather "
                + "than left in the skip bucket", EXPECTED_UNRESOLVABLE_ROWS, unresolvable);
    }

    private static String targetCode(MetaCRSTestCase test) {
        String name = test.getTargetCrsName();
        int colon = name.indexOf(':');
        return colon < 0 ? name : name.substring(colon + 1);
    }

    private static String describe(MetaCRSTestCase test) {
        return test.getName().trim() + "  " + test.getSourceCrsName() + " -> "
                + test.getTargetCrsName() + "  expected " + test.getTargetCoordinate()
                + " got " + test.getResultCoordinate();
    }

    private static File getFile(String name) throws IOException {
        try {
            return new File(SpcsNad27Test.class.getResource("../../../" + name).toURI());
        } catch (URISyntaxException e) {
            throw new IOException("cannot locate test resource " + name, e);
        }
    }
}
