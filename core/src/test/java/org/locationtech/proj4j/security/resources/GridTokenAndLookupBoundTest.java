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
 *******************************************************************************/
package org.locationtech.proj4j.security.resources;

import java.io.IOException;
import java.util.List;

import org.junit.After;
import org.junit.Test;
import org.locationtech.proj4j.InvalidValueException;
import org.locationtech.proj4j.datum.Grid;
import org.locationtech.proj4j.datum.GridCache;
import org.locationtech.proj4j.datum.VerticalGrid;
import org.locationtech.proj4j.resource.ResourceResolvers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@code +nadgrids=} and {@code +geoidgrids=} are lists of untrusted names, and every element of one
 * is a full pass down the resolver chain.
 *
 * <h2>What was unbounded</h2>
 *
 * <p>{@code fromNadGrids} split on {@code ','} and looped, with no cap on how many tokens it would
 * loop over. A CRS string is per-row user input in this library's threat model, so N tokens bought N
 * chain traversals per CRS construction — an arbitrary multiplier chosen by the caller.
 *
 * <h2>Where the number 32 comes from</h2>
 *
 * <p>Measured, not guessed. Over PROJ 9.8.1 at the tag ({@code git grep} across {@code src/},
 * {@code data/} and {@code test/}) the distribution of comma counts in
 * {@code (nadgrids|geoidgrids|grids|xy_grids|z_grids)=} is 519&times;1, 18&times;2, 1&times;3,
 * 5&times;4, 1&times;6 — <b>maximum 6</b>. Over this repository's sources it is 355&times;1,
 * 22&times;2, 1&times;3, 6&times;4, 1&times;5, 4&times;6 — the same maximum 6, the 6 being
 * {@code g2012a_conus.gtx} through {@code g2012a_samoa.gtx} and the longest {@code +nadgrids=} being
 * the 4-token NAD27 list. The cap is therefore above five times anything real, which is why
 * {@link #theLongestRealListsAreWellUnderTheCap()} asserts both of those lists still work.
 */
public class GridTokenAndLookupBoundTest {

    @After
    public void reset() {
        ResourceResolvers.clearResolvers();
        GridCache.instance().clear();
        GridCache.vertical().clear();
    }

    private static String tokens(int n, String prefix) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(',');
            }
            // @-optional, so every one of them is silently skipped when absent and the ONLY thing
            // that can fail the call is the cap itself.
            sb.append('@').append(prefix).append(i);
        }
        return sb.toString();
    }

    @Test
    public void aListLongerThanTheCapIsRefused() {
        try {
            Grid.fromNadGrids(tokens(Grid.MAX_GRID_TOKENS + 1, "absent"));
            fail("a " + (Grid.MAX_GRID_TOKENS + 1) + "-token +nadgrids= must be refused");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("+nadgrids="));
            assertTrue("the message must state the limit: " + expected.getMessage(),
                    expected.getMessage().contains(String.valueOf(Grid.MAX_GRID_TOKENS)));
        }
    }

    /**
     * The boundary, from below. Without this the cap could be off by one in the safe direction and
     * nothing would notice, or it could be refusing everything and the test above would still pass.
     */
    @Test
    public void aListExactlyAtTheCapIsAccepted() throws IOException {
        List<Grid> grids = Grid.fromNadGrids(tokens(Grid.MAX_GRID_TOKENS, "absent"));
        assertNotNull(grids);
        assertEquals("every token is @-optional and absent, so the list is empty but the call "
                + "must not have thrown", 0, grids.size());
    }

    @Test
    public void theVerticalListHasTheSameCap() throws IOException {
        assertEquals("a +geoidgrids= list at the cap must be accepted", 0,
                VerticalGrid.fromGeoidGrids(tokens(Grid.MAX_GRID_TOKENS, "@absentv")).size());
        try {
            VerticalGrid.fromGeoidGrids(tokens(Grid.MAX_GRID_TOKENS + 1, "absentv"));
            fail("a +geoidgrids= list over the cap must be refused");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("+geoidgrids="));
        }
    }

    /** The introspection entry point takes the same limit; it just cannot throw {@code IOException}. */
    @Test
    public void describeNadGridsHasTheSameCap() {
        assertEquals(Grid.MAX_GRID_TOKENS,
                Grid.describeNadGrids(tokens(Grid.MAX_GRID_TOKENS, "absent")).size());
        try {
            Grid.describeNadGrids(tokens(Grid.MAX_GRID_TOKENS + 1, "absent"));
            fail("describeNadGrids must refuse the same list fromNadGrids refuses");
        } catch (InvalidValueException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains(String.valueOf(Grid.MAX_GRID_TOKENS)));
        }
    }

    /**
     * The accept side, on real data: the two longest lists that exist anywhere in PROJ 9.8.1 or in
     * this repository. If the cap were ever tightened to where it bites, this is what fails.
     */
    @Test
    public void theLongestRealListsAreWellUnderTheCap() throws IOException {
        // The NAD27 list, 4 tokens, the longest +nadgrids= upstream ships. conus and ntv1_can.dat
        // are present in the test classpath; alaska and ntv2_0.gsb are not, and are @-optional.
        List<Grid> nad27 = Grid.fromNadGrids("@conus,@alaska,@ntv2_0.gsb,@ntv1_can.dat");
        assertTrue("the NAD27 list must still resolve at least conus", nad27.size() >= 1);
        assertEquals("conus", nad27.get(0).getGridName());

        // The 6-token geoid list, the longest of either kind anywhere. All six are absent here, so
        // what this pins is that six tokens are not refused for being six.
        assertEquals(0, VerticalGrid.fromGeoidGrids("@g2012a_conus.gtx,@g2012a_alaska.gtx,"
                + "@g2012a_guam.gtx,@g2012a_hawaii.gtx,@g2012a_puertorico.gtx,@g2012a_samoa.gtx")
                .size());

        // And a real vertical grid that IS present, so the vertical path is proven to still load.
        assertNotNull(VerticalGrid.fromGeoidGrids("egm96_15_downsampled.gtx").get(0));
    }

    /**
     * A flood of distinct absent names must not accumulate without bound in the resolution memo.
     *
     * <p>{@code CachingResourceResolver} memoised every lookup, positive and negative, in one
     * unbounded map. The negative half is the attacker-sized one — the names come from
     * {@code +nadgrids=} tokens — so a per-row job feeding fresh names retained one map entry and
     * one string per name for the life of the JVM.
     *
     * <p>This asserts on the observable proxy: after a flood far larger than the cap, a legitimate
     * grid still resolves and still gives the same answer. The bound itself is asserted directly in
     * {@code org.locationtech.proj4j.resource.NegativeLookupCacheTest}, which can see the counters.
     */
    @Test
    public void aFloodOfAbsentNamesLeavesLegitimateResolutionIntact() throws IOException {
        List<Grid> before = Grid.fromNadGrids("conus");
        assertEquals(1, before.size());

        for (int i = 0; i < 5000; i++) {
            List<Grid> skipped = Grid.fromNadGrids("@flood-" + i + "-not-a-grid");
            assertEquals(0, skipped.size());
        }

        GridCache.instance().clear();
        List<Grid> after = Grid.fromNadGrids("conus");
        assertEquals(1, after.size());
        assertEquals("conus", after.get(0).getGridName());
        assertEquals("ctable2", after.get(0).getFormat());
        assertEquals("the flood must not have changed what conus is", before.get(0).sizeBytes(),
                after.get(0).sizeBytes());
    }
}
