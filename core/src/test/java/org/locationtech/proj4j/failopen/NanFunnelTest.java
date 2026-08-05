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
package org.locationtech.proj4j.failopen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

import org.junit.Test;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.Registry;
import org.locationtech.proj4j.proj.Projection;

/**
 * {@code NaN} in, {@code NaN} out, for <b>every projection at once</b>, because the rule lives at
 * the funnel and not in the kernels.
 *
 * <h2>Why this is a sweep and not a list</h2>
 *
 * <p>{@link LaeaNanPropagationTest} pins one kernel's polar arm. This pins the shared funnel that
 * makes the same latent arm unreachable in all of them: {@code Projection.projectRadians(double,
 * double, ProjCoordinate)} and {@code Projection.inverseProjectRadians} return early on a
 * {@code NaN} input, before {@code project}/{@code projectInverse} is called at all, exactly as
 * {@code 9.8.1:src/trans.cpp:352-354} short-circuits ahead of {@code pj_fwd4d}/{@code pj_inv4d}.
 * Upstream's {@code laea_e_forward} has the same {@code else out.x = out.y = 0.;} arm this file
 * exists to neutralise; it is dead code there for the same reason it is dead code here now.
 *
 * <p>The measured before/after over the whole registry, on
 * {@code +proj=<name> +ellps=GRS80 +x_0=1000000 +y_0=2000000 +units=m}:
 *
 * <table>
 * <caption>projections answering {@code (NaN, NaN)} with at least one finite ordinate</caption>
 * <tr><th>direction</th><th>before</th><th>after</th></tr>
 * <tr><td>forward</td><td>6 &mdash; {@code apian bacon ortel} returned
 *     {@code (1000000.0, NaN)}, i.e. the false easting itself; {@code eck4 putp6 putp6p} returned
 *     a finite northing</td><td>0</td></tr>
 * <tr><td>inverse</td><td>3 &mdash; {@code aea} and {@code leac} returned latitude
 *     {@code +pi/2} exactly, {@code collg} returned {@code (0.436, +pi/2)}, a fully plausible
 *     lon/lat pair</td><td>0</td></tr>
 * </table>
 *
 * <p>Those nine were <em>not</em> the nine anyone would have guessed, which is the argument for
 * sweeping: the fail-open arms are wherever a kernel happens to have written {@code else 0} or
 * clamped to a pole, and a comparison like {@code q >= 1e-15} or {@code |b| < EPS10} is false for
 * {@code NaN} in some kernels and takes the fabricating branch in others.
 *
 * <h2>The one exclusion, and why it is named rather than filtered</h2>
 *
 * <p>{@code +proj=geocent} overrides both funnels in {@code GeocentProjection} and still raises
 * ({@code NUMERICAL_FAILURE} forward, {@code INVALID_COORDINATE} inverse) on {@code NaN} input.
 * That is a residual site in a file outside this change's scope, so it is listed by name in
 * {@link #EXPECTED_RAISERS} rather than skipped silently, and
 * {@link #theExclusionListIsExactlyOneEntryLong()} fails if the set ever grows &mdash; a new
 * raiser cannot hide inside a filter.
 *
 * <h2>The positive control</h2>
 *
 * <p>A sweep that cannot fail proves nothing, and this one would report a clean result even if the
 * detector were broken. {@link #theDetectorSeesAKernelThatFabricatesTheOrigin()} feeds
 * {@code (NaN, NaN)} straight into a kernel written to be exactly the bug &mdash; upstream's
 * {@code else out.x = out.y = 0.;} &mdash; and asserts the detector flags it;
 * {@link #theFunnelMakesTheFabricatingKernelUnreachable()} then sends the same object the same
 * input through the public entry point and asserts {@code NaN}. Same class, same kernel, opposite
 * verdicts: the difference is the funnel, which is the claim being made.
 *
 * <p><b>And the whole file was run against a build with the early return neutered</b>, which is the
 * measurement that says these tests can fail. Three of the six went red and each named the right
 * thing:
 *
 * <ul>
 * <li>{@code theFunnelMakesTheFabricatingKernelUnreachable} &mdash;
 *     <i>"the funnel let the fabricating kernel run: ProjCoordinate[1000000.0 2000000.0 NaN]"</i>.
 *     The false easting and northing, exactly the {@code laea} shape.</li>
 * <li>{@code noInverseKernelAnswersNaNWithACoordinate} &mdash; named
 *     {@code +proj=collg -> (0.4363323129985824, 1.5707963267948966)} out of 131 swept.</li>
 * <li>{@code theExclusionListIsExactlyOneEntryLong} &mdash; 130 names instead of one.</li>
 * </ul>
 *
 * <p>The forward sweep, notably, stayed <b>green</b> under that control, and the reason is worth
 * recording: with the early return gone, {@code checkForwardDomain} and the finiteness
 * postcondition are reached with {@code NaN}, and the postcondition raises
 * {@code NUMERICAL_FAILURE} for almost every projection &mdash; so the offenders were converted
 * into raisers, and a sweep that skips raisers saw nothing. That is not a flaw in the control, it
 * is the reason {@link #theExclusionListIsExactlyOneEntryLong()} exists: a {@code catch} in the
 * sweep can only be trusted if what it catches is asserted as a set somewhere else.
 */
public class NanFunnelTest {

    /**
     * Projections that raise rather than answer on {@code NaN} input, by name, with the reason.
     * See the class javadoc: {@code GeocentProjection} overrides both funnels.
     */
    private static final SortedSet<String> EXPECTED_RAISERS =
            Collections.unmodifiableSortedSet(new TreeSet<String>(Arrays.asList("geocent")));

    // ------------------------------------------------------------------
    // The sweep
    // ------------------------------------------------------------------

    /**
     * Every constructible {@code +proj=} name, forward, with a false origin set so that the
     * false-easting shape is reachable.
     */
    @Test
    public void noForwardKernelAnswersNaNWithACoordinate() {
        sweep(true);
    }

    /** The same, inverse, with a central meridian so the {@code lon_0} path is exercised too. */
    @Test
    public void noInverseKernelAnswersNaNWithACoordinate() {
        sweep(false);
    }

    /**
     * The sweep must actually be sweeping. If the registry stops yielding roughly the number of
     * usable projections it has today, the two tests above go green by testing nothing at all.
     */
    @Test
    public void theSweepCoversTheWholeRegistry() {
        int registered = new Registry().getProjectionDescriptions().size();
        assertTrue("the registry lists only " + registered + " names; this sweep is not covering"
                + " what it claims to", registered >= 150);
        assertTrue("only " + constructibleNames().size() + " of " + registered + " names could be"
                + " constructed; the sweep would be nearly vacuous",
                constructibleNames().size() >= 125);
    }

    /**
     * Pins the exclusion set so a newly-introduced raiser is a failure rather than a filtered-out
     * line. Both directions are collected, because {@code geocent} raises a different cause in
     * each and either could change independently.
     */
    @Test
    public void theExclusionListIsExactlyOneEntryLong() {
        SortedSet<String> observed = new TreeSet<String>();
        observed.addAll(raisers(true));
        observed.addAll(raisers(false));
        assertEquals("the set of projections that RAISE on NaN input changed. NaN in must be NaN"
                + " out (9.8.1:src/trans.cpp:352-354); geocent is the one known residual, in a"
                + " file outside this change's scope", EXPECTED_RAISERS, observed);
    }

    // ------------------------------------------------------------------
    // The positive control: the detector, and then the funnel
    // ------------------------------------------------------------------

    /**
     * Proves the instrument can fail.
     *
     * <h4>What is being controlled for</h4>
     *
     * <p>The sweep asserts "no finite ordinate came back". A detector that never sees a finite
     * ordinate &mdash; because it looks at the wrong field, or because every kernel happens to be
     * correct &mdash; is indistinguishable from a working one. So here the fabricating kernel is
     * called <em>directly</em>, bypassing the funnel, and the assertion is that the check used by
     * the sweep says <b>fail</b>.
     */
    @Test
    public void theDetectorSeesAKernelThatFabricatesTheOrigin() {
        FabricatingProjection p = new FabricatingProjection();
        ProjCoordinate fwd = p.callForwardKernel(Double.NaN, Double.NaN);
        assertTrue("the control kernel did not fabricate, so it controls for nothing: " + fwd,
                fabricated(fwd));
        assertEquals("the control must reproduce upstream's `else out.x = out.y = 0.;` exactly",
                0L, Double.doubleToRawLongBits(fwd.x));

        ProjCoordinate inv = p.callInverseKernel(Double.NaN, Double.NaN);
        assertTrue("the control inverse kernel did not fabricate: " + inv, fabricated(inv));
    }

    /**
     * The other half of the control: the same object, the same kernel, the same input, through the
     * public entry point. {@code NaN}, because the funnel never asks the kernel.
     */
    @Test
    public void theFunnelMakesTheFabricatingKernelUnreachable() {
        FabricatingProjection p = new FabricatingProjection();
        p.setFalseEasting(1000000.0);
        p.setFalseNorthing(2000000.0);
        p.initialize();

        ProjCoordinate fwd = new ProjCoordinate();
        p.projectRadians(new ProjCoordinate(Double.NaN, Double.NaN), fwd);
        assertFalse("the funnel let the fabricating kernel run: " + fwd, fabricated(fwd));
        assertTrue("forward x must be NaN, was " + fwd.x, Double.isNaN(fwd.x));
        assertTrue("forward y must be NaN, was " + fwd.y, Double.isNaN(fwd.y));

        ProjCoordinate inv = new ProjCoordinate();
        p.inverseProjectRadians(new ProjCoordinate(Double.NaN, Double.NaN), inv);
        assertFalse("the funnel let the fabricating inverse kernel run: " + inv, fabricated(inv));
        assertTrue("inverse x must be NaN, was " + inv.x, Double.isNaN(inv.x));
        assertTrue("inverse y must be NaN, was " + inv.y, Double.isNaN(inv.y));

        // ... and the finite path through the very same kernel still works, so the early return is
        // confined to NaN and has not simply disabled the projection.
        ProjCoordinate finite = new ProjCoordinate();
        p.projectRadians(new ProjCoordinate(0.5, 0.5), finite);
        assertEquals("the finite path must still reach the kernel and the affine",
                1000000.0, finite.x, 0.0);
        assertEquals(2000000.0, finite.y, 0.0);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** A kernel written to be the defect: the origin, from undefined input. */
    private static final class FabricatingProjection extends Projection {

        private static final long serialVersionUID = 1L;

        @Override
        protected ProjCoordinate project(double x, double y, ProjCoordinate dst) {
            // 9.8.1:src/projections/laea.cpp -- `else out.x = out.y = 0.;`
            dst.x = 0.0;
            dst.y = 0.0;
            return dst;
        }

        @Override
        protected ProjCoordinate projectInverse(double x, double y, ProjCoordinate dst) {
            dst.x = 0.0;
            dst.y = 0.0;
            return dst;
        }

        @Override
        public boolean hasInverse() {
            return true;
        }

        ProjCoordinate callForwardKernel(double x, double y) {
            return project(x, y, new ProjCoordinate());
        }

        ProjCoordinate callInverseKernel(double x, double y) {
            return projectInverse(x, y, new ProjCoordinate());
        }
    }

    /** The detector the sweep uses: at least one ordinate came back finite. */
    private static boolean fabricated(ProjCoordinate c) {
        return isFinite(c.x) || isFinite(c.y);
    }

    private static boolean isFinite(double v) {
        // Not Double.isFinite: <release> is 8 and this must read the same on Java 8.
        return !Double.isNaN(v) && !Double.isInfinite(v);
    }

    private static List<String> constructibleNames() {
        List<String> out = new ArrayList<String>();
        for (String name : new Registry().getProjectionDescriptions().keySet()) {
            if (projection(name, true) != null) {
                out.add(name);
            }
        }
        return out;
    }

    private static Projection projection(String name, boolean forward) {
        String definition = "+proj=" + name + " +ellps=GRS80 +x_0=1000000 +y_0=2000000 +units=m"
                + (forward ? "" : " +lon_0=25");
        try {
            return new CRSFactory().createFromParameters("t", definition).getProjection();
        } catch (RuntimeException notUsable) {
            // Registered but not constructible with these parameters -- an unimplemented name, or
            // one that requires a parameter this string does not supply. Not this test's subject.
            return null;
        }
    }

    /**
     * The names that RAISE rather than answer on {@code (NaN, NaN)}. Collected separately from
     * {@link #sweep(boolean)} so that the exclusion is asserted as a set, not skipped in a catch.
     *
     * @param forward true for the forward direction
     * @return the raising names, sorted
     */
    private static SortedSet<String> raisers(boolean forward) {
        SortedSet<String> out = new TreeSet<String>();
        for (String name : new Registry().getProjectionDescriptions().keySet()) {
            Projection p = projection(name, forward);
            if (p == null) {
                continue;
            }
            try {
                if (forward) {
                    p.projectRadians(new ProjCoordinate(Double.NaN, Double.NaN),
                            new ProjCoordinate());
                } else {
                    p.inverseProjectRadians(new ProjCoordinate(Double.NaN, Double.NaN),
                            new ProjCoordinate());
                }
            } catch (RuntimeException raised) {
                out.add(name);
            }
        }
        return out;
    }

    private static void sweep(boolean forward) {
        StringBuilder offenders = new StringBuilder();
        int swept = 0;
        for (String name : new Registry().getProjectionDescriptions().keySet()) {
            Projection p = projection(name, forward);
            if (p == null) {
                continue;
            }
            swept++;
            ProjCoordinate out = new ProjCoordinate();
            try {
                if (forward) {
                    p.projectRadians(new ProjCoordinate(Double.NaN, Double.NaN), out);
                } else {
                    p.inverseProjectRadians(new ProjCoordinate(Double.NaN, Double.NaN), out);
                }
            } catch (RuntimeException raised) {
                continue; // covered, by name, by theExclusionListIsExactlyOneEntryLong
            }
            if (fabricated(out)) {
                offenders.append("\n  +proj=").append(name).append(" -> (")
                        .append(out.x).append(", ").append(out.y).append(')');
            } else if (!Double.isNaN(out.x) || !Double.isNaN(out.y)) {
                offenders.append("\n  +proj=").append(name).append(" -> (")
                        .append(out.x).append(", ").append(out.y)
                        .append(") -- non-finite but not NaN; an infinity is not a propagated NaN");
            }
        }
        assertTrue("swept only " + swept + " projections", swept >= 125);
        if (offenders.length() != 0) {
            throw new AssertionError((forward ? "forward" : "inverse") + ": NaN input must come"
                    + " back as NaN in BOTH ordinates, because 9.8.1:src/trans.cpp:352-354 never"
                    + " invokes the operation on a coordinate carrying a NaN. These answered with"
                    + " something else, over " + swept + " swept:" + offenders);
        }
    }
}
