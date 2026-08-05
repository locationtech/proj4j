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

package org.locationtech.proj4j.tmerc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;
import org.locationtech.proj4j.tmerc.TmercGieRunner.Outcome;

/**
 * The whole of {@code builtins.gie}'s transverse-Mercator corpus — every {@code tmerc},
 * {@code etmerc} and {@code utm} row, at the file's own tolerances, read from the file.
 *
 * <p>This is the acceptance test for switching {@code +proj=tmerc} from the Evenden/Snyder series
 * to Poder/Engsager. The decisive rows are the {@code +proj=tmerc +ellps=GRS80} block at
 * {@code builtins.gie:7095}: it asserts <b>{@code tolerance 50 nm}</b> against numbers identical
 * to the {@code +proj=etmerc} block's, including {@code accept 44.69 35.37} — 3,900 km from the
 * central meridian, where the approximate series is out by about 1.5 km, i.e. 3&times;10<sup>13</sup>
 * times the bar.
 */
public class TmercGieCorpusTest {

    /**
     * A parse regression must not be able to pass as a clean run, so the row inventory is pinned.
     * If upstream adds a row, this fails first and with a clear message.
     */
    @Test
    public void corpusInventoryIsWhatWeThinkItIs() {
        List<TmercGieCorpus.Row> rows = TmercGieCorpus.transverseMercatorRows();

        int tmerc = 0;
        int etmerc = 0;
        int utm = 0;
        int roundtrips = 0;
        int failures = 0;
        for (TmercGieCorpus.Row r : rows) {
            if ("tmerc".equals(r.projection())) {
                tmerc++;
            } else if ("etmerc".equals(r.projection())) {
                etmerc++;
            } else if ("utm".equals(r.projection())) {
                utm++;
            } else {
                fail("unexpected projection in a selected row: " + r);
            }
            if (r.roundtrip > 0) {
                roundtrips++;
            }
            if (r.kind == TmercGieCorpus.FAILURE) {
                failures++;
            }
        }

        assertEquals("tmerc rows in builtins.gie", 84, tmerc);
        assertEquals("etmerc rows in builtins.gie", 12, etmerc);
        assertEquals("utm rows in builtins.gie", 12, utm);
        assertEquals("rows carrying a roundtrip", 61, roundtrips);
        assertEquals("expect-failure rows", 3, failures);
        assertEquals("transverse-Mercator rows in builtins.gie", 108, rows.size());
    }

    /** {@code +proj=tmerc} — 8 blocks, ellipsoidal and spherical, {@code +approx} and {@code +algo}. */
    @Test
    public void tmerc() {
        assertFamilyPasses("tmerc");
    }

    /** {@code +proj=etmerc} — {@code builtins.gie:1929-1959}, all at {@code tolerance 50 nm}. */
    @Test
    public void etmerc() {
        assertFamilyPasses("etmerc");
    }

    /** {@code +proj=utm} — {@code builtins.gie:7742-7787}. */
    @Test
    public void utm() {
        assertFamilyPasses("utm");
    }

    /**
     * The two rows this stage cannot reach, keyed by their {@code operation} line — <b>not</b> by
     * file line, because three distinct rows share line 7772: gie does not clear the pending
     * {@code accept} on a new {@code operation}, so the two {@code expect failure} blocks that
     * follow inherit that one. Each entry carries the specific plumbing it is waiting on. <b>A skip is not a pass:</b> they are tallied
     * separately, printed, and if one starts passing the test <em>fails</em> so the entry is
     * removed rather than quietly protecting a fixed row.
     */
    private static final Map<String, String> KNOWN_GAPS = new LinkedHashMap<String, String>();

    static {
        // builtins.gie:7767 (+proj=utm +zone=32, no +ellps, tolerance 0.001 mm) was a gap here
        // until the default ellipsoid was corrected: PROJ 9.8.1 appends ellps=GRS80 when no size
        // or shape parameter is given (9.8.1:src/init.cpp:362) and proj4j used to default to
        // WGS84, which cost 124 um of northing at lat 56. It now passes, so it is deliberately
        // NOT listed -- the UNEXPECTED PASS branch below is what told us to remove it.
        //
        // builtins.gie:7772 (+proj=utm +zone=32 +approx) was the second gap, for a different
        // reason: Registry bound utm to ExtendedTransverseMercatorProjection, which is
        // Poder/Engsager only, so the +approx escape hatch had nowhere to go. Both halves have
        // now landed -- Registry binds utm to TransverseMercatorProjection, and Proj4Parser
        // dispatches +approx and +algo to it -- so it too is deliberately NOT listed, and again
        // it was the UNEXPECTED PASS branch that said so.
        //
        // All 12 utm rows pass. This map is intentionally left empty rather than deleted: it is
        // the mechanism that makes a future gap explicit instead of silent.
    }

    private static void assertFamilyPasses(String projection) {
        List<TmercGieCorpus.Row> rows = new ArrayList<TmercGieCorpus.Row>();
        for (TmercGieCorpus.Row r : TmercGieCorpus.transverseMercatorRows()) {
            if (projection.equals(r.projection())) {
                rows.add(r);
            }
        }
        assertTrue("no rows selected for " + projection, !rows.isEmpty());

        List<Outcome> outcomes = TmercGieRunner.runAll(rows);
        int passed = 0;
        int skipped = 0;
        StringBuilder report = new StringBuilder();
        for (Outcome o : outcomes) {
            boolean known = KNOWN_GAPS.containsKey(o.row.operation);
            if (o.passed && known) {
                report.append("\n  UNEXPECTED PASS ").append(o.row)
                        .append("\n       remove the KNOWN_GAPS entry for ")
                        .append(o.row.operation);
            } else if (o.passed) {
                passed++;
            } else if (known) {
                skipped++;
                System.out.println("GIE-SKIP builtins.gie:" + o.row.line + " " + o.row.operation
                        + "\n         " + KNOWN_GAPS.get(o.row.operation)
                        + "\n         observed: " + o.detail);
            } else {
                report.append("\n  FAIL ").append(o.row).append("\n       ").append(o.detail);
            }
        }
        // Printed unconditionally so a before/after comparison can be read straight off the
        // surefire output, and so the skips are never invisible.
        System.out.println("GIE-TALLY builtins.gie " + projection + " pass=" + passed + " skip="
                + skipped + " fail=" + (outcomes.size() - passed - skipped) + " total="
                + outcomes.size());
        if (report.length() > 0) {
            fail(projection + ": " + outcomes.size() + " builtins.gie rows, " + passed
                    + " passed, " + skipped + " known gaps" + report);
        }
    }
}
