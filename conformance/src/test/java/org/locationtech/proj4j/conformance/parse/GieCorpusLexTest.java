/*
 * Copyright 2026 The Proj4J Contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.locationtech.proj4j.conformance.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.URL;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The real self-check: lex all 42 active corpus files and account for every
 * command. A lexer that passes hand-written fixtures but drops a hundred
 * assertions on {@code builtins.gie} is no use, so the counts below are pinned
 * as a regression fence.
 *
 * <p><strong>These numbers correct the skill's corpus table.</strong> Measured
 * against {@code 9.8.1} they are:
 *
 * <pre>
 *   operation             780   (skill said 775; a column-0-only grep gives 774)
 *   crs_src / crs_dst     8 / 8 (8 pairs)
 *   use_proj4_init_rules   23   (skill said 24, "all in test/gigs" - 3 are in test/gie)
 *   accept               7288
 *   expect               6962   (skill said 7056)
 *   of which failure     1187   (skill's headline said 811; its own column sums to 1187)
 *   roundtrip             961   (agrees)
 *   direction             283   (skill said 284)
 *   tolerance            1634
 *   require_grid            3   (skill said 5)
 *   banner verbose ignore echo skip   0 each (agrees)
 * </pre>
 *
 * <p>The {@code expect} gap is entirely {@code DHDN_ETRS89.gie}: 94 of its 158
 * {@code accept}/{@code expect} pairs sit <em>after</em> {@code </gie-strict>}
 * (source lines 162-375), under a heading that says "Tests for GK system zones
 * to UTM32/33 not implemented yet". gie never reads them. The {@code operation}
 * gap is {@code peirce_q.gie}, which indents 6 of its 10 {@code operation}
 * lines by one space; {@code pj_chomp} left-trims before the verb test, so they
 * are commands.
 *
 * <p>So the headline metric is <strong>6,962 + 961 = 7,923</strong> assertions,
 * not 8,017.
 *
 * <p>Deliberately <em>not</em> gated behind the module's {@code gie.corpus.skip}
 * property. That flag exists to keep the expensive part out of a plain build —
 * the part that creates operations and transforms coordinates. This test only
 * splits strings, so it costs milliseconds and should always run.
 */
class GieCorpusLexTest {

    /** Expected command counts over the 42 active files, at PROJ 9.8.1. */
    private static final Map<GieVerb, Integer> EXPECTED = new EnumMap<GieVerb, Integer>(GieVerb.class);

    static {
        EXPECTED.put(GieVerb.OPEN_GIE, 0);
        EXPECTED.put(GieVerb.OPERATION, 780);
        EXPECTED.put(GieVerb.CRS_SRC, 8);
        EXPECTED.put(GieVerb.CRS_DST, 8);
        EXPECTED.put(GieVerb.USE_PROJ4_INIT_RULES, 23);
        EXPECTED.put(GieVerb.ACCEPT, 7288);
        EXPECTED.put(GieVerb.EXPECT, 6962);
        EXPECTED.put(GieVerb.ROUNDTRIP, 961);
        EXPECTED.put(GieVerb.BANNER, 0);
        EXPECTED.put(GieVerb.VERBOSE, 0);
        EXPECTED.put(GieVerb.DIRECTION, 283);
        EXPECTED.put(GieVerb.TOLERANCE, 1634);
        EXPECTED.put(GieVerb.IGNORE, 0);
        EXPECTED.put(GieVerb.REQUIRE_GRID, 3);
        EXPECTED.put(GieVerb.ECHO, 0);
        EXPECTED.put(GieVerb.SKIP, 0);
        EXPECTED.put(GieVerb.CLOSE_GIE, 0);
        EXPECTED.put(GieVerb.OPEN_GIE_STRICT, 0);
        EXPECTED.put(GieVerb.CLOSE_GIE_STRICT, 42);
    }

    private static final int EXPECTED_FILES = 42;
    private static final int EXPECTED_EXPECT_FAILURE = 1187;

    // ------------------------------------------------------------ resolution

    /**
     * Locate a vendored corpus directory, preferring the test classpath and
     * falling back to the source tree so this works whether or not the
     * resources have been copied yet.
     */
    private static Path corpusDir(String name) {
        try {
            URL u = GieCorpusLexTest.class.getResource("/" + name);
            if (u != null && "file".equals(u.getProtocol())) {
                Path p = Paths.get(u.toURI());
                if (Files.isDirectory(p)) {
                    return p;
                }
            }
        } catch (Exception ignored) {
            // fall through to the source tree
        }
        for (String prefix : new String[] {"src/test/resources", "conformance/src/test/resources"}) {
            Path p = Paths.get(prefix).resolve(name);
            if (Files.isDirectory(p)) {
                return p;
            }
        }
        return null;
    }

    private static List<Path> activeGieFiles() throws IOException {
        List<Path> out = new ArrayList<Path>();
        for (String dir : new String[] {"gie", "gigs"}) {
            Path d = corpusDir(dir);
            if (d == null) {
                continue;
            }
            DirectoryStream<Path> s = Files.newDirectoryStream(d, "*.gie");
            try {
                for (Path p : s) {
                    // "*.gie" already excludes the quarantined "*.gie.failing".
                    out.add(p);
                }
            } finally {
                s.close();
            }
        }
        Collections.sort(out);
        return out;
    }

    // ----------------------------------------------------------------- tests

    @Test
    @DisplayName("every one of the 42 active corpus files lexes with zero fatal errors")
    void wholeCorpusLexesCleanly() throws IOException {
        List<Path> files = activeGieFiles();
        assumeTrue(!files.isEmpty(),
                "corpus not vendored under src/test/resources/{gie,gigs} yet");
        assertEquals(EXPECTED_FILES, files.size(),
                "wrong number of active .gie files: " + files);

        Map<GieVerb, Integer> total = new EnumMap<GieVerb, Integer>(GieVerb.class);
        for (GieVerb v : GieVerb.values()) {
            total.put(v, 0);
        }
        int expectFailure = 0;
        List<String> failures = new ArrayList<String>();

        for (Path f : files) {
            GieFile parsed;
            try {
                parsed = GieLexer.lex(f);
            } catch (GieSyntaxException e) {
                failures.add(e.getMessage());
                continue;
            }
            assertTrue(parsed.size() > 0, f + " lexed to nothing");
            for (Map.Entry<GieVerb, Integer> e : parsed.verbCounts().entrySet()) {
                total.put(e.getKey(), total.get(e.getKey()) + e.getValue());
            }
            for (GieCommand c : parsed.commands(GieVerb.EXPECT)) {
                if (c.args().startsWith("failure")) {
                    expectFailure++;
                }
            }
        }

        assertEquals(Collections.<String>emptyList(), failures,
                "fatal lexing errors in the active corpus");

        StringBuilder report = new StringBuilder("per-verb command counts over "
                + files.size() + " active .gie files:\n");
        for (GieVerb v : GieVerb.values()) {
            report.append(String.format("  %-22s %6d%n", v.token(), total.get(v)));
        }
        report.append(String.format("  %-22s %6d%n", "(expect failure)", expectFailure));
        report.append(String.format("  %-22s %6d%n", "(assertions)",
                total.get(GieVerb.EXPECT) + total.get(GieVerb.ROUNDTRIP)));
        System.out.print(report);

        for (GieVerb v : GieVerb.values()) {
            assertEquals(EXPECTED.get(v).intValue(), total.get(v).intValue(),
                    "command count for '" + v.token() + "'\n" + report);
        }
        assertEquals(EXPECTED_EXPECT_FAILURE, expectFailure, "expect failure count\n" + report);
    }

    @Test
    @DisplayName("the headline assertion count is expect + roundtrip = 7,923")
    void headlineAssertionCount() {
        assertEquals(7923,
                EXPECTED.get(GieVerb.EXPECT) + EXPECTED.get(GieVerb.ROUNDTRIP));
    }

    @Test
    @DisplayName("every command carries a plausible 1-based line number")
    void lineNumbersAreSane() throws IOException {
        List<Path> files = activeGieFiles();
        assumeTrue(!files.isEmpty(), "corpus not vendored yet");
        for (Path f : files) {
            int lineCount = Files.readAllLines(f, GieLexer.GIE_CHARSET).size();
            GieFile parsed = GieLexer.lex(f);
            int previous = 0;
            for (GieCommand c : parsed.commands()) {
                assertTrue(c.line() >= 1, f + ": non-positive line " + c);
                assertTrue(c.line() <= lineCount, f + ": line past EOF " + c);
                assertTrue(c.lastLine() >= c.line(), f + ": lastLine before line " + c);
                assertTrue(c.line() > previous,
                        f + ": line numbers not strictly increasing at " + c);
                previous = c.line();
            }
        }
    }

    @Test
    @DisplayName("DHDN_ETRS89 has 64 assertions in its block, not the 158 lines in the file")
    void dhdnOutOfBlockLinesAreNotAssertions() throws IOException {
        Path d = corpusDir("gie");
        assumeTrue(d != null, "corpus not vendored yet");
        Path f = d.resolve("DHDN_ETRS89.gie");
        assumeTrue(Files.isRegularFile(f), "DHDN_ETRS89.gie not vendored");

        GieFile parsed = GieLexer.lex(f);
        assertEquals(64, parsed.commands(GieVerb.ACCEPT).size());
        assertEquals(64, parsed.commands(GieVerb.EXPECT).size());
        // Everything it lexed is inside the <gie-strict> block, which ends at 161.
        for (GieCommand c : parsed.commands()) {
            assertTrue(c.line() <= 161,
                    "command outside the gie block was lexed: " + c);
        }
    }

    @Test
    @DisplayName("peirce_q has 10 operations, six of them indented")
    void peirceQIndentedOperations() throws IOException {
        Path d = corpusDir("gie");
        assumeTrue(d != null, "corpus not vendored yet");
        Path f = d.resolve("peirce_q.gie");
        assumeTrue(Files.isRegularFile(f), "peirce_q.gie not vendored");

        GieFile parsed = GieLexer.lex(f);
        List<GieCommand> ops = parsed.commands(GieVerb.OPERATION);
        assertEquals(10, ops.size());
        int indented = 0;
        for (GieCommand c : ops) {
            if (c.raw().startsWith(" ")) {
                indented++;
            }
        }
        assertEquals(6, indented, "six of the ten operation lines are indented");
    }

    @Test
    @DisplayName("builtins.gie has both a non-strict and a strict block")
    void builtinsHasBothModes() throws IOException {
        Path d = corpusDir("gie");
        assumeTrue(d != null, "corpus not vendored yet");
        Path f = d.resolve("builtins.gie");
        assumeTrue(Files.isRegularFile(f), "builtins.gie not vendored");

        GieFile parsed = GieLexer.lex(f);
        assertEquals(369, parsed.commands(GieVerb.OPERATION).size());
        assertEquals(2185, parsed.commands(GieVerb.EXPECT).size());
        assertEquals(2147, parsed.commands(GieVerb.ACCEPT).size());
        assertEquals(359, parsed.commands(GieVerb.ROUNDTRIP).size());
        // Exactly one </gie-strict>; the non-strict block's </gie> is consumed
        // by the lexer and never surfaces as a command.
        assertEquals(1, parsed.commands(GieVerb.CLOSE_GIE_STRICT).size());
        assertEquals(0, parsed.commands(GieVerb.CLOSE_GIE).size());

        // The first command comes from the non-strict block and is assembled
        // from two physical lines without any ' \' continuation marker.
        GieCommand first = parsed.commands().get(0);
        assertEquals(GieVerb.OPERATION, first.verb());
        assertEquals("proj=aea ellps=GRS80 lat_1=0 lat_2=2", first.args());
        assertEquals(17, first.line());
        assertEquals(18, first.lastLine());
    }

    @Test
    @DisplayName("every accept and expect line parses to a usable coordinate")
    void everyCoordinateParses() throws IOException {
        List<Path> files = activeGieFiles();
        assumeTrue(!files.isEmpty(), "corpus not vendored yet");
        int coords = 0;
        List<String> bad = new ArrayList<String>();
        for (Path f : files) {
            GieFile parsed = GieLexer.lex(f);
            for (GieCommand c : parsed.commands()) {
                if (c.verb() != GieVerb.ACCEPT && c.verb() != GieVerb.EXPECT) {
                    continue;
                }
                if (c.verb() == GieVerb.EXPECT && c.args().startsWith("failure")) {
                    continue; // not a coordinate at all
                }
                coords++;
                GieCoord coord = GieCoordParser.parseCoord(c.args());
                if (coord.isError() || coord.dimensionsGiven() < 2) {
                    bad.add(f.getFileName() + ":" + c.line() + " " + c.verb().token()
                            + " [" + c.args() + "]");
                }
            }
        }
        assertEquals(Collections.<String>emptyList(), bad,
                "coordinates that failed to parse");
        // 7288 accept + (6962 - 1187) expect
        assertEquals(7288 + 6962 - 1187, coords);
    }
}
