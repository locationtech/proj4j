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
package org.locationtech.proj4j.parser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.InvalidValueException;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.Registry;
import org.locationtech.proj4j.proj.PeirceQuincuncialProjection;
import org.locationtech.proj4j.proj.Projection;
import org.locationtech.proj4j.proj.SpilhausProjection;
import org.locationtech.proj4j.util.ProjectionMath;

/**
 * The five operator-scoped parameters of the {@code adams.cpp}/{@code spilhaus.cpp} family, as
 * seen from the parser: {@code +shape}, {@code +scrollx}, {@code +scrolly} on {@code peirce_q},
 * and {@code +azi}, {@code +rot} on {@code spilhaus}.
 *
 * <h2>Why this file exists</h2>
 *
 * <p>{@code Proj4Parser} used to have no dispatch for any of them. In
 * {@link Proj4Parser.ParseMode#PROJ_COMPATIBLE} an unrecognised key is retained and ignored —
 * which is what {@code init.cpp} does and is correct — so {@code +proj=peirce_q +shape=square}
 * parsed cleanly and then projected as a <b>diamond</b>. A silent wrong answer for every one of
 * {@code peirce_q.gie}'s 29 blocks. {@link #squareAndDiamondProduceDifferentOutput()} is the
 * regression net for exactly that defect: it does not inspect the shape, it measures the
 * coordinates, so a dispatch that is deleted or short-circuited cannot pass it.
 *
 * <h2>The dispatch is per-projection, deliberately</h2>
 *
 * <p>None of the five is universal, so they are routed on the concrete class in the same way
 * {@code +zone} already was, and are ignored on any other projection — which is also what PROJ
 * does, since {@code pj_param} is pull-based and only {@code peirce_q} and {@code spilhaus} ask.
 *
 * <h2>The scroll asymmetry is upstream's, and is not to be tidied up</h2>
 *
 * <p>{@code pj_adams_setup} reads {@code +scrollx} <b>only inside the {@code horizontal}
 * branch</b> and {@code +scrolly} only inside the {@code vertical} branch
 * ({@code adams.cpp:420-447}). Two consequences, both asserted below, both of which look like
 * bugs and will otherwise be "fixed" by someone:
 *
 * <ul>
 * <li>{@code +shape=vertical +scrollx=0.5} <b>ignores</b> {@code scrollx}. Not an error, not
 *     applied.
 * <li>the {@code [-1, 1]} range check lives inside the same branch, so
 *     {@code +shape=vertical +scrollx=5} is <b>not an error either</b>, while
 *     {@code +shape=horizontal +scrollx=5} is.
 * </ul>
 */
public class OperatorScopedParameterTest {

    private final CRSFactory factory = new CRSFactory();

    private Projection projection(String definition) {
        return factory.createFromParameters("test", definition).getProjection();
    }

    private PeirceQuincuncialProjection peirceQ(String definition) {
        return (PeirceQuincuncialProjection) projection(definition);
    }

    private SpilhausProjection spilhaus(String definition) {
        return (SpilhausProjection) projection(definition);
    }

    // -------------------------------------------------------------------------------- +shape

    /**
     * <b>The regression net for the silent-default defect.</b> Measured, not introspected: if
     * {@code +shape} stops reaching the projection, both definitions become the default diamond
     * and the two outputs become identical.
     *
     * <p>{@code square} is {@code diamond} rotated 45 degrees ({@code adams.cpp:270-274}), so the
     * two disagree everywhere except where the fold puts a point on the rotation centre. The
     * expected magnitudes here come from the rotation itself, not from a transcription: for the
     * diamond image {@code (x, y)} the square image is
     * {@code (RSQRT2*(x - y), RSQRT2*(x + y))}.
     */
    @Test
    public void squareAndDiamondProduceDifferentOutput() {
        Projection square = projection("+proj=peirce_q +R=6370997 +shape=square");
        Projection diamond = projection("+proj=peirce_q +R=6370997 +shape=diamond");

        ProjCoordinate s = new ProjCoordinate();
        ProjCoordinate d = new ProjCoordinate();
        square.project(new ProjCoordinate(20, 40), s);
        diamond.project(new ProjCoordinate(20, 40), d);

        double separation = Math.hypot(s.x - d.x, s.y - d.y);
        assertTrue("+shape=square and +shape=diamond must not produce the same coordinate; "
                        + "square=" + s + " diamond=" + d,
                separation > 1000.0);

        // And the difference is the 45-degree rotation, not noise.
        double rsqrt2 = Math.sqrt(0.5);
        assertEquals(rsqrt2 * (d.x - d.y), s.x, 1e-6);
        assertEquals(rsqrt2 * (d.x + d.y), s.y, 1e-6);
    }

    /** All six {@code +shape} names arrive, and each yields a distinct arrangement. */
    @Test
    public void allSixShapesArriveAndAreDistinct() {
        for (PeirceQuincuncialProjection.Shape shape : PeirceQuincuncialProjection.Shape.values()) {
            assertEquals(shape, peirceQ("+proj=peirce_q +R=6370997 +shape="
                    + shape.parameterValue()).getShape());
        }
    }

    /** No {@code +shape} means {@code diamond}, not {@code square} ({@code adams.cpp:408-410}). */
    @Test
    public void absentShapeMeansDiamond() {
        assertEquals(PeirceQuincuncialProjection.Shape.DIAMOND,
                peirceQ("+proj=peirce_q +R=6370997").getShape());
    }

    /** {@code adams.cpp:448-453}: anything outside the six fails the whole setup. */
    @Test
    public void anUnknownShapeIsARejection() {
        for (String value : new String[] {"hemisphere", "SQUARE", "rectangle", ""}) {
            try {
                peirceQ("+proj=peirce_q +R=6370997 +shape=" + value);
                fail("+shape=" + value + " must be rejected");
            } catch (InvalidValueException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("shape"));
            }
        }
    }

    /**
     * A valueless {@code +shape} is a rejection too, not a silent default: {@code pj_param} type
     * {@code 's'} hands the empty string to {@code strcmp} ({@code param.cpp:174-192}), which
     * matches none of the six.
     */
    @Test
    public void aValuelessShapeIsARejection() {
        try {
            peirceQ("+proj=peirce_q +R=6370997 +shape");
            fail("a bare +shape must be rejected rather than defaulting to diamond");
        } catch (InvalidValueException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("shape"));
        }
    }

    /**
     * First-match-wins, the same rule as every other key: {@code pj_param_exists} returns the
     * first occurrence, so the second {@code +shape} is dead.
     */
    @Test
    public void aDuplicateShapeTakesTheFirstOccurrence() {
        assertEquals(PeirceQuincuncialProjection.Shape.SQUARE,
                peirceQ("+proj=peirce_q +R=6370997 +shape=square +shape=vertical").getShape());
    }

    /**
     * {@code +shape} on any other projection is retained and ignored — PROJ-faithful, because
     * nothing but {@code peirce_q} pulls it.
     */
    @Test
    public void shapeIsIgnoredOnEveryOtherProjection() {
        assertNotNull(projection("+proj=merc +R=6370997 +shape=square"));
        assertNotNull(projection("+proj=guyou +R=6370997 +shape=not_a_shape"));
        assertNotNull(projection("+proj=adams_ws2 +R=6370997 +scrollx=99"));
    }

    // ------------------------------------------------------------- +scrollx / +scrolly asymmetry

    /** {@code +scrollx} on {@code horizontal} is read and applied. */
    @Test
    public void scrollXIsReadOnHorizontal() {
        PeirceQuincuncialProjection p =
                peirceQ("+proj=peirce_q +R=6370997 +shape=horizontal +scrollx=0.75");
        assertEquals(0.75, p.getScrollX(), 0.0);
        assertEquals(0.0, p.getScrollY(), 0.0);

        // And it moves the map: the shift is scrollx * shd * 2, in unit-sphere units, scaled by R.
        ProjCoordinate plain = new ProjCoordinate();
        ProjCoordinate scrolled = new ProjCoordinate();
        projection("+proj=peirce_q +R=6370997 +shape=horizontal")
                .project(new ProjCoordinate(20, 40), plain);
        p.project(new ProjCoordinate(20, 40), scrolled);
        assertTrue("+scrollx=0.75 must move the horizontal arrangement; plain=" + plain
                        + " scrolled=" + scrolled,
                Math.abs(scrolled.x - plain.x) > 1000.0);
        assertEquals("scrolling x must not move y", plain.y, scrolled.y, 0.0);
    }

    /** {@code +scrolly} on {@code vertical} is read and applied. */
    @Test
    public void scrollYIsReadOnVertical() {
        PeirceQuincuncialProjection p =
                peirceQ("+proj=peirce_q +R=6370997 +shape=vertical +scrolly=-0.25");
        assertEquals(-0.25, p.getScrollY(), 0.0);
        assertEquals(0.0, p.getScrollX(), 0.0);

        ProjCoordinate plain = new ProjCoordinate();
        ProjCoordinate scrolled = new ProjCoordinate();
        projection("+proj=peirce_q +R=6370997 +shape=vertical")
                .project(new ProjCoordinate(20, 40), plain);
        p.project(new ProjCoordinate(20, 40), scrolled);
        assertTrue("+scrolly=-0.25 must move the vertical arrangement",
                Math.abs(scrolled.y - plain.y) > 1000.0);
        assertEquals("scrolling y must not move x", plain.x, scrolled.x, 0.0);
    }

    /**
     * <b>The asymmetry, part one.</b> {@code +scrollx} off {@code horizontal} is silently
     * ignored, because {@code pj_adams_setup} never looks for it outside that branch. Same for
     * {@code +scrolly} off {@code vertical}. <b>This is upstream's behaviour and the corpus was
     * generated against it — do not "regularise" it.</b>
     */
    @Test
    public void theWrongScrollForTheShapeIsSilentlyIgnored() {
        assertEquals("+scrollx is invisible to +shape=vertical", 0.0,
                peirceQ("+proj=peirce_q +R=6370997 +shape=vertical +scrollx=0.5").getScrollX(),
                0.0);
        assertEquals("+scrolly is invisible to +shape=horizontal", 0.0,
                peirceQ("+proj=peirce_q +R=6370997 +shape=horizontal +scrolly=0.5").getScrollY(),
                0.0);
        assertEquals("neither scroll is read by +shape=square", 0.0,
                peirceQ("+proj=peirce_q +R=6370997 +shape=square +scrollx=0.5 +scrolly=0.5")
                        .getScrollX(), 0.0);
        assertEquals(0.0,
                peirceQ("+proj=peirce_q +R=6370997 +shape=diamond +scrollx=0.5 +scrolly=0.5")
                        .getScrollY(), 0.0);
        assertEquals("no +shape means diamond, so no scroll is read", 0.0,
                peirceQ("+proj=peirce_q +R=6370997 +scrollx=0.5").getScrollX(), 0.0);
    }

    /**
     * <b>The asymmetry, part two.</b> The {@code [-1, 1]} check lives inside the same branch as
     * the read, so an out-of-range value is a hard error on its own shape and <em>not even an
     * error</em> on any other. It looks like an upstream oversight; it is nonetheless the
     * contract.
     */
    @Test
    public void theRangeCheckIsScopedToTheOwningShapeToo() {
        try {
            peirceQ("+proj=peirce_q +R=6370997 +shape=horizontal +scrollx=1.0000001");
            fail("|scrollx| > 1 must be rejected on +shape=horizontal");
        } catch (InvalidValueException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("scrollx"));
        }
        try {
            peirceQ("+proj=peirce_q +R=6370997 +shape=vertical +scrolly=-1.0000001");
            fail("|scrolly| > 1 must be rejected on +shape=vertical");
        } catch (InvalidValueException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("scrolly"));
        }

        // ... and the same values are accepted, unread, off their own shape.
        assertEquals(0.0,
                peirceQ("+proj=peirce_q +R=6370997 +shape=vertical +scrollx=5").getScrollX(), 0.0);
        assertEquals(0.0,
                peirceQ("+proj=peirce_q +R=6370997 +shape=horizontal +scrolly=-5").getScrollY(),
                0.0);
    }

    /** The bounds are inclusive ({@code scrollx > 1 || scrollx < -1}). */
    @Test
    public void theScrollBoundsAreInclusive() {
        assertEquals(1.0,
                peirceQ("+proj=peirce_q +R=6370997 +shape=horizontal +scrollx=1").getScrollX(),
                0.0);
        assertEquals(-1.0,
                peirceQ("+proj=peirce_q +R=6370997 +shape=vertical +scrolly=-1").getScrollY(),
                0.0);
    }

    /** A scroll of exactly zero is a no-op, not a wrap through the same arithmetic. */
    @Test
    public void aZeroScrollIsANoOp() {
        ProjCoordinate plain = new ProjCoordinate();
        ProjCoordinate zero = new ProjCoordinate();
        projection("+proj=peirce_q +R=6370997 +shape=horizontal")
                .project(new ProjCoordinate(20, 40), plain);
        projection("+proj=peirce_q +R=6370997 +shape=horizontal +scrollx=0")
                .project(new ProjCoordinate(20, 40), zero);
        assertEquals(plain.x, zero.x, 0.0);
        assertEquals(plain.y, zero.y, 0.0);
    }

    // --------------------------------------------------------------------------- +azi and +rot

    /** Both reach {@code spilhaus}, and both change the projected coordinate. */
    @Test
    public void aziAndRotReachSpilhausAndChangeTheOutput() {
        SpilhausProjection defaulted = spilhaus("+proj=spilhaus +R=6378137");
        SpilhausProjection turned = spilhaus("+proj=spilhaus +R=6378137 +azi=9.1 +rot=40.1");

        assertEquals(9.1 * ProjectionMath.DTR, turned.getAzi(), 0.0);
        assertEquals(40.1 * ProjectionMath.DTR, turned.getRot(), 0.0);

        ProjCoordinate a = new ProjCoordinate();
        ProjCoordinate b = new ProjCoordinate();
        defaulted.project(new ProjCoordinate(10, -30), a);
        turned.project(new ProjCoordinate(10, -30), b);
        assertTrue("+azi/+rot must change the projected coordinate; default=" + a + " turned=" + b,
                Math.hypot(a.x - b.x, a.y - b.y) > 1000.0);
    }

    /** Absent, the upstream defaults stand ({@code spilhaus.cpp:145,147}). */
    @Test
    public void absentAziAndRotKeepTheUpstreamDefaults() {
        SpilhausProjection p = spilhaus("+proj=spilhaus +R=6378137");
        assertEquals(SpilhausProjection.DEFAULT_AZI_DEGREES * ProjectionMath.DTR, p.getAzi(), 0.0);
        assertEquals(SpilhausProjection.DEFAULT_ROT_DEGREES * ProjectionMath.DTR, p.getRot(), 0.0);
    }

    /**
     * <b>{@code +azi} and {@code +rot} are angles.</b> Upstream reads both through
     * {@code pj_param}'s {@code "r"} sigil, i.e. {@code dmstor}
     * ({@code spilhaus.cpp:133-136}), so they must go through the DMS-capable angle parser and
     * never through bare {@code Double.parseDouble} — which would throw on every form below.
     *
     * <p>{@code +alpha}, {@code +lonc}, {@code +gamma} and {@code +pm} are still parsed with
     * {@code parseDouble} elsewhere in {@code Proj4Parser}; that is a known defect and this is
     * deliberately not a sixth instance of it.
     */
    @Test
    public void aziAndRotGoThroughTheAngleParser() {
        // DMS with minutes and seconds.
        assertEquals(45.5 * ProjectionMath.DTR,
                spilhaus("+proj=spilhaus +R=6378137 +azi=45d30'0\"").getAzi(), 1e-12);
        // Degree-minute without a seconds quote.
        assertEquals(45.5 * ProjectionMath.DTR,
                spilhaus("+proj=spilhaus +R=6378137 +rot=45d30").getRot(), 1e-12);
        // A trailing cardinal carries the sign.
        assertEquals(-30.0 * ProjectionMath.DTR,
                spilhaus("+proj=spilhaus +R=6378137 +azi=30W").getAzi(), 1e-12);
        // PROJ's r/R radian suffix.
        assertEquals(0.5, spilhaus("+proj=spilhaus +R=6378137 +rot=0.5r").getRot(), 1e-12);
        // A plain decimal still works, and zero is a value, not "absent".
        assertEquals(0.0, spilhaus("+proj=spilhaus +R=6378137 +azi=0 +rot=0").getAzi(), 0.0);
        assertEquals(0.0, spilhaus("+proj=spilhaus +R=6378137 +azi=0 +rot=0").getRot(), 0.0);
    }

    /** A value that is not an angle at all is a rejection, not a silent default. */
    @Test
    public void aNonAngularAziIsARejection() {
        try {
            spilhaus("+proj=spilhaus +R=6378137 +azi=nonsense");
            fail("+azi=nonsense must be rejected");
        } catch (InvalidValueException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("azi"));
        }
    }

    /** {@code +azi}/{@code +rot} are invisible to every projection that is not {@code spilhaus}. */
    @Test
    public void aziAndRotAreIgnoredOnEveryOtherProjection() {
        assertNotNull(projection("+proj=merc +R=6378137 +azi=20"));
        assertNotNull(projection("+proj=adams_ws2 +R=6378137 +azi=20 +rot=45"));
        // Not even an unparseable angle is looked at off spilhaus, since nothing pulls it.
        assertNotNull(projection("+proj=merc +R=6378137 +rot=nonsense"));
    }

    // ------------------------------------------------------------------------------ strictness

    /**
     * All five are in {@code Proj4Keyword.supportedParameters()}, so
     * {@link Proj4Parser.ParseMode#STRICT} accepts the corpus's definitions instead of throwing
     * {@code UnsupportedParameterException}. The conformance bridge's capability table is
     * asserted against the same set, which is why the three files move together.
     */
    @Test
    public void strictModeAcceptsAllFive() {
        for (String key : new String[] {"shape", "scrollx", "scrolly", "azi", "rot"}) {
            assertTrue("+" + key, Proj4Keyword.supportedParameters().contains(key));
        }
        Proj4Parser strict = new Proj4Parser(new Registry(), Proj4Parser.ParseMode.STRICT);
        assertNotNull(strict.parse("p",
                "+proj=peirce_q +R=6370997 +shape=horizontal +scrollx=0.75".split("\\s+")));
        assertNotNull(strict.parse("p",
                "+proj=peirce_q +R=6370997 +shape=vertical +scrolly=-0.25".split("\\s+")));
        assertNotNull(strict.parse("s",
                "+proj=spilhaus +R=6378137 +azi=9.1 +rot=40.1".split("\\s+")));
    }

    // -------------------------------------------------------------------------- the real corpus

    /**
     * Every {@code operation} line of {@code peirce_q.gie} and {@code spilhaus.gie} — read from
     * the vendored corpus, not transcribed — is accepted by the parser, and every one of them
     * delivers the parameters it carries.
     *
     * <p>This is the reachability claim in its narrowest form: 10 blocks in {@code peirce_q.gie},
     * <b>every one</b> carrying {@code +shape} — which is why the whole file was unreachable — and
     * 13 in {@code spilhaus.gie}, four of which carry {@code +azi} or {@code +rot}. One of the
     * thirteen is a {@code +proj=adams_ws2} control block, so the {@code spilhaus} cast is
     * conditional. If the dispatch regresses, the assertion that fails names the corpus line.
     */
    @Test
    public void everyOperationLineInTheTwoCorpusFilesDeliversItsParameters() {
        List<String> peirce = operationLines("peirce_q.gie");
        List<String> spil = operationLines("spilhaus.gie");
        assertEquals("peirce_q.gie operation count", 10, peirce.size());
        assertEquals("spilhaus.gie operation count", 13, spil.size());

        int withShape = 0;
        for (String definition : peirce) {
            PeirceQuincuncialProjection p = peirceQ(definition);
            String shape = value(definition, "shape");
            assertNotNull(definition + ": every peirce_q block in the corpus carries +shape",
                    shape);
            withShape++;
            assertEquals(definition, shape, p.getShape().parameterValue());

            String scrollx = value(definition, "scrollx");
            if (scrollx != null && p.getShape() == PeirceQuincuncialProjection.Shape.HORIZONTAL) {
                assertEquals(definition, Double.parseDouble(scrollx), p.getScrollX(), 0.0);
            }
            String scrolly = value(definition, "scrolly");
            if (scrolly != null && p.getShape() == PeirceQuincuncialProjection.Shape.VERTICAL) {
                assertEquals(definition, Double.parseDouble(scrolly), p.getScrollY(), 0.0);
            }
        }
        assertEquals("every peirce_q.gie block carries +shape", 10, withShape);

        int withAziOrRot = 0;
        for (String definition : spil) {
            Projection projection = projection(definition);
            String azi = value(definition, "azi");
            String rot = value(definition, "rot");
            if (!(projection instanceof SpilhausProjection)) {
                // spilhaus.gie's one +proj=adams_ws2 control block.
                assertNotNull(definition, projection);
                continue;
            }
            SpilhausProjection p = (SpilhausProjection) projection;
            if (azi != null) {
                assertEquals(definition, Double.parseDouble(azi) * ProjectionMath.DTR, p.getAzi(),
                        1e-12);
            }
            if (rot != null) {
                assertEquals(definition, Double.parseDouble(rot) * ProjectionMath.DTR, p.getRot(),
                        1e-12);
            }
            if (azi != null || rot != null) {
                withAziOrRot++;
            }
        }
        assertEquals("spilhaus.gie's blocks carrying +azi and/or +rot", 4, withAziOrRot);
    }

    /** The value of {@code +key=} in a proj-string, or null. */
    private static String value(String definition, String key) {
        for (String token : definition.trim().split("\\s+")) {
            String kv = token.startsWith("+") ? token.substring(1) : token;
            int eq = kv.indexOf('=');
            if (eq > 0 && kv.substring(0, eq).equals(key)) {
                return kv.substring(eq + 1);
            }
        }
        return null;
    }

    /**
     * The {@code operation} definitions of a vendored {@code .gie} file. Only the {@code
     * operation} verb is needed here, so this is a filter rather than a lexer; the numeric rows of
     * these two files are driven by {@code AdamsRegistrationAndParserGapTest}.
     */
    private static List<String> operationLines(String file) {
        Path path = corpusDirectory().resolve(file);
        List<String> operations = new ArrayList<String>();
        try {
            for (String raw : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                int hash = raw.indexOf('#');
                String line = (hash >= 0 ? raw.substring(0, hash) : raw).trim();
                if (line.startsWith("operation")) {
                    operations.add(line.substring("operation".length()).trim());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("reading " + path, e);
        }
        if (operations.isEmpty()) {
            throw new IllegalStateException("no operation lines found in " + path);
        }
        return operations;
    }

    /**
     * Locates {@code conformance/src/test/resources/gie}. Surefire runs with the module base
     * directory as the working directory, so {@code ../conformance/...} resolves from
     * {@code core/}; the repository root and the {@code basedir} property are tried too.
     */
    private static Path corpusDirectory() {
        List<Path> candidates = new ArrayList<Path>();
        String basedir = System.getProperty("basedir");
        if (basedir != null) {
            candidates.add(
                    Paths.get(basedir, "..", "conformance", "src", "test", "resources", "gie"));
        }
        candidates.add(Paths.get("..", "conformance", "src", "test", "resources", "gie"));
        candidates.add(Paths.get("conformance", "src", "test", "resources", "gie"));
        for (Path p : candidates) {
            if (Files.isDirectory(p)) {
                return p;
            }
        }
        throw new IllegalStateException("cannot locate conformance/src/test/resources/gie from "
                + Paths.get(".").toAbsolutePath().normalize() + "; tried " + candidates);
    }
}
