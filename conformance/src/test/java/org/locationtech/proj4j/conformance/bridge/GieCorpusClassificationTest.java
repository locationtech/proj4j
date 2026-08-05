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
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import java.util.EnumMap;
import java.util.LinkedHashMap;
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
 * Runs the bridge over every {@code operation} and {@code crs_src}/{@code crs_dst}
 * pair in the vendored corpus and prints the census.
 *
 * <p>This is the most useful single number the bridge can produce: how many of the
 * corpus's operations are <em>executable</em> versus classified, and — for the
 * classified ones — the exact reason, aggregated. Without it, "the bridge is an
 * honest boundary" is an assertion rather than a measurement.
 *
 * <p>Deliberately not gated behind {@code gie.corpus.skip}: it constructs
 * operations but transforms no coordinates, so it costs a couple of seconds, and a
 * silent collapse in executability is exactly the regression this module exists to
 * catch.
 */
class GieCorpusClassificationTest {

    // ------------------------------------------------------------ resolution

    private static Path corpusDir(String name) {
        try {
            URL u = GieCorpusClassificationTest.class.getResource("/" + name);
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
            // "*.gie" already excludes the quarantined "*.gie.failing".
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

    // ------------------------------------------------------------- the sweep

    @Test
    @DisplayName("classify every operation in the 42 active corpus files and print the census")
    void classifyWholeCorpus() throws IOException {
        List<Path> files = activeGieFiles();
        assumeTrue(!files.isEmpty(),
                "corpus not vendored under src/test/resources/{gie,gigs} yet");

        GieOperationFactory factory = new Proj4jGieOperationFactory();

        int operations = 0;
        int usable = 0;
        Map<GieFailureKind, Integer> byKind = new EnumMap<GieFailureKind, Integer>(GieFailureKind.class);
        for (GieFailureKind k : GieFailureKind.values()) {
            byKind.put(k, Integer.valueOf(0));
        }
        Map<String, Integer> reasons = new TreeMap<String, Integer>();
        Map<String, Integer> usableByProj = new TreeMap<String, Integer>();
        Map<String, int[]> perFile = new LinkedHashMap<String, int[]>();

        // Registry.getProjection prints a stack trace for the three abstract
        // registrations; the probe suppresses that, and this assertion proves it.
        PrintStream savedErr = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(captured, true, "UTF-8"));

            for (Path f : files) {
                GieFile parsed = GieLexer.lex(f);
                int[] counts = new int[2];
                perFile.put(f.getFileName().toString(), counts);
                for (GieCommand c : parsed.commands(GieVerb.OPERATION)) {
                    operations++;
                    GieOperation o = factory.create(c.args());
                    assertNotNull(o, f + ":" + c.line() + " produced no GieOperation");
                    if (o.isUsable()) {
                        usable++;
                        counts[0]++;
                        String name = GieProjArgs.parse(c.args()).peek("proj");
                        bump(usableByProj, name == null ? "(none)" : name);
                    } else {
                        counts[1]++;
                        GieFailure fail = o.failure();
                        assertNotNull(fail, f + ":" + c.line() + " unusable without a failure");
                        byKind.put(fail.kind(),
                                Integer.valueOf(byKind.get(fail.kind()).intValue() + 1));
                        bump(reasons, fail.kind() + " | " + summarise(fail.message()));
                    }
                }
            }
        } finally {
            System.setErr(savedErr);
        }

        String leaked = captured.toString("UTF-8");
        assertEquals("", leaked,
                "the sweep leaked output to stderr; Registry's abstract-registration stack "
                        + "traces must stay suppressed:\n" + leaked);

        // ------------------------------------------------------------ report
        StringBuilder r = new StringBuilder();
        r.append("\n=== gie corpus operation census (").append(files.size())
                .append(" active files) ===\n");
        r.append(String.format("  operations            %5d%n", Integer.valueOf(operations)));
        r.append(String.format("  executable            %5d  (%.1f%%)%n", Integer.valueOf(usable),
                Double.valueOf(100.0 * usable / Math.max(1, operations))));
        r.append(String.format("  classified            %5d%n",
                Integer.valueOf(operations - usable)));
        for (GieFailureKind k : GieFailureKind.values()) {
            int n = byKind.get(k).intValue();
            if (n > 0) {
                r.append(String.format("      %-20s %5d%n", k, Integer.valueOf(n)));
            }
        }

        r.append("\n  executable operations by +proj=:\n");
        for (Map.Entry<String, Integer> e : sortedByCountDesc(usableByProj)) {
            r.append(String.format("      %-14s %4d%n", e.getKey(), e.getValue()));
        }

        r.append("\n  top classification reasons:\n");
        int shown = 0;
        for (Map.Entry<String, Integer> e : sortedByCountDesc(reasons)) {
            if (shown++ >= 30) {
                break;
            }
            r.append(String.format("      %4d  %s%n", e.getValue(), e.getKey()));
        }

        r.append("\n  per-file executable/classified:\n");
        for (Map.Entry<String, int[]> e : perFile.entrySet()) {
            r.append(String.format("      %-24s %4d / %4d%n", e.getKey(),
                    Integer.valueOf(e.getValue()[0]), Integer.valueOf(e.getValue()[1])));
        }
        System.out.print(r);

        // Invariants, not vanity numbers. These are the shape of the answer, and
        // they are the thing that breaks if the bridge silently regresses.
        assertEquals(780, operations,
                "the active corpus has 780 `operation` commands (measured by GieCorpusLexTest)");
        assertTrue(usable >= 150,
                "only " + usable + " of " + operations + " operations are executable; the bridge "
                        + "has regressed or core has. Census:\n" + r);
        assertTrue(byKind.get(GieFailureKind.NOT_IMPLEMENTED).intValue() > 0);
        assertTrue(byKind.get(GieFailureKind.INVALID_DEFINITION).intValue() > 0,
                "no operation classified INVALID_DEFINITION - the split that makes "
                        + "`expect failure` rows meaningful has stopped working");
    }

    @Test
    @DisplayName("classify every crs_src/crs_dst pair")
    void classifyCrsPairs() throws IOException {
        List<Path> files = activeGieFiles();
        assumeTrue(!files.isEmpty(), "corpus not vendored yet");

        GieOperationFactory factory = new Proj4jGieOperationFactory();
        StringBuilder r = new StringBuilder("\n=== gie corpus crs_src/crs_dst census ===\n");
        int pairs = 0;
        int usable = 0;
        for (Path f : files) {
            GieFile parsed = GieLexer.lex(f);
            List<GieCommand> src = parsed.commands(GieVerb.CRS_SRC);
            List<GieCommand> dst = parsed.commands(GieVerb.CRS_DST);
            int n = Math.min(src.size(), dst.size());
            for (int i = 0; i < n; i++) {
                pairs++;
                String s = firstToken(src.get(i).args());
                String d = firstToken(dst.get(i).args());
                GieOperation o = factory.createCrsToCrs(s, d);
                assertNotNull(o);
                if (o.isUsable()) {
                    usable++;
                    r.append(String.format("  OK    %-12s -> %-12s left=%s right=%s%n", s, d,
                            o.leftUnits(), o.rightUnits()));
                } else {
                    r.append(String.format("  %-5s %-12s -> %-12s %s%n", o.failure().kind(), s, d,
                            summarise(o.failure().message())));
                }
            }
        }
        r.append(String.format("  %d pairs, %d executable%n", Integer.valueOf(pairs),
                Integer.valueOf(usable)));
        System.out.print(r);
        assertEquals(8, pairs, "the active corpus has 8 crs_src/crs_dst pairs");
    }

    /**
     * A {@code crs_src} argument is a single token; anything after it is a trailing
     * comment the lexer already stripped, but a defensive split costs nothing.
     */
    private static String firstToken(String args) {
        if (args == null) {
            return null;
        }
        String t = args.trim();
        int sp = t.indexOf(' ');
        return sp < 0 ? t : t.substring(0, sp);
    }

    private static void bump(Map<String, Integer> m, String key) {
        Integer cur = m.get(key);
        m.put(key, Integer.valueOf(cur == null ? 1 : cur.intValue() + 1));
    }

    /** Collapse a message to its distinguishing head, so counts aggregate usefully. */
    private static String summarise(String message) {
        String m = message == null ? "" : message;
        int colon = m.indexOf(':');
        String head = colon > 0 ? m.substring(0, colon) : m;
        return head.length() > 90 ? head.substring(0, 90) : head;
    }

    private static List<Map.Entry<String, Integer>> sortedByCountDesc(Map<String, Integer> m) {
        List<Map.Entry<String, Integer>> out =
                new ArrayList<Map.Entry<String, Integer>>(m.entrySet());
        Collections.sort(out, new Comparator<Map.Entry<String, Integer>>() {
            @Override
            public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) {
                int c = b.getValue().compareTo(a.getValue());
                return c != 0 ? c : a.getKey().compareTo(b.getKey());
            }
        });
        return out;
    }
}
