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
package org.locationtech.proj4j.benchmark;

import java.util.concurrent.TimeUnit;

import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.util.AuthalicLat;
import org.locationtech.proj4j.util.ConformalLat;
import org.locationtech.proj4j.util.EllipticIntegral;
import org.locationtech.proj4j.util.GenericInverse2D;
import org.locationtech.proj4j.util.MathHelpers;
import org.locationtech.proj4j.util.MeridianArc;
import org.locationtech.proj4j.util.ProjectionMath;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Head-to-head for every row of {@code reference/numerics.md}'s ranked-changes table that has both
 * an old and a new implementation in the tree.
 *
 * <p>The pairing is the point. Each {@code legacy*} method calls the {@code @Deprecated} routine in
 * {@code ProjectionMath}; each {@code karney*} method calls the {@code AuxLat}/{@code Clenshaw6}
 * replacement. <b>Both arms run in the same JMH fork</b>, which is exactly the Tier 2 discipline:
 * an absolute ns/op on a shared runner is worth little, but the <i>ratio</i> of two methods measured
 * in one fork cancels machine speed, turbo state and noisy neighbours.
 *
 * <p>The legacy routines are deprecated, not deleted, precisely so this comparison can exist. When
 * the last caller of one of them is gone, keep the {@code legacy*} arm here and add a comment - the
 * ratio is the evidence for a claim in the skill, and deleting the baseline arm makes the claim
 * unverifiable.
 *
 * <p>Parameterised over latitude because several of these are ill-conditioned in a specific regime:
 * {@code tsfn} loses relative precision near the pole, {@code phi2}'s {@code pow}-bearing loop trip
 * count grows with eccentricity times latitude, and the authalic inverse is worst near 90 degrees.
 * A single mid-latitude sample would report the easy case for all of them.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Fork(value = 3, jvmArgsAppend = {"-XX:+UseSerialGC"})
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
public class SolverBenchmark {

    /** Latitude in degrees. 89.9 is the near-pole regime where several of these degrade. */
    @Param({"0.0", "45.0", "70.0", "89.9"})
    public double latDeg;

    /** WGS84 / GRS80 eccentricity squared. */
    private static final double ES = 0.00669437999014133;

    private double phi;
    private double sinPhi;
    private double cosPhi;
    private double e;
    private double oneEs;

    // --- legacy state -----------------------------------------------------------------------
    private double[] en;
    private double[] apa;
    private double legacyArcLength;
    private double legacyTs;
    private double legacyBeta;

    // --- Karney-core state ------------------------------------------------------------------
    private MeridianArc meridianArc;
    private AuthalicLat authalicLat;
    private double karneyArcLength;
    private double karneyTs;
    private double karneyBeta;

    private ProjCoordinate solverOut;
    private GenericInverse2D.Forward2D sinusoidalForward;
    private double solverTargetX;
    private double solverTargetY;

    @Setup(Level.Trial)
    public void setUp() {
        phi = Math.toRadians(latDeg);
        sinPhi = Math.sin(phi);
        cosPhi = Math.cos(phi);
        e = Math.sqrt(ES);
        oneEs = 1.0 - ES;

        en = ProjectionMath.enfn(ES);
        apa = ProjectionMath.authset(ES);
        legacyArcLength = ProjectionMath.mlfn(phi, sinPhi, cosPhi, en);
        legacyTs = ProjectionMath.tsfn(phi, sinPhi, e);
        legacyBeta = Math.asin(ProjectionMath.qsfn(sinPhi, e, oneEs) / ProjectionMath.qsfn(1.0, e, oneEs));

        meridianArc = MeridianArc.fromEs(ES);
        authalicLat = new AuthalicLat(ES);
        karneyArcLength = meridianArc.mlfn(phi, sinPhi, cosPhi);
        karneyTs = ConformalLat.tsfn(phi, sinPhi, e);
        karneyBeta = authalicLat.forward(phi, sinPhi, cosPhi);

        // A sinusoidal forward map is the cheapest non-trivial 2D map that GenericInverse2D can
        // actually invert, so the measurement is dominated by the solver's own iteration and
        // finite-difference machinery rather than by the map.
        sinusoidalForward = (lam, p, dst) -> dst.setValue(lam * Math.cos(p), p);
        solverOut = new ProjCoordinate();
        solverTargetX = Math.toRadians(10.0) * Math.cos(phi);
        solverTargetY = phi;
    }

    // ============================================================================================
    // numerics.md row 2: MeridianArc replaces enfn/mlfn/inv_mlfn.
    // Forward: series vs series, expect near parity. Inverse: closed form vs a 10-step Newton loop
    // that calls sin, cos, mlfn and sqrt per trip, so expect the largest ratio in this class.
    // ============================================================================================

    @Benchmark
    public double legacyMlfn() {
        return ProjectionMath.mlfn(phi, sinPhi, cosPhi, en);
    }

    @Benchmark
    public double karneyMlfn() {
        return meridianArc.mlfn(phi, sinPhi, cosPhi);
    }

    @Benchmark
    public double legacyInvMlfn() {
        return ProjectionMath.inv_mlfn(legacyArcLength, ES, en);
    }

    @Benchmark
    public double karneyInvMlfn() {
        return meridianArc.invMlfn(karneyArcLength);
    }

    // ============================================================================================
    // numerics.md row 3: ConformalLat.phi2 replaces ProjectionMath.phi2.
    // Legacy is a pow-bearing fixed-point loop; the replacement is at most two sqrt/sinh steps.
    // ============================================================================================

    @Benchmark
    public double legacyPhi2() {
        return ProjectionMath.phi2(legacyTs, e);
    }

    @Benchmark
    public double karneyPhi2() {
        return ConformalLat.phi2(karneyTs, e);
    }

    // ============================================================================================
    // numerics.md row 5: tsfn rewrite. tan + pow becomes exp + log1p.
    // ============================================================================================

    @Benchmark
    public double legacyTsfn() {
        return ProjectionMath.tsfn(phi, sinPhi, e);
    }

    @Benchmark
    public double karneyTsfn() {
        return ConformalLat.tsfn(phi, sinPhi, e);
    }

    /** The sine/cosine-preserving form, which avoids recomputing what the caller already has. */
    @Benchmark
    public double karneyTsfnSinCos() {
        return ConformalLat.tsfnSinCos(sinPhi, cosPhi, e);
    }

    // ============================================================================================
    // numerics.md row 4: AuthalicLat replaces authset/authlat. The largest visible accuracy delta
    // in the whole table (up to 1.6 mm on laea/aea/cea/eqearth/nzmg), so the speed result matters
    // less here than knowing it is not a regression.
    // ============================================================================================

    @Benchmark
    public double legacyQsfn() {
        return ProjectionMath.qsfn(sinPhi, e, oneEs);
    }

    @Benchmark
    public double karneyAuthalicForward() {
        return authalicLat.forward(phi, sinPhi, cosPhi);
    }

    @Benchmark
    public double legacyAuthlat() {
        return ProjectionMath.authlat(legacyBeta, apa);
    }

    @Benchmark
    public double karneyAuthalicInverse() {
        return authalicLat.inverse(karneyBeta);
    }

    // ============================================================================================
    // numerics.md row 14: purge Math.hypot. Kept here as well as in MathDispatchBenchmark because
    // MathHelpers.norm2 is the specific replacement core calls, and its cost relative to the
    // solvers above is what decides whether row 14 is worth doing at a given site.
    // ============================================================================================

    @Benchmark
    public double mathHypot() {
        return Math.hypot(sinPhi, cosPhi);
    }

    @Benchmark
    public double mathHelpersNorm2() {
        return MathHelpers.norm2(sinPhi, cosPhi);
    }

    @Benchmark
    public double mathHelpersAsinh() {
        return MathHelpers.asinh(sinPhi);
    }

    @Benchmark
    public double mathHelpersAtanh() {
        // atanh's domain is (-1, 1); sinPhi reaches 1.0 exactly only at 90 deg, which is not sampled.
        return MathHelpers.atanh(sinPhi * 0.9);
    }

    // ============================================================================================
    // Single-arm subjects: no legacy counterpart exists, so these establish a first baseline.
    // ============================================================================================

    /**
     * {@code adams.cpp}'s {@code ell_int_5} Chebyshev series. Ported verbatim, including its 0.42 m
     * error, because the ~3,400 gie values for the Adams family were generated by it - see the
     * skill's non-negotiable 7. Measured so that a future "improvement" shows up as a cost as well
     * as a conformance break.
     */
    @Benchmark
    public double ellipticIntegralEllInt5() {
        return EllipticIntegral.ellInt5(phi);
    }

    /**
     * {@code generic_inverse.cpp}'s Newton solver with finite-difference Jacobian refresh. Reached
     * by the whole Adams family, {@code wink2} and {@code cass}. Bounded at 15 iterations, so its
     * worst case is bounded - but it is roughly two orders of magnitude above a closed-form inverse
     * and that ratio is what justifies never putting it on a hot path.
     */
    @Benchmark
    public ProjCoordinate genericInverse2D() {
        return GenericInverse2D.solve(solverTargetX, solverTargetY, sinusoidalForward,
                0.0, 0.0, 1e-10, solverOut);
    }
}
