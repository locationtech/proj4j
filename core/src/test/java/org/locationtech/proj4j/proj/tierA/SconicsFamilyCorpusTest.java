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

package org.locationtech.proj4j.proj.tierA;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.InvalidValueException;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.proj.Projection;

/**
 * All seven members of {@code 9.8.1:src/projections/sconics.cpp}, against the corpus.
 *
 * <p><b>Why a whole family is here when only {@code tissot} was in scope.</b>
 * {@code tissot} was listed as free — "the class already exists and is complete, un-comment
 * the registration and verify" — and the verify step failed all sixteen of its rows by 2,336
 * km. The cause was not in {@code TissotProjection} but in {@code SimpleConicProjection},
 * which every member inherits: it ignored {@code +lat_1} and {@code +lat_2} entirely, using
 * hard-coded 30&deg; and 60&deg; behind a {@code FIXME}. All fourteen {@code sconics}
 * operations in {@code builtins.gie} use {@code +lat_1=0.5 +lat_2=2}, so the family's 112
 * assertions were failing on one defect.
 *
 * <p>Six of the seven — {@code euler}, {@code murd1}, {@code murd2}, {@code murd3},
 * {@code pconic}, {@code vitk1} — were <b>already registered and live</b>, returning
 * plausible coordinates for the wrong standard parallels. That is a silent wrong answer, the
 * failure mode this project's third non-negotiable exists to eliminate, and it is worth more
 * than the assertion count: a caller had no way to detect it.
 *
 * <p>Two further defects in the same file's inverse, both from the same mishandling of C's
 * mutate-the-parameter idiom, are covered by {@link #everyMemberRoundTripsThroughItsInverse}:
 * {@code atan2} read the raw northing instead of {@code rho_0 - y}, and the {@code n < 0}
 * branch negated variables that were overwritten two statements later.
 */
public class SconicsFamilyCorpusTest {

    /** Each member has 2 operations of 8 rows: 4 forward and 4 inverse. */
    private static final int ROWS_PER_MEMBER = 16;

    @Test
    public void euler() {
        GieCheck.assertAllRows("builtins.gie", "euler", ROWS_PER_MEMBER);
    }

    @Test
    public void murd1() {
        GieCheck.assertAllRows("builtins.gie", "murd1", ROWS_PER_MEMBER);
    }

    @Test
    public void murd2() {
        GieCheck.assertAllRows("builtins.gie", "murd2", ROWS_PER_MEMBER);
    }

    @Test
    public void murd3() {
        GieCheck.assertAllRows("builtins.gie", "murd3", ROWS_PER_MEMBER);
    }

    @Test
    public void pconic() {
        GieCheck.assertAllRows("builtins.gie", "pconic", ROWS_PER_MEMBER);
    }

    @Test
    public void vitk1() {
        GieCheck.assertAllRows("builtins.gie", "vitk1", ROWS_PER_MEMBER);
    }

    @Test
    public void tissot() {
        GieCheck.assertAllRows("builtins.gie", "tissot", ROWS_PER_MEMBER);
    }

    /**
     * The regression test for the hard-coding itself, independent of the corpus: two
     * definitions differing only in {@code +lat_1}/{@code +lat_2} must not project a point to
     * the same place.
     *
     * <p>This is the assertion that would have caught the defect. Every corpus row used the
     * same parallels, so no single row could distinguish "reads the parameters" from "ignores
     * them and happens to be configured correctly" — only a comparison between two different
     * parameterisations can.
     */
    @Test
    public void standardParallelsChangeTheResult() {
        for (String name : new String[] {"euler", "murd1", "murd2", "murd3", "pconic",
                "tissot", "vitk1"}) {
            ProjCoordinate a = project("+proj=" + name + " +a=6400000 +lat_1=0.5 +lat_2=2");
            ProjCoordinate b = project("+proj=" + name + " +a=6400000 +lat_1=30 +lat_2=60");
            double moved = Math.hypot(a.x - b.x, a.y - b.y);
            assertTrue(name + ": +lat_1/+lat_2 must change the projected coordinate, but"
                    + " (0.5, 2) and (30, 60) both gave (" + a.x + ", " + a.y + ")."
                    + " SimpleConicProjection is ignoring the parameters again.",
                    moved > 1000.0);
        }
    }

    /**
     * {@code sconics.cpp:44-51} rejects a definition with no {@code +lat_1}/{@code +lat_2}.
     * Proj4J cannot tell "absent" from "zero", but it does not need to: with both at their
     * {@code 0.0} default, {@code del} and {@code sig} are zero and the
     * {@code |del| < EPS || |sig| < EPS} test rejects them — which is upstream's error
     * condition reached by upstream's own guard.
     */
    @Test
    public void missingStandardParallelsIsRejectedRatherThanGuessed() {
        for (String name : new String[] {"euler", "murd1", "murd2", "murd3", "pconic",
                "tissot", "vitk1"}) {
            try {
                project("+proj=" + name + " +a=6400000");
                fail(name + ": a definition with no +lat_1/+lat_2 must be rejected, not"
                        + " silently given default parallels");
            } catch (InvalidValueException expected) {
                assertTrue(name + ": the message should name the parameters, was: "
                        + expected.getMessage(),
                        expected.getMessage().contains("lat_1"));
            }
        }
    }

    /**
     * Forward then inverse must return the input. This is what fails if {@code atan2} is fed
     * the raw northing rather than {@code rho_0 - y}: the easting survives but the longitude
     * comes back displaced by an amount of order {@code rho_0}, which is metres-scale, not
     * rounding-scale.
     */
    @Test
    public void everyMemberRoundTripsThroughItsInverse() {
        for (String name : new String[] {"euler", "murd1", "murd2", "murd3", "pconic",
                "tissot", "vitk1"}) {
            String def = "+proj=" + name + " +a=6400000 +lat_1=0.5 +lat_2=2";
            Projection p = new CRSFactory().createFromParameters("t", def).getProjection();
            ProjCoordinate xy = new ProjCoordinate();
            p.project(new ProjCoordinate(2.0, 1.0), xy);
            ProjCoordinate back = new ProjCoordinate();
            p.inverseProject(xy, back);
            assertEquals(name + " round trip longitude", 2.0, back.x, 1e-9);
            assertEquals(name + " round trip latitude", 1.0, back.y, 1e-9);
        }
    }

    private static ProjCoordinate project(String def) {
        Projection p = new CRSFactory().createFromParameters("t", def).getProjection();
        ProjCoordinate out = new ProjCoordinate();
        p.project(new ProjCoordinate(2.0, 1.0), out);
        return out;
    }
}
