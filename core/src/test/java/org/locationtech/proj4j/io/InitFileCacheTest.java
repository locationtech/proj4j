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
package org.locationtech.proj4j.io;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StreamTokenizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.util.Pair;

/**
 * {@link InitFileCache} against the streaming scan it replaced.
 *
 * <h2>Why the controls are shaped the way they are</h2>
 *
 * <p>The hazard this change introduces is not a crash, it is a <b>silently wrong CRS</b>. Two
 * different comparisons live one line apart in the code it replaces: the resource name is the
 * authority lowercased with {@code Locale.ROOT}, and the code inside the file was matched with
 * case-sensitive {@code String.equals}. Fold the second and {@code EPSG:4326} starts answering for
 * something else, in a library that runs per row with untrusted input.
 *
 * <p>So the load-bearing test here is <b>exhaustive</b>: every code in every shipped dictionary,
 * through the cache, compared against an independently built reference of the same file, plus a
 * stride sample compared against the genuine legacy {@code streamParametersFromFile}. And because
 * an exhaustive comparison that cannot fail is worth nothing,
 * {@link #theExhaustiveComparisonDetectsAWrongKey()} runs the same comparison against a
 * deliberately case-folded index and requires it to <b>report violations</b> - naming the codes.
 *
 * <p>The other half of the discipline: every bound is shown <b>rejecting</b> something and shown
 * <b>accepting</b> legitimate input. A cache that refuses everything passes all the hostile tests
 * and breaks the library.
 */
public class InitFileCacheTest {

    /** The dictionaries {@code proj4j-epsg} ships, by their lowercased resource-name suffix. */
    private static final String[] SHIPPED = {"epsg", "esri", "world", "nad83", "nad27"};

    /** Stride for the sample compared against the genuine legacy scan, which is O(file) per call. */
    private static final int LEGACY_SAMPLE_STRIDE = 113;

    // ==========================================================================================
    // The load-bearing equivalence, and the proof that it can fail
    // ==========================================================================================

    /**
     * Every code in every shipped dictionary resolves through the cache to exactly what the file
     * says, and a stride sample resolves to exactly what the legacy streaming scan returns.
     */
    @Test
    public void everyCodeInEveryShippedDictionaryResolvesAsTheStreamingScanDid() throws Exception {
        InitFileCache cache = new InitFileCache(InitFileCache.DEFAULT_MAX_BYTES);
        Proj4FileReader reader = new Proj4FileReader(cache);

        int totalCodes = 0;
        int legacyChecked = 0;
        List<String> violations = new ArrayList<String>();

        for (int f = 0; f < SHIPPED.length; f++) {
            String file = SHIPPED[f];
            LinkedHashMap<String, String[]> reference = referenceForward(file);
            assertFalse("reference parse of proj4/nad/" + file + " found no entries, so every "
                    + "comparison against it below is vacuous", reference.isEmpty());

            int i = 0;
            for (Map.Entry<String, String[]> e : reference.entrySet()) {
                String code = e.getKey();
                String[] expected = e.getValue();
                String[] actual = reader.readParametersFromFile(file, code);
                if (!Arrays.equals(expected, actual)) {
                    violations.add(file + ":" + code + " expected " + Arrays.toString(expected)
                            + " got " + Arrays.toString(actual));
                }
                totalCodes++;

                if (i % LEGACY_SAMPLE_STRIDE == 0) {
                    String[] legacy = reader.streamParametersFromFile(file, code);
                    if (!Arrays.equals(legacy, actual)) {
                        violations.add("LEGACY " + file + ":" + code + " streaming "
                                + Arrays.toString(legacy) + " cached " + Arrays.toString(actual));
                    }
                    legacyChecked++;
                }
                i++;
            }
        }

        // Coverage floors. A comparison that ran over nothing reports clean.
        assertTrue("only " + totalCodes + " codes were compared; the shipped dictionaries carry "
                + "thousands, so the walk did not reach them", totalCodes > 5000);
        assertTrue("only " + legacyChecked + " codes were compared against the genuine legacy "
                + "scan", legacyChecked >= 40);
        assertTrue(violations.size() + " of " + totalCodes + " codes resolved differently through "
                + "the cache: " + head(violations), violations.isEmpty());
    }

    /**
     * The positive control for the test above. Run the identical comparison against an index whose
     * <b>code</b> key has been case-folded - the one plausible way to get this wrong - and require
     * it to report violations, naming them. If it reports clean, the exhaustive test proves nothing.
     */
    @Test
    public void theExhaustiveComparisonDetectsAWrongKey() throws Exception {
        Proj4FileReader reader =
                new Proj4FileReader(new InitFileCache(InitFileCache.DEFAULT_MAX_BYTES));

        int wrongKeyHits = 0;
        int filesWithLetterCodes = 0;
        for (int f = 0; f < SHIPPED.length; f++) {
            String file = SHIPPED[f];
            LinkedHashMap<String, String[]> reference = referenceForward(file);
            assertFalse("reference parse of " + file + " is empty", reference.isEmpty());

            // The wrong key: fold the code. Exactly the mistake the two comparisons - one
            // Locale.ROOT-lowercased, one case-sensitive - sitting fifty lines apart invite.
            Map<String, String[]> folded = new java.util.HashMap<String, String[]>();
            for (Map.Entry<String, String[]> e : reference.entrySet()) {
                String k = e.getKey().toLowerCase(java.util.Locale.ROOT);
                if (!folded.containsKey(k)) {
                    folded.put(k, e.getValue());
                }
            }

            boolean sawLetterCode = false;
            for (Map.Entry<String, String[]> e : reference.entrySet()) {
                String shouted = e.getKey().toUpperCase(java.util.Locale.ROOT);
                if (shouted.equals(e.getKey())) {
                    continue;
                }
                sawLetterCode = true;
                // The folded index ANSWERS this lookup ...
                assertNotNull("the case-folded index did not answer " + file + ":" + shouted
                        + ", so it does not model the failure mode",
                        folded.get(shouted.toLowerCase(java.util.Locale.ROOT)));
                wrongKeyHits++;
                // ... and production must MISS it.
                assertNull(file + ":" + shouted + " must not resolve - the code comparison is "
                        + "case-sensitive and folding it returns the WRONG CRS",
                        reader.readParametersFromFile(file, shouted));
                assertNull("the legacy scan agrees about " + file + ":" + shouted,
                        reader.streamParametersFromFile(file, shouted));
            }
            if (sawLetterCode) {
                filesWithLetterCodes++;
            }
        }
        assertTrue("no shipped dictionary has a code containing a letter, so this control cannot "
                + "distinguish a case-folded index from a case-sensitive one and every "
                + "case-sensitivity claim here is unproven", wrongKeyHits > 0);
        assertTrue("only " + filesWithLetterCodes + " dictionaries carried a letter-bearing code",
                filesWithLetterCodes >= 1);
    }

    // ==========================================================================================
    // Key semantics: the authority folds, the code does not
    // ==========================================================================================

    /** {@code epsg:4326} and {@code EPSG:4326} behave exactly as they did: same definition. */
    @Test
    public void theAuthorityIsCaseInsensitiveAndTheCodeIsNot() throws Exception {
        Proj4FileReader reader = new Proj4FileReader(new InitFileCache(InitFileCache.DEFAULT_MAX_BYTES));

        String[] upper = reader.readParametersFromFile("EPSG", "4326");
        String[] lower = reader.readParametersFromFile("epsg", "4326");
        String[] mixed = reader.readParametersFromFile("EpSg", "4326");
        assertNotNull("EPSG:4326 must resolve", upper);
        assertTrue("EPSG:4326 came back empty", upper.length > 0);
        assertArrayEquals2("epsg vs EPSG", upper, lower);
        assertArrayEquals2("epsg vs EpSg", upper, mixed);

        // ... and through the factory, which is how a consumer meets it.
        CRSFactory factory = new CRSFactory();
        CoordinateReferenceSystem a = factory.createFromName("EPSG:4326");
        CoordinateReferenceSystem b = factory.createFromName("epsg:4326");
        assertArrayEquals2("CRSFactory EPSG vs epsg", a.getParameters(), b.getParameters());

        // The code is NOT folded and must not become so. world:palestine is real; PALESTINE is not.
        assertNotNull("world:palestine must resolve",
                reader.readParametersFromFile("world", "palestine"));
        assertNull("world:PALESTINE must NOT resolve; folding the code returns the wrong CRS",
                reader.readParametersFromFile("world", "PALESTINE"));
        // ... and the legacy scan agrees, so this is the pre-existing contract, not a new one.
        assertNull("the legacy scan also misses world:PALESTINE",
                reader.streamParametersFromFile("world", "PALESTINE"));

        // Trailing whitespace was never trimmed and still is not.
        assertNull("EPSG:'4326 ' must not resolve", reader.readParametersFromFile("EPSG", "4326 "));
    }

    /**
     * The authority folds with {@code Locale.ROOT}, and under a Turkish default locale that is the
     * difference between {@code ESRI} resolving and every ESRI-authority definition in the library
     * becoming unreachable.
     *
     * <p>{@code "ESRI".toLowerCase()} under {@code tr-TR} maps {@code 'I'} to dotless {@code 'ı'}
     * (U+0131), so the resource name becomes {@code proj4/nad/esrı} and never resolves. The claim
     * that this is handled lived only in a javadoc comment until this test; the comment is not an
     * instrument.
     *
     * <p>The control is inside the test: it first asserts that the default-locale rule
     * <b>does</b> produce a different string here - otherwise the locale was not actually installed
     * and the assertions below hold for a reason that has nothing to do with the code.
     */
    @Test
    public void theAuthorityFoldsUnderLocaleRootNotTheDefaultLocale() throws Exception {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));

            // Control: prove the hostile locale is really in force and really is hostile. If these
            // are equal, this JVM cannot distinguish the two foldings and the test proves nothing.
            assertFalse("the tr-TR default locale did not change how 'ESRI' lowercases, so this "
                    + "test cannot detect a default-locale fold and is vacuous",
                    "ESRI".toLowerCase().equals("ESRI".toLowerCase(Locale.ROOT)));
            assertEquals("esri", "ESRI".toLowerCase(Locale.ROOT));

            Proj4FileReader reader =
                    new Proj4FileReader(new InitFileCache(InitFileCache.DEFAULT_MAX_BYTES));

            // An authority whose lowercasing is locale-sensitive must still resolve.
            String[] esri = reader.readParametersFromFile("ESRI", "37001");
            assertNotNull("ESRI:37001 became unreachable under a tr-TR default locale: the "
                    + "authority is being folded with the default locale, not Locale.ROOT", esri);
            assertArrayEquals2("ESRI under tr-TR vs esri", esri,
                    reader.readParametersFromFile("esri", "37001"));
            assertArrayEquals2("the cached path and the legacy scan agree under tr-TR", esri,
                    reader.streamParametersFromFile("esri", "37001"));

            // And the factory, which is how a consumer meets it.
            assertNotNull("CRSFactory lost ESRI:37001 under a tr-TR default locale",
                    new CRSFactory().createFromName("ESRI:37001"));
        } finally {
            Locale.setDefault(previous);
        }
    }

    // ==========================================================================================
    // Misses must not poison
    // ==========================================================================================

    /**
     * A bogus authority fails, fails the same way on the second call, and leaves every real
     * dictionary answering correctly. The cached failure is the point - one classpath probe per
     * bogus authority instead of one per row - so both halves matter.
     */
    @Test
    public void aBogusAuthorityFailsIdenticallyTwiceAndPoisonsNothing() throws Exception {
        InitFileCache cache = new InitFileCache(InitFileCache.DEFAULT_MAX_BYTES);
        Proj4FileReader reader = new Proj4FileReader(cache);

        String[] before = reader.readParametersFromFile("EPSG", "4326");
        assertNotNull(before);

        String first = expectMissingResource(reader, "bogus", "1");
        String second = expectMissingResource(reader, "bogus", "1");
        assertEquals("the replayed failure must be the same failure", first, second);
        assertEquals("the message must still name the resource it could not open",
                "Unable to access CRS file: proj4/nad/bogus", first);

        // The failure was cached, not re-probed.
        assertEquals("a repeated bogus authority must not re-probe the classpath",
                1L, cache.missCount() - 1L /* the epsg load above */);

        assertArrayEquals2("EPSG:4326 after a bogus lookup", before,
                reader.readParametersFromFile("EPSG", "4326"));
    }

    /** An unknown code inside a known file returns null and leaves the file usable. */
    @Test
    public void anUnknownCodeReturnsNullAndPoisonsNothing() throws Exception {
        Proj4FileReader reader = new Proj4FileReader(new InitFileCache(InitFileCache.DEFAULT_MAX_BYTES));
        String[] before = reader.readParametersFromFile("EPSG", "4326");

        assertNull("epsg:999999999 must not resolve",
                reader.readParametersFromFile("EPSG", "999999999"));
        assertNull("the legacy scan agrees", reader.streamParametersFromFile("epsg", "999999999"));

        assertArrayEquals2("EPSG:4326 after a missing-code lookup", before,
                reader.readParametersFromFile("EPSG", "4326"));
    }

    /** {@code CRSFactory} still reports both flavours of miss the way it always has. */
    @Test
    public void theFactoryStillReportsBothFlavoursOfMiss() {
        CRSFactory factory = new CRSFactory();
        try {
            factory.createFromName("bogus:1");
            fail("bogus:1 must not resolve to a CRS");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().startsWith("Unable to access CRS file:"));
        }
        try {
            factory.createFromName("EPSG:999999999");
            fail("EPSG:999999999 must not resolve to a CRS");
        } catch (RuntimeException expected) {
            assertTrue("expected an unknown-code report, got " + expected,
                    expected.getMessage().contains("999999999"));
        }
        // ... and the factory still works afterwards.
        assertNotNull(factory.createFromName("EPSG:4326"));
    }

    // ==========================================================================================
    // The shared array. This is the one that turns a cache into a wrong answer.
    // ==========================================================================================

    /**
     * {@code readParametersFromFile} hands out a copy, and the test proves it can tell the
     * difference: corrupting the <b>cached</b> array is shown to be observable, which is exactly
     * what would happen without the copy.
     */
    @Test
    public void theReturnedArrayIsACopyAndCorruptionWouldOtherwiseBeVisible() throws Exception {
        InitFileCache cache = new InitFileCache(InitFileCache.DEFAULT_MAX_BYTES);
        Proj4FileReader reader = new Proj4FileReader(cache);

        String[] first = reader.readParametersFromFile("EPSG", "4326");
        String[] second = reader.readParametersFromFile("EPSG", "4326");
        assertFalse("two lookups returned the SAME array object; a caller mutating "
                + "CoordinateReferenceSystem.getParameters() would corrupt the dictionary "
                + "process-wide", first == second);

        String original = first[0];
        first[0] = "+proj=CORRUPTED";
        String[] third = reader.readParametersFromFile("EPSG", "4326");
        assertEquals("mutating a returned array changed a later lookup", original, third[0]);

        // Positive control: the assertion above is only meaningful if corrupting the CACHED array
        // really would be visible. Prove it on this throwaway cache, then confirm the throwaway is
        // genuinely separate from the shared one.
        InitFileCache victim = new InitFileCache(InitFileCache.DEFAULT_MAX_BYTES);
        Proj4FileReader victimReader = new Proj4FileReader(victim);
        InitFileCache.Dictionary dict = victim.get("epsg");
        String[] cached = dict.parameters("4326");
        assertNotNull(cached);
        cached[0] = "+proj=CORRUPTED";
        assertEquals("corrupting the cached array is NOT observable, so the copy assertion above "
                + "is vacuous - the instrument cannot detect the failure it guards against",
                "+proj=CORRUPTED", victimReader.readParametersFromFile("EPSG", "4326")[0]);

        assertEquals("the throwaway cache leaked into the one under test", original,
                reader.readParametersFromFile("EPSG", "4326")[0]);
    }

    // ==========================================================================================
    // Fixtures: duplicates and a malformed entry
    // ==========================================================================================

    /**
     * First match wins in both directions, and the reverse index sees entries the forward index
     * dropped. Built from the forward map instead, the reverse lookup below would answer {@code B}.
     */
    @Test
    public void duplicateEntriesKeepTheFirstForwardAndTheFirstInFileOrderInReverse()
            throws Exception {
        Proj4FileReader reader = new Proj4FileReader(new InitFileCache(InitFileCache.DEFAULT_MAX_BYTES));

        String[] a = reader.readParametersFromFile("proj4jtestdupes", "A");
        assertNotNull("the fixture proj4/nad/proj4jtestdupes is not on the test classpath", a);
        assertEquals("[+proj=longlat, +datum=WGS84]", Arrays.toString(a));
        assertArrayEquals2("cached vs streaming for the duplicated code", a,
                reader.streamParametersFromFile("proj4jtestdupes", "A"));

        String[] merc = {"+proj=merc", "+datum=WGS84"};
        // The whole-file scan reached <A>'s SECOND parameter set before <B>'s identical one.
        assertEquals("the reverse index must be fed from the entry stream, not from the forward "
                + "map - otherwise A's second parameter set is invisible and this answers B",
                "A", reverseIn("proj4jtestdupes", merc));
    }

    /**
     * A malformed entry is a deferred failure: names before it still resolve, names that would
     * have required scanning past it still throw - and the cached and streaming paths agree on
     * both, which is the only way to know the semantics were preserved rather than guessed.
     */
    @Test
    public void aMalformedEntryFailsExactlyWhereTheStreamingScanFailed() throws Exception {
        Proj4FileReader reader = new Proj4FileReader(new InitFileCache(InitFileCache.DEFAULT_MAX_BYTES));

        String[] good = reader.readParametersFromFile("proj4jtestmalformed", "good");
        assertNotNull("the fixture proj4/nad/proj4jtestmalformed is not on the test classpath", good);
        assertArrayEquals2("an entry before the malformed one must resolve, as it always did",
                reader.streamParametersFromFile("proj4jtestmalformed", "good"), good);

        String cachedFailure = expectIoFailure(reader, "proj4jtestmalformed", "after");
        String streamingFailure;
        try {
            reader.streamParametersFromFile("proj4jtestmalformed", "after");
            streamingFailure = null;
        } catch (IOException e) {
            streamingFailure = e.getMessage();
        }
        assertNotNull("the streaming scan did not fail on the malformed fixture, so the fixture "
                + "does not exercise the deferred-failure path at all", streamingFailure);
        assertEquals("the cached path must replay the streaming path's failure verbatim",
                streamingFailure, cachedFailure);

        // Replayed identically, and the good entry still resolves after the failure.
        assertEquals(cachedFailure, expectIoFailure(reader, "proj4jtestmalformed", "after"));
        assertNotNull(reader.readParametersFromFile("proj4jtestmalformed", "good"));
    }

    // ==========================================================================================
    // The reverse index
    // ==========================================================================================

    /**
     * <b>Every</b> entry of <b>every</b> shipped dictionary, reverse-resolved through the cache's
     * index and compared against an independently built reverse map of the same file.
     *
     * <p>The sampled test below is the one that runs against the genuine legacy scan, because that
     * scan is O(file) per call and 9,000 of them would not be a unit test. This one closes the gap:
     * the two together say "the index agrees with a from-scratch reading of the file everywhere,
     * and a from-scratch reading agrees with the legacy scan on a spread sample".
     */
    @Test
    public void everyEntryReverseResolvesAsTheWholeFileScanWould() throws Exception {
        InitFileCache cache = new InitFileCache(InitFileCache.DEFAULT_MAX_BYTES);
        int compared = 0;
        int nonNull = 0;
        List<String> violations = new ArrayList<String>();

        for (int f = 0; f < SHIPPED.length; f++) {
            String file = SHIPPED[f];
            // The reference: first parameter set wins, in file order, over the WHOLE entry stream -
            // duplicated codes included, which is what the old whole-file scan saw.
            LinkedHashMap<List<String>, String> referenceReverse = referenceReverse(file);
            assertFalse("reference reverse parse of " + file + " is empty",
                    referenceReverse.isEmpty());

            InitFileCache.Dictionary dict = cache.get(file);
            for (Map.Entry<List<String>, String> e : referenceReverse.entrySet()) {
                String[] params = e.getKey().toArray(new String[e.getKey().size()]);
                String expected = e.getValue();
                String actual = dict.codeForParameters(params);
                if (!expected.equals(actual)) {
                    violations.add(file + " " + e.getKey() + ": expected " + expected
                            + " got " + actual);
                }
                if (actual != null) {
                    nonNull++;
                }
                compared++;
            }
        }
        assertTrue("only " + compared + " reverse lookups were compared", compared > 5000);
        assertTrue("every reverse lookup returned null", nonNull > compared / 2);
        assertTrue(violations.size() + " of " + compared + " reverse lookups disagreed: "
                + head(violations), violations.isEmpty());
    }

    /** A stride sample of the reverse lookup, against the genuine legacy whole-file scan. */
    @Test
    public void theReverseLookupAgreesWithTheStreamingScan() throws Exception {
        Proj4FileReader reader = new Proj4FileReader(new InitFileCache(InitFileCache.DEFAULT_MAX_BYTES));
        LinkedHashMap<String, String[]> reference = referenceForward("epsg");

        int checked = 0;
        int nonNull = 0;
        List<String> violations = new ArrayList<String>();
        int i = 0;
        for (Map.Entry<String, String[]> e : reference.entrySet()) {
            if (i++ % LEGACY_SAMPLE_STRIDE != 0) {
                continue;
            }
            String[] params = e.getValue();
            String cached = reader.readEpsgCodeFromFile(params);
            String streaming = reader.streamEpsgCodeFromFile(params);
            if (cached == null ? streaming != null : !cached.equals(streaming)) {
                violations.add(e.getKey() + ": cached=" + cached + " streaming=" + streaming);
            }
            if (cached != null) {
                nonNull++;
            }
            checked++;
        }
        assertTrue("only " + checked + " reverse lookups ran", checked >= 40);
        assertTrue("every sampled reverse lookup returned null, so the comparison is vacuous",
                nonNull > checked / 2);
        assertTrue(violations.size() + " reverse lookups disagreed: " + head(violations),
                violations.isEmpty());

        // A parameter set that is in no dictionary entry must still miss, both ways.
        String[] nonsense = {"+proj=not_a_projection", "+lat_0=1234"};
        assertNull(reader.readEpsgCodeFromFile(nonsense));
        assertNull(reader.streamEpsgCodeFromFile(nonsense));
    }

    // ==========================================================================================
    // The bound: shown rejecting, and shown accepting
    // ==========================================================================================

    /** ACCEPTING: the default budget holds every shipped dictionary with room to spare. */
    @Test
    public void theDefaultBudgetHoldsEveryShippedDictionaryWithoutEvicting() throws Exception {
        InitFileCache cache = new InitFileCache(InitFileCache.DEFAULT_MAX_BYTES);
        Proj4FileReader reader = new Proj4FileReader(cache);
        for (int i = 0; i < SHIPPED.length; i++) {
            assertNotNull(cache.get(SHIPPED[i]));
        }
        assertEquals("all five dictionaries must be resident", SHIPPED.length, cache.size());
        assertEquals("nothing may be evicted at the default budget", 0L, cache.evictionCount());
        assertTrue("the five dictionaries account for " + cache.bytes() + " bytes, which is not "
                + "below the " + InitFileCache.DEFAULT_MAX_BYTES + "-byte default budget",
                cache.bytes() < InitFileCache.DEFAULT_MAX_BYTES);
        // ... and they still answer.
        assertNotNull(reader.readParametersFromFile("EPSG", "4326"));
        assertNotNull(reader.readParametersFromFile("NAD27", "101"));
    }

    /** REJECTING: a budget that fits one small dictionary evicts when a second arrives. */
    @Test
    public void aTightBudgetEvictsAndStillAnswersCorrectly() throws Exception {
        InitFileCache sizer = new InitFileCache(InitFileCache.DEFAULT_MAX_BYTES);
        long nad27 = sizer.get("nad27").bytes;
        long nad83 = sizer.get("nad83").bytes;
        assertTrue("the sizer measured nad27 at 0 bytes", nad27 > 0);
        assertTrue("the sizer measured nad83 at 0 bytes", nad83 > 0);

        InitFileCache tight = new InitFileCache(Math.max(nad27, nad83));
        Proj4FileReader reader = new Proj4FileReader(tight);
        String[] a = reader.readParametersFromFile("NAD27", "101");
        String[] b = reader.readParametersFromFile("NAD83", "101");
        assertNotNull(a);
        assertNotNull(b);
        assertTrue("a budget of " + tight.maxBytes() + " bytes held both nad27 (" + nad27 + ") and "
                + "nad83 (" + nad83 + ") without evicting, so the bound did nothing",
                tight.evictionCount() > 0);
        assertTrue("more than one dictionary is resident under a one-dictionary budget",
                tight.size() <= 1);

        // ACCEPTING: eviction costs a re-parse, never an answer.
        assertArrayEquals2("NAD27:101 after eviction", a,
                reader.readParametersFromFile("NAD27", "101"));
        assertArrayEquals2("NAD83:101 after eviction", b,
                reader.readParametersFromFile("NAD83", "101"));
    }

    /**
     * REJECTING: the OOM vector. A flood of distinct untrusted authorities is bounded, and the
     * real dictionaries still resolve afterwards.
     */
    @Test
    public void aFloodOfBogusAuthoritiesIsBounded() throws Exception {
        long budget = 8 * InitFileCache.MISS_WEIGHT_BYTES;
        InitFileCache cache = new InitFileCache(budget);
        Proj4FileReader reader = new Proj4FileReader(cache);

        for (int i = 0; i < 500; i++) {
            try {
                reader.readParametersFromFile("bogus_authority_" + i, "1");
                fail("bogus_authority_" + i + " resolved");
            } catch (IllegalStateException expected) {
                // expected
            }
        }
        assertTrue("500 distinct bogus authorities left " + cache.size() + " entries resident "
                + "under an 8-entry budget: the cache is not bounded on the untrusted key",
                cache.size() <= 9);
        assertTrue("nothing was evicted, so nothing was bounded", cache.evictionCount() >= 490);

        // ACCEPTING: a real dictionary still loads and answers after the flood.
        assertNotNull("the flood broke real lookups", reader.readParametersFromFile("EPSG", "4326"));
    }

    /**
     * A dictionary larger than the whole budget is not cached; the reader streams it and still
     * answers correctly. This is the escape hatch that keeps an unusually large user dictionary
     * working instead of refusing it.
     */
    @Test
    public void anOversizedDictionaryFallsBackToStreaming() throws Exception {
        InitFileCache tiny = new InitFileCache(100L);
        Proj4FileReader reader = new Proj4FileReader(tiny);
        assertSame("epsg must be far too large for a 100-byte budget",
                InitFileCache.Dictionary.OVERSIZED, tiny.get("epsg"));
        assertNotNull("the streaming fallback must still answer", reader.readParametersFromFile("EPSG", "4326"));
        assertNotNull(reader.readEpsgCodeFromFile(reader.readParametersFromFile("EPSG", "4326")));
    }

    /** A zero budget disables caching outright - the control arm, and a supported escape hatch. */
    @Test
    public void aZeroBudgetDisablesTheCacheEntirely() throws Exception {
        InitFileCache off = new InitFileCache(0L);
        Proj4FileReader reader = new Proj4FileReader(off);
        assertSame(InitFileCache.Dictionary.OVERSIZED, off.get("epsg"));
        assertEquals("a disabled cache must retain nothing", 0, off.size());
        assertNotNull(reader.readParametersFromFile("EPSG", "4326"));
        // A missing authority must still fail the same way with the cache off.
        assertEquals("Unable to access CRS file: proj4/nad/bogus",
                expectMissingResource(reader, "bogus", "1"));
    }

    // ==========================================================================================
    // Concurrency
    // ==========================================================================================

    /** One parse per file under concurrent demand, and every thread sees the same answer. */
    @Test
    public void concurrentDemandParsesEachFileOnce() throws Exception {
        final InitFileCache cache = new InitFileCache(InitFileCache.DEFAULT_MAX_BYTES);
        final Proj4FileReader reader = new Proj4FileReader(cache);
        final String[] expected = new Proj4FileReader(
                new InitFileCache(InitFileCache.DEFAULT_MAX_BYTES))
                .readParametersFromFile("EPSG", "4326");
        assertNotNull(expected);

        final int threads = 8;
        final int perThread = 200;
        final AtomicInteger mismatches = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<Future<?>>();
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(new Callable<Void>() {
                    @Override
                    public Void call() throws Exception {
                        for (int i = 0; i < perThread; i++) {
                            String[] got = reader.readParametersFromFile("EPSG", "4326");
                            if (!Arrays.equals(expected, got)) {
                                mismatches.incrementAndGet();
                            }
                        }
                        return null;
                    }
                }));
            }
            for (int i = 0; i < futures.size(); i++) {
                futures.get(i).get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals("threads disagreed about EPSG:4326", 0, mismatches.get());
        assertEquals("the epsg dictionary was parsed more than once under concurrent demand",
                1L, cache.missCount());
        assertEquals(1, cache.size());
    }

    // ==========================================================================================
    // Helpers
    // ==========================================================================================

    /**
     * Parses a dictionary independently of the cache, keeping the first occurrence of each code,
     * which is what the streaming scan returned.
     */
    private static LinkedHashMap<String, String[]> referenceForward(String file) throws IOException {
        LinkedHashMap<String, String[]> out = new LinkedHashMap<String, String[]>();
        InputStream in = InitFileCacheTest.class.getClassLoader()
                .getResourceAsStream("proj4/nad/" + file);
        assertNotNull("proj4/nad/" + file + " is not on the test classpath", in);
        BufferedReader reader = new BufferedReader(new InputStreamReader(in));
        try {
            StreamTokenizer t = Proj4FileReader.createTokenizer(reader);
            t.nextToken();
            while (t.ttype == '<') {
                Pair<String, List> pair = Proj4FileReader.parseTokenizer(t);
                @SuppressWarnings("unchecked")
                List<String> v = (List<String>) pair.snd();
                if (!out.containsKey(pair.fst())) {
                    out.put(pair.fst(), v.toArray(new String[v.size()]));
                }
            }
        } finally {
            reader.close();
        }
        return out;
    }

    /**
     * Parses a dictionary independently of the cache into parameter-set -&gt; first code, over the
     * <b>whole entry stream</b>, which is what {@code readEpsgCodeFromFile}'s scan saw. Keys are
     * the parameter arrays joined with a delimiter no PROJ.4 token contains.
     */
    private static LinkedHashMap<List<String>, String> referenceReverse(String file)
            throws IOException {
        LinkedHashMap<List<String>, String> out = new LinkedHashMap<List<String>, String>();
        InputStream in = InitFileCacheTest.class.getClassLoader()
                .getResourceAsStream("proj4/nad/" + file);
        assertNotNull("proj4/nad/" + file + " is not on the test classpath", in);
        BufferedReader reader = new BufferedReader(new InputStreamReader(in));
        try {
            StreamTokenizer t = Proj4FileReader.createTokenizer(reader);
            t.nextToken();
            while (t.ttype == '<') {
                Pair<String, List> pair = Proj4FileReader.parseTokenizer(t);
                @SuppressWarnings("unchecked")
                List<String> v = (List<String>) pair.snd();
                // A fresh list, so the key is exactly the parameter sequence and shares nothing
                // with the cache under test. A List key is used rather than a joined String
                // because no delimiter is provably absent from a PROJ.4 token.
                List<String> key = new ArrayList<String>(v);
                if (!out.containsKey(key)) {
                    out.put(key, pair.fst());
                }
            }
        } finally {
            reader.close();
        }
        return out;
    }

    /** The reverse lookup restricted to one fixture file, via the cache's own index. */
    private static String reverseIn(String file, String[] params) throws IOException {
        // readEpsgCodeFromFile is hard-wired to "epsg", so reach the fixture's index directly.
        InitFileCache cache = new InitFileCache(InitFileCache.DEFAULT_MAX_BYTES);
        return cache.get(file).codeForParameters(params);
    }

    private static String expectMissingResource(Proj4FileReader reader, String auth, String code) {
        try {
            reader.readParametersFromFile(auth, code);
            fail(auth + ":" + code + " resolved but should not have");
            return null;
        } catch (IllegalStateException e) {
            return e.getMessage();
        } catch (IOException e) {
            fail("expected IllegalStateException for a missing resource, got " + e);
            return null;
        }
    }

    private static String expectIoFailure(Proj4FileReader reader, String auth, String code) {
        try {
            reader.readParametersFromFile(auth, code);
            fail(auth + ":" + code + " did not fail");
            return null;
        } catch (IOException e) {
            return e.getMessage();
        }
    }

    private static void assertArrayEquals2(String what, String[] expected, String[] actual) {
        assertEquals(what + ": " + Arrays.toString(expected) + " vs " + Arrays.toString(actual),
                Arrays.toString(expected), Arrays.toString(actual));
    }

    private static String head(List<String> items) {
        if (items.size() <= 10) {
            return items.toString();
        }
        return items.subList(0, 10) + " ... and " + (items.size() - 10) + " more";
    }
}
