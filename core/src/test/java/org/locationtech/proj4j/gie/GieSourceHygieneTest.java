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
package org.locationtech.proj4j.gie;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * A structural guard, not a behavioural test.
 *
 * <p>The single most damaging mistake available in this area is to convert a coordinate delta to
 * metres by multiplying by a constant degrees-per-metre factor. It is damaging because it does
 * not look wrong: the number is roughly the right size at the equator and silently wrong
 * everywhere else, and it is off by a factor of two at 60 degrees of latitude.
 *
 * <p>{@link GieTolerance#GRS80_DEG} exists solely so that a tolerance written as
 * {@code "1 deg"} can be turned into metres, which is the one place PROJ itself uses a constant
 * scale. This test asserts that the literal lives there and nowhere else in {@code src/main},
 * along with {@code 111195}, the other constant people reach for.
 *
 * <p>If this test fails, the fix is almost never to add an exemption. It is to obtain the
 * distance from {@link GieComparator}, which solves the geodesic.
 */
public class GieSourceHygieneTest {

    /** Literals that must not appear in main sources outside the permitted file. */
    private static final List<String> BANNED = Collections.unmodifiableList(
            Arrays.asList("111319.4908", "111195"));

    /** The one file allowed to contain them. */
    private static final String PERMITTED_FILE = "GieTolerance.java";

    @Test
    public void constantDegreeScalesAreConfinedToTheToleranceParser() throws IOException {
        final File root = findMainSourceRoot();
        final List<File> sources = new ArrayList<File>();
        collectJavaFiles(root, sources);

        assertTrue("expected to walk a substantial source tree, found " + sources.size()
                + " files under " + root, sources.size() > 50);

        final List<String> offences = new ArrayList<String>();
        for (final File f : sources) {
            if (PERMITTED_FILE.equals(f.getName())) {
                continue;
            }
            final String text = read(f);
            for (final String banned : BANNED) {
                if (text.contains(banned)) {
                    offences.add(banned + " in " + relative(root, f));
                }
            }
        }

        assertEquals("A constant degrees-to-metres scale escaped " + PERMITTED_FILE
                        + ". Use GieComparator, which solves the geodesic, instead: " + offences,
                Collections.<String>emptyList(), offences);
    }

    /** And a positive control: the constant really is where we say it is. */
    @Test
    public void theToleranceParserDoesContainTheConstant() throws IOException {
        final File root = findMainSourceRoot();
        final File f = new File(root, "org/locationtech/proj4j/gie/" + PERMITTED_FILE);
        assertTrue(f + " should exist", f.isFile());
        assertTrue("GieTolerance.java should declare 111319.4908",
                read(f).contains("111319.4908"));
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Locates {@code core/src/main/java} by walking up from the working directory, so that the
     * test works whether it is run from the module directory (Maven surefire) or the reactor
     * root (an IDE).
     */
    private static File findMainSourceRoot() {
        File dir = new File(System.getProperty("user.dir", ".")).getAbsoluteFile();
        while (dir != null) {
            final File direct = new File(dir, "src/main/java");
            if (new File(direct, "org/locationtech/proj4j/gie").isDirectory()) {
                return direct;
            }
            final File nested = new File(dir, "core/src/main/java");
            if (new File(nested, "org/locationtech/proj4j/gie").isDirectory()) {
                return nested;
            }
            dir = dir.getParentFile();
        }
        fail("could not locate core/src/main/java from user.dir="
                + System.getProperty("user.dir"));
        throw new AssertionError("unreachable");
    }

    private static void collectJavaFiles(final File dir, final List<File> out) {
        final File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        Arrays.sort(children);
        for (final File child : children) {
            if (child.isDirectory()) {
                collectJavaFiles(child, out);
            } else if (child.getName().endsWith(".java")) {
                out.add(child);
            }
        }
    }

    private static String read(final File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), Charset.forName("UTF-8"));
    }

    private static String relative(final File root, final File f) {
        final String r = root.getAbsolutePath();
        final String p = f.getAbsolutePath();
        return p.startsWith(r) ? p.substring(r.length() + 1) : p;
    }
}
