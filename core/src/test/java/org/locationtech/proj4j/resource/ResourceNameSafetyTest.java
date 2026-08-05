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
package org.locationtech.proj4j.resource;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The resource-name guard, after it was relaxed to permit interior path segments.
 *
 * <h2>Why this test exists in this shape</h2>
 *
 * <p>{@link ResourceNames#isSafe} used to reject <em>any</em> name containing {@code /}. That was a
 * guard doing two jobs at once, and only one of them was security: it also, incidentally, refused
 * {@code tests/us_noaa_nadcon5_conus.tif}, which is an ordinary PROJ spelling. Relaxing it is
 * exactly the kind of change that can quietly stop refusing things it was still meant to refuse — so
 * every hostile name below asserts <strong>which rule</strong> refused it, not merely that something
 * did.
 *
 * <p>That distinction is the whole point. {@code subdir/conus} is now a <em>legal</em> name that
 * happens not to exist, and a test asserting only "it does not resolve" would pass for both a legal
 * name and a refused one — it would keep passing if the guard were deleted outright. The
 * {@link #namesThatAreLegalNowAndWereNotBefore()} case below is the positive control that separates
 * the two: those names must be accepted by the rule, so a hostile name being rejected is a statement
 * about the rule rather than about the filesystem.
 */
public class ResourceNameSafetyTest {

    private static final Charset ASCII = Charset.forName("US-ASCII");

    /**
     * Every hostile name, with the rule that must refuse it. Asserting the rule rather than a boolean
     * is what makes the test able to notice a name being refused for the wrong reason — a name that
     * starts being caught by, say, {@code WHITESPACE} instead of {@code DOT_SEGMENT} has had its real
     * check removed and replaced by an accident.
     */
    private static Map<String, ResourceNames.Rule> hostileNames() {
        Map<String, ResourceNames.Rule> m = new LinkedHashMap<String, ResourceNames.Rule>();

        // --- traversal, the original threat ---------------------------------------------------
        m.put("../../../etc/passwd", ResourceNames.Rule.DOT_SEGMENT);
        m.put("tests/../../etc/passwd", ResourceNames.Rule.DOT_SEGMENT);
        m.put("tests/..", ResourceNames.Rule.DOT_SEGMENT);
        m.put("..", ResourceNames.Rule.DOT_SEGMENT);
        m.put("../", ResourceNames.Rule.DOT_SEGMENT);
        // A "." segment cannot reach anywhere a plain name cannot, so its only use is to vary the
        // spelling of a name past a check that compares strings.
        m.put(".", ResourceNames.Rule.DOT_SEGMENT);
        m.put("a/./b", ResourceNames.Rule.DOT_SEGMENT);
        m.put("./conus", ResourceNames.Rule.DOT_SEGMENT);

        // --- absolute: a leading separator addresses a root that is not the resolver's ----------
        m.put("/etc/passwd", ResourceNames.Rule.ABSOLUTE);
        m.put("/tests/foo.tif", ResourceNames.Rule.ABSOLUTE);
        m.put("\\tests\\foo.tif", ResourceNames.Rule.ABSOLUTE);
        m.put("/", ResourceNames.Rule.ABSOLUTE);

        // --- the backslash is never a separator here --------------------------------------------
        m.put("..\\..\\windows\\win.ini", ResourceNames.Rule.BACKSLASH);
        m.put("tests\\foo.tif", ResourceNames.Rule.BACKSLASH);

        // --- empty segments: "a//b" and a trailing slash -----------------------------------------
        m.put("a//b", ResourceNames.Rule.EMPTY_SEGMENT);
        m.put("tests/", ResourceNames.Rule.EMPTY_SEGMENT);
        m.put("tests//foo.tif", ResourceNames.Rule.EMPTY_SEGMENT);

        // --- whitespace and control characters ---------------------------------------------------
        // A proj string is whitespace-delimited, so a name with a space cannot have arrived from one
        // intact; and a NUL is the classic path-truncation trick.
        m.put("dir with space/myconus", ResourceNames.Rule.WHITESPACE);
        m.put("conus ", ResourceNames.Rule.WHITESPACE);
        m.put("con\tus", ResourceNames.Rule.WHITESPACE);
        m.put("conus\n", ResourceNames.Rule.WHITESPACE);
        m.put("conus\u0000.tif", ResourceNames.Rule.WHITESPACE);

        // --- encodings, so the name cannot mean one thing here and another downstream -------------
        // %2e%2e%2f is "../". It contains no '.' and no '/', so the dot-segment and absolute rules
        // cannot see it; whether it is dangerous depends entirely on whether some later layer
        // decodes it, which is precisely why it is refused rather than reasoned about.
        m.put("%2e%2e%2fetc/passwd", ResourceNames.Rule.PERCENT);
        m.put("%2E%2E/passwd", ResourceNames.Rule.PERCENT);
        m.put("tests%2f..%2fpasswd", ResourceNames.Rule.PERCENT);

        // --- drive letters and URL schemes ---------------------------------------------------------
        m.put("C:/windows/win.ini", ResourceNames.Rule.COLON);
        m.put("file:///etc/passwd", ResourceNames.Rule.COLON);
        m.put("http://example.invalid/grid.tif", ResourceNames.Rule.COLON);

        // --- degenerate --------------------------------------------------------------------------
        m.put("", ResourceNames.Rule.EMPTY);
        return m;
    }

    @Test
    public void everyHostileNameIsRefusedByANamedRule() {
        for (Map.Entry<String, ResourceNames.Rule> e : hostileNames().entrySet()) {
            ResourceNames.Rule rule = ResourceNames.violation(e.getKey());
            assertNotNull("'" + display(e.getKey()) + "' must be refused, and was not", rule);
            assertEquals("'" + display(e.getKey()) + "' must be refused by " + e.getValue()
                    + " (" + e.getValue().description() + "), not by " + rule,
                    e.getValue(), rule);
        }
    }

    @Test
    public void aNullNameIsRefusedRatherThanThrowing() {
        assertSame(ResourceNames.Rule.NULL, ResourceNames.violation(null));
    }

    /**
     * The positive control for the whole file. If these were also refused, every assertion above
     * would be satisfied by a guard that refuses everything, which is indistinguishable from a guard
     * that works and useless as evidence.
     */
    @Test
    public void namesThatAreLegalNowAndWereNotBefore() {
        String[] legal = {
                "conus",
                "ntv1_can.dat",
                "tests/foo.tif",
                "tests/tinshift_simplified_kkj_etrs.json",
                "tests/nkgrf03vel_realigned_xy_extract.ct2",
                "tests/us_noaa_nadcon5_nad83_2007_nad83_2011_conus_extract.tif",
                "a/b/c/d.tif",
                // A leading dot is a hidden file, not a dot segment; "..foo" is not "..".
                ".hidden",
                "..foo",
                "foo..bar",
                "au_ga_AUSGeoid09_V1.01.tif",
                "nz-linz-grid.gsb",
        };
        for (String name : legal) {
            assertNull("'" + name + "' is a legitimate resource name and must be accepted, but "
                    + ResourceNames.violation(name), ResourceNames.violation(name));
        }
    }

    // ---------------------------------------------------------------- the rule, applied end to end

    /**
     * The classpath resolver honours the rule, and the relaxation actually works: a resource in a
     * sub-directory of the prefix resolves, and every hostile spelling of a path to it does not.
     */
    @Test
    public void theClasspathResolverAppliesTheRule() throws IOException {
        ClasspathResourceResolver r = new ClasspathResourceResolver(
                ResourceNameSafetyTest.class.getClassLoader(), "proj4/nad");

        assertNotNull("a bare name still resolves", r.resolve("ntv1_can.dat"));
        for (String hostile : hostileNames().keySet()) {
            assertNull("'" + display(hostile) + "' must not resolve", r.resolve(hostile));
        }
        // ... and the guard, not mere absence, is what refused them: a legal-but-absent name takes
        // the same path and also yields null, so "null" alone proves nothing.
        assertNull(r.resolve("no/such/grid.tif"));
    }

    @Test
    public void theDirectoryResolverAppliesTheRuleAndResolvesASubdirectory() throws IOException {
        Path root = Files.createTempDirectory("proj4j-name-rule-root");
        Path outside = Files.createTempDirectory("proj4j-name-rule-outside");
        try {
            Files.createDirectory(root.resolve("tests"));
            Files.write(root.resolve("tests").resolve("foo.tif"), "grid".getBytes(ASCII));
            Files.write(root.resolve("conus"), "grid".getBytes(ASCII));
            Files.write(outside.resolve("secret"), "secret".getBytes(ASCII));

            DirectoryResourceResolver r = new DirectoryResourceResolver(root);

            // The unlock, stated positively: this is the spelling the corpus uses.
            ResourceHandle handle = r.resolve("tests/foo.tif");
            assertNotNull("a file in a sub-directory of the root must resolve", handle);
            assertEquals("tests/foo.tif", handle.name());
            assertNotNull(r.resolve("conus"));

            for (String hostile : hostileNames().keySet()) {
                assertNull("'" + display(hostile) + "' must not resolve", r.resolve(hostile));
            }
            // The traversal that actually points at a real file outside the root -- the one case
            // where "it returned null" could otherwise be explained by the file not existing.
            assertTrue(Files.isRegularFile(outside.resolve("secret")));
            assertNull("a traversal to a file that really is there must still be refused",
                    r.resolve("../" + outside.getFileName() + "/secret"));
            assertNull(r.resolve("tests/../../" + outside.getFileName() + "/secret"));
        } finally {
            deleteRecursively(root);
            deleteRecursively(outside);
        }
    }

    /**
     * {@code listAvailable()} and {@code resolve()} are the same rule seen from two sides. A name the
     * inventory publishes must resolve, and a file the inventory omits must be one the rule refuses —
     * otherwise {@code Proj.availableGrids()} under-reports what is installed.
     */
    @Test
    public void theDirectoryInventoryAndTheRuleAgree() throws IOException {
        Path root = Files.createTempDirectory("proj4j-name-rule-listing");
        try {
            Files.createDirectory(root.resolve("tests"));
            Files.createDirectory(root.resolve("dir with space"));
            Files.write(root.resolve("conus"), "grid".getBytes(ASCII));
            Files.write(root.resolve("tests").resolve("foo.tif"), "grid".getBytes(ASCII));
            Files.write(root.resolve("dir with space").resolve("myconus"), "grid".getBytes(ASCII));

            DirectoryResourceResolver r = new DirectoryResourceResolver(root);
            Collection<String> listed = r.listAvailable();

            assertTrue("a sub-directory file must be listed under the name that resolves it: " + listed,
                    listed.contains("tests/foo.tif"));
            assertTrue(listed.contains("conus"));
            assertTrue("a file only reachable through an unsafe path is not part of the inventory: "
                    + listed, !listed.contains("dir with space/myconus"));
            assertEquals("nothing else is in the tree", 2, listed.size());

            for (String name : listed) {
                assertNull(name + " is listed, so the rule must accept it",
                        ResourceNames.violation(name));
                assertNotNull(name + " is listed, so it must resolve", r.resolve(name));
            }
        } finally {
            deleteRecursively(root);
        }
    }

    // ---------------------------------------------------------------------------------- helpers

    /** Renders control characters so a failure message is readable. */
    private static String display(String s) {
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < ' ' || c == 0x7f) {
                out.append(String.format("\\u%04x", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(root);
        try {
            for (Path p : stream) {
                if (Files.isDirectory(p)) {
                    deleteRecursively(p);
                } else {
                    Files.deleteIfExists(p);
                }
            }
        } finally {
            stream.close();
        }
        Files.deleteIfExists(root);
    }
}
