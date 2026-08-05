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

package org.locationtech.proj4j.proj.tierB;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.Registry;
import org.locationtech.proj4j.proj.AlaskaModifiedStereographicProjection;
import org.locationtech.proj4j.proj.ModifiedStereographic50Projection;
import org.locationtech.proj4j.proj.Projection;

/**
 * The five {@code mod_ster.cpp} operators against {@code builtins.gie}: {@code gs50} 16,
 * {@code alsk} 16, {@code gs48} 8, {@code mil_os} 8, {@code lee_os} 8 — <b>56 assertions</b>, the
 * highest Tier B payoff.
 *
 * <p>All five parse straight through {@code Proj4Parser}: none of them reads a parameter the
 * parser does not already dispatch.
 */
public class ModifiedStereographicCorpusTest {

    @Test
    public void gs50MatchesCorpus() {
        TierBCorpus.assertAll("builtins.gie", "gs50", 16);
    }

    @Test
    public void alskMatchesCorpus() {
        TierBCorpus.assertAll("builtins.gie", "alsk", 16);
    }

    @Test
    public void gs48MatchesCorpus() {
        TierBCorpus.assertAll("builtins.gie", "gs48", 8);
    }

    @Test
    public void milOsMatchesCorpus() {
        TierBCorpus.assertAll("builtins.gie", "mil_os", 8);
    }

    @Test
    public void leeOsMatchesCorpus() {
        TierBCorpus.assertAll("builtins.gie", "lee_os", 8);
    }

    /**
     * {@code alsk} used to be registered to the abstract {@link Projection} base class, so the name
     * resolved and then could not be instantiated. It must now produce a real projection.
     */
    @Test
    public void alskIsNoLongerRegisteredToTheAbstractBase() {
        Projection alsk = new Registry().getProjection("alsk");
        assertNotNull("+proj=alsk", alsk);
        assertTrue(alsk.getClass().getName(),
                alsk instanceof AlaskaModifiedStereographicProjection);
        assertTrue("alsk must declare an inverse; mod_ster_setup assigns P->inv unconditionally",
                alsk.hasInverse());
    }

    /** All five names resolve. */
    @Test
    public void allFiveNamesAreRegistered() {
        Registry registry = new Registry();
        for (String name : new String[] {"alsk", "gs48", "gs50", "lee_os", "mil_os"}) {
            assertNotNull("+proj=" + name, registry.getProjection(name));
        }
    }

    /**
     * {@code alsk} and {@code gs50} replace the requested ellipsoid with
     * {@code a = 6378206.4, es = 0.00676866} — <b>upstream's literal, not clrk66's own
     * {@code es}</b>.
     *
     * <p>This is the constant-fidelity rule with a measurable consequence: clrk66's own
     * {@code es} is {@code 0.00676865799729...}, and re-deriving it instead of using the literal
     * moves the {@code gs50 +ellps=clrk66} forward by about 0.7 mm — seven times the corpus's
     * 0.1 mm bar. So the assertion is on the stomped value, not on agreement with the ellipsoid.
     */
    @Test
    public void gs50AndAlskStompTheEllipsoidWithUpstreamsLiteral() {
        Projection gs50 = TierBCorpus.build("+proj=gs50 +ellps=clrk66");
        Projection alsk = TierBCorpus.build("+proj=alsk +ellps=clrk66");
        for (Projection p : new Projection[] {gs50, alsk}) {
            assertEquals(p.getName() + " stomped semi-major axis", 6378206.4,
                    p.getEquatorRadius(), 0.0);
        }
        // The ellipsoid object is untouched -- getEllipsoid() still reports what +ellps= asked for
        // -- and its own es is NOT the value the kernel runs on. Asserting the inequality means a
        // future "tidy-up" that derives es from the ellipsoid fails here, loudly, instead of 0.7 mm
        // downstream in eight corpus rows.
        double clrk66Es = gs50.getEllipsoid().getEccentricitySquared();
        assertTrue("clrk66's own es is " + clrk66Es + ", which must not be the stomped 0.00676866 "
                + "-- if these ever coincide this assertion has stopped testing anything",
                clrk66Es != 0.00676866);
    }

    /**
     * The {@code (0, 0)} early return of {@code mod_ster_e_inverse} lands exactly on the
     * projection centre ({@code mod_ster.cpp:82-89}), not at longitude zero.
     *
     * <p>Upstream returns {@code lam = 0} there specifically so that {@code inv_finalize} adds
     * {@code lam0} and the answer is the centre. Getting this wrong gives Greenwich for every
     * variant, which is a plausible coordinate and therefore the dangerous kind of wrong.
     */
    @Test
    public void originInvertsToTheProjectionCentre() {
        String[][] cases = {
                {"+proj=gs48 +R=6370997", "-96", "39"},
                {"+proj=gs50 +R=6370997", "-120", "45"},
                {"+proj=alsk +R=6370997", "-152", "64"},
                {"+proj=lee_os +R=6400000", "-165", "-10"},
                {"+proj=mil_os +R=6400000", "20", "18"},
        };
        for (String[] c : cases) {
            Projection p = TierBCorpus.build(c[0]);
            ProjCoordinate out = new ProjCoordinate();
            p.inverseProject(new ProjCoordinate(0, 0), out);
            assertEquals(c[0] + " inverse of (0,0) longitude", Double.parseDouble(c[1]), out.x,
                    1e-9);
            assertEquals(c[0] + " inverse of (0,0) latitude", Double.parseDouble(c[2]), out.y,
                    1e-9);
        }
    }
}
