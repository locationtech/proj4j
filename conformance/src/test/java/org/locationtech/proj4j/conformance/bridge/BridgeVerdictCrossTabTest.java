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
package org.locationtech.proj4j.conformance.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URL;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.proj4j.conformance.parse.GieCommand;
import org.locationtech.proj4j.conformance.parse.GieFile;
import org.locationtech.proj4j.conformance.parse.GieLexer;
import org.locationtech.proj4j.conformance.parse.GieVerb;

/**
 * The false-positive control for {@link GieFailureKind#INVALID_DEFINITION}.
 *
 * <p>{@code INVALID_DEFINITION} is the one bridge verdict that can manufacture a
 * <em>false pass</em>: {@code ExpectedFailureVerdict.ofConstructionFailure} promotes it
 * to {@code PASS_EXPECTED_FAILURE} on any row that is not a {@code coord_transfm*} /
 * {@code no_inverse_op} assertion. Every other verdict can only understate progress.
 *
 * <p>The corpus itself supplies the control. Cross-tabulate each {@code operation}'s
 * bridge verdict against what the corpus asserts about it:
 *
 * <pre>
 *                              INVALID_DEFINITION   NOT_IMPLEMENTED   executable
 *   followed by expect failure          n                 n               n
 *   followed by a coordinate         MUST BE 0            n               n
 * </pre>
 *
 * <p><b>An operation the bridge calls {@code INVALID_DEFINITION} that the corpus
 * follows with a coordinate assertion is a proven false positive</b> — PROJ
 * demonstrably built that operation and produced numbers from it, so "PROJ 9.8.1
 * would reject this too" is false. That is the bottom-left cell, and it must read 0.
 *
 * <p>An operation counts as "followed by a coordinate" if <em>any</em> of its
 * assertions is a coordinate {@code expect} or a {@code roundtrip}; only operations
 * whose every assertion is an {@code expect failure} land in the top row. That is the
 * sensitive direction for the control: a single coordinate anywhere in the block is
 * enough to convict.
 *
 * <p>{@link #crossTabDetectsAnInjectedFalsePositive()} is the positive control
 * required by the skill's non-negotiable 5c — a verification without one is a claim,
 * not a measurement. It re-runs the same tabulation with a mutant verdict function
 * that calls a handful of demonstrably-valid operators invalid, and asserts the
 * bottom-left cell becomes non-zero. Without it, a tabulation that silently counted
 * nothing would report the same clean 0.
 */
class BridgeVerdictCrossTabTest {

    // ------------------------------------------------------------ resolution

    private static Path corpusDir(String name) {
        try {
            URL u = BridgeVerdictCrossTabTest.class.getResource("/" + name);
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
                    out.add(p);
                }
            } finally {
                s.close();
            }
        }
        Collections.sort(out);
        return out;
    }

    // --------------------------------------------------------- the tabulation

    /** How the bridge classified one operation. {@code null} kind means executable. */
    private interface Verdict {
        GieFailure classify(String args);
    }

    /** One {@code operation} block and what the corpus asserts about it. */
    private static final class Block {
        final String where;
        final String args;
        final GieFailureKind kind;
        final String reason;
        final List<String> errnos = new ArrayList<String>();
        int coordinateAssertions;
        int failureAssertions;

        Block(String where, String args, GieFailure failure) {
            this.where = where;
            this.args = args;
            this.kind = failure == null ? null : failure.kind();
            this.reason = failure == null ? "" : failure.message();
        }

        boolean followedByCoordinate() {
            return coordinateAssertions > 0;
        }

        boolean hasAssertions() {
            return coordinateAssertions + failureAssertions > 0;
        }
    }

    private static List<Block> tabulate(List<Path> files, Verdict verdict) throws IOException {
        List<Block> blocks = new ArrayList<Block>();
        for (Path f : files) {
            GieFile parsed = GieLexer.lex(f);
            Block current = null;
            String pendingSrc = null;
            String pendingDst = null;
            for (GieCommand c : parsed.commands()) {
                switch (c.verb()) {
                    case OPERATION:
                        current = new Block(f.getFileName() + ":" + c.line(), c.args(),
                                verdict.classify(c.args()));
                        blocks.add(current);
                        pendingSrc = null;
                        pendingDst = null;
                        break;
                    case CRS_SRC:
                    case CRS_DST:
                        // A completed crs_src+crs_dst pair opens a block of its own, exactly as
                        // `operation` does (gie.cpp:719-736), and gie then clears both back to "".
                        if (c.verb() == GieVerb.CRS_SRC) {
                            pendingSrc = c.args();
                        } else {
                            pendingDst = c.args();
                        }
                        if (pendingSrc != null && pendingDst != null) {
                            // Tracked so their assertions are not misattributed to the preceding
                            // `operation`; the pairs themselves are reported separately.
                            current = null;
                            pendingSrc = null;
                            pendingDst = null;
                        }
                        break;
                    case EXPECT:
                        if (current != null) {
                            if (isExpectFailure(c.args())) {
                                current.failureAssertions++;
                                current.errnos.add(errnoOf(c.args()));
                            } else {
                                current.coordinateAssertions++;
                            }
                        }
                        break;
                    case ROUNDTRIP:
                        if (current != null) {
                            current.coordinateAssertions++;
                        }
                        break;
                    default:
                        break;
                }
            }
        }
        return blocks;
    }

    /** gie's own test: {@code expect}'s argument starts with the 7 characters "failure". */
    private static boolean isExpectFailure(String args) {
        return args != null && args.startsWith("failure");
    }

    /** The errno constant an {@code expect failure} row names, or {@code "-"}. */
    private static String errnoOf(String args) {
        String[] tok = args.trim().split("\\s+");
        if (tok.length >= 3 && tok[1].startsWith("errno")) {
            return tok[2];
        }
        return "-";
    }

    private static int[][] cells(List<Block> blocks) {
        // [row][col]; row 0 = expect failure, row 1 = coordinate.
        // col 0 = INVALID_DEFINITION, col 1 = NOT_IMPLEMENTED, col 2 = executable.
        int[][] t = new int[2][3];
        for (Block b : blocks) {
            if (!b.hasAssertions()) {
                continue;
            }
            int row = b.followedByCoordinate() ? 1 : 0;
            int col;
            if (b.kind == null) {
                col = 2;
            } else if (b.kind == GieFailureKind.INVALID_DEFINITION) {
                col = 0;
            } else {
                col = 1;
            }
            t[row][col]++;
        }
        return t;
    }

    private static String render(int[][] t) {
        StringBuilder r = new StringBuilder();
        r.append(String.format("%-30s %20s %18s %12s%n", "", "INVALID_DEFINITION",
                "NOT_IMPLEMENTED", "executable"));
        r.append(String.format("%-30s %20d %18d %12d%n", "followed by expect failure",
                Integer.valueOf(t[0][0]), Integer.valueOf(t[0][1]), Integer.valueOf(t[0][2])));
        r.append(String.format("%-30s %20d %18d %12d%n", "followed by a coordinate",
                Integer.valueOf(t[1][0]), Integer.valueOf(t[1][1]), Integer.valueOf(t[1][2])));
        return r.toString();
    }

    // ---------------------------------------------------------------- the test

    @Test
    @DisplayName("no INVALID_DEFINITION operation is followed by a coordinate assertion")
    void crossTabHasNoFalsePositives() throws IOException {
        List<Path> files = activeGieFiles();
        assumeTrue(!files.isEmpty(), "corpus not vendored yet");

        final GieOperationFactory factory = new Proj4jGieOperationFactory();
        List<Block> blocks;
        PrintStream savedErr = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(captured, true, "UTF-8"));
            blocks = tabulate(files, new Verdict() {
                @Override
                public GieFailure classify(String args) {
                    GieOperation o = factory.create(args);
                    return o.isUsable() ? null : o.failure();
                }
            });
        } finally {
            System.setErr(savedErr);
        }

        int[][] t = cells(blocks);
        StringBuilder r = new StringBuilder("\n=== bridge verdict x corpus assertion ===\n");
        r.append(render(t));

        // The evidence, spelled out, for whichever cell is non-zero.
        List<Block> falsePositives = new ArrayList<Block>();
        for (Block b : blocks) {
            if (b.kind == GieFailureKind.INVALID_DEFINITION && b.followedByCoordinate()) {
                falsePositives.add(b);
            }
        }
        if (!falsePositives.isEmpty()) {
            r.append("\n  PROVEN FALSE POSITIVES (PROJ produced coordinates for these):\n");
            for (Block b : falsePositives) {
                r.append("      ").append(b.where).append("  ").append(b.args).append('\n');
                r.append("          reason: ").append(b.reason).append('\n');
            }
        }

        // The recoverable population: expect-failure rows the bridge cannot speak to.
        Map<String, Integer> notImplByProj = new TreeMap<String, Integer>();
        Map<String, String> exampleByProj = new TreeMap<String, String>();
        int notImplRows = 0;
        for (Block b : blocks) {
            if (b.kind == GieFailureKind.NOT_IMPLEMENTED && !b.followedByCoordinate()
                    && b.hasAssertions()) {
                String name = GieProjArgs.parse(b.args).peek("proj");
                String key = name == null ? "(none)" : name;
                Integer cur = notImplByProj.get(key);
                notImplByProj.put(key, Integer.valueOf(cur == null ? 1 : cur.intValue() + 1));
                if (!exampleByProj.containsKey(key)) {
                    exampleByProj.put(key, b.where + "  " + b.args);
                }
                notImplRows += b.failureAssertions;
            }
        }
        r.append("\n  NOT_IMPLEMENTED operations whose every assertion is `expect failure`,\n");
        r.append("  by +proj (").append(notImplRows).append(" assertion rows):\n");
        List<Map.Entry<String, Integer>> sorted =
                new ArrayList<Map.Entry<String, Integer>>(notImplByProj.entrySet());
        Collections.sort(sorted, new Comparator<Map.Entry<String, Integer>>() {
            @Override
            public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) {
                int c = b.getValue().compareTo(a.getValue());
                return c != 0 ? c : a.getKey().compareTo(b.getKey());
            }
        });
        for (Map.Entry<String, Integer> e : sorted) {
            r.append(String.format("      %-14s %3d   %s%n", e.getKey(), e.getValue(),
                    exampleByProj.get(e.getKey())));
        }
        r.append("\n  ...in full:\n");
        for (Block b : blocks) {
            if (b.kind == GieFailureKind.NOT_IMPLEMENTED && !b.followedByCoordinate()
                    && b.hasAssertions()) {
                r.append(String.format("      %-28s %-42s %s%n", b.where, b.errnos, b.args));
            }
        }
        System.out.print(r);

        assertEquals(0, t[1][0],
                "PROVEN FALSE POSITIVES: the bridge called these definitions invalid, but the "
                        + "corpus asserts PROJ produced coordinates from them.\n" + r);
    }

    /**
     * Positive control. The clean 0 above is only meaningful if this tabulation can
     * report a non-zero. A mutant verdict function calls three operators that the
     * corpus exercises with real coordinates {@code INVALID_DEFINITION}; the
     * bottom-left cell must light up.
     */
    @Test
    @DisplayName("positive control: the cross-tab detects an injected false positive")
    void crossTabDetectsAnInjectedFalsePositive() throws IOException {
        List<Path> files = activeGieFiles();
        assumeTrue(!files.isEmpty(), "corpus not vendored yet");

        List<Block> blocks = tabulate(files, new Verdict() {
            @Override
            public GieFailure classify(String args) {
                String name = GieProjArgs.parse(args).peek("proj");
                if ("merc".equals(name) || "utm".equals(name) || "aea".equals(name)) {
                    return GieFailures.invalidDefinition("injected false positive for +proj=" + name);
                }
                return null;
            }
        });

        int[][] t = cells(blocks);
        assertTrue(t[1][0] > 0,
                "the cross-tab failed to detect a deliberately injected false positive, so its "
                        + "clean result on the real bridge means nothing:\n" + render(t));
    }
}
