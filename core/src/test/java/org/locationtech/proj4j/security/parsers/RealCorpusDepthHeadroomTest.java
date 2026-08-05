/*
 * Copyright 2026, PROJ4J contributors
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
package org.locationtech.proj4j.security.parsers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import org.junit.Assume;
import org.junit.Test;
import org.locationtech.proj4j.io.projjson.ProjJsonReader;
import org.locationtech.proj4j.io.projjson.ProjJsonWriter;
import org.locationtech.proj4j.io.wkt.CrsDefinition;
import org.locationtech.proj4j.io.wkt.WktDialect;
import org.locationtech.proj4j.io.wkt.WktNode;
import org.locationtech.proj4j.io.wkt.WktParser;
import org.locationtech.proj4j.io.wkt.WktReader;
import org.locationtech.proj4j.io.wkt.WktWriter;

/**
 * A depth cap below real data is a functional regression, so this measures the real data.
 *
 * <p>The corpus is {@code proj4/wkt/epsg.properties} from the shipped {@code proj4j-epsg} artifact:
 * <b>5,671 EPSG definitions in WKT1</b>, the widest sample of genuine WKT this project carries.
 * Every one of them is parsed, written back as WKT2 in both dialects and as PROJJSON, and re-read;
 * every depth is measured and the maxima are pinned. The limits in force are <b>64</b> syntactic
 * and <b>24</b> semantic, so the headroom is asserted rather than assumed.
 *
 * <p><b>What the corpus actually contains</b>, and therefore the answer to "is 64 above real data":
 * <table>
 *   <caption>deepest real document</caption>
 *   <tr><td>WKT1 tree, read</td><td><b>7</b></td></tr>
 *   <tr><td>WKT2:2019 tree, written</td><td><b>8</b></td></tr>
 *   <tr><td>WKT2:2015 tree, written</td><td><b>8</b></td></tr>
 *   <tr><td>PROJJSON, written</td><td><b>8</b></td></tr>
 *   <tr><td>nested CRSs, semantic</td><td><b>3</b></td></tr>
 * </table>
 * The caps are eight times the deepest real document in every one of those five directions.
 *
 * <p><b>The measurement has a control</b>, because a scan that reports a comfortable maximum having
 * silently read nothing is exactly the failure this project keeps finding: the test asserts a
 * minimum number of entries scanned, asserts that the deepest one found is genuinely nested (not
 * 1, which is what a broken depth meter would report), and names the entry it found.
 */
public class RealCorpusDepthHeadroomTest {

    /** The syntactic limit enforced by {@code WktParser} and {@code WktFormat}. */
    private static final int MAX_DEPTH = 64;

    /** The resource, in the {@code proj4j-epsg} artifact, which is on core's test classpath. */
    private static final String CORPUS = "proj4/wkt/epsg.properties";

    /** The semantic limit enforced by {@code WktReader}, {@code WktWriter} and {@code CrsDefinition}. */
    private static final int MAX_CRS_DEPTH = 24;

    /**
     * Pinned, because the corpus is a committed input: 5,671 definitions, none deeper than a tree
     * depth of 7 — {@code COMPD_CS[PROJCS[GEOGCS[DATUM[SPHEROID[AUTHORITY["EPSG","7019"]]]]]]},
     * whose innermost node is a leaf. {@code EPSG:4100} is one such entry. An unpinned "it is
     * comfortably under the limit" would not notice the corpus changing underneath it.
     */
    private static final int EXPECTED_MAX_TREE_DEPTH = 7;

    /**
     * What this library <em>emits</em> is one level deeper than what it reads, because WKT2 wraps a
     * projected CRS's base in {@code BASEGEOGCRS} where WKT1 nests {@code GEOGCS} directly. Pinned
     * for both output notations, because a cap that admitted every input but refused the library's
     * own output would be just as much a regression — and it is the emitted depth, not the read
     * depth, that the writers' guards have to clear.
     */
    private static final int EXPECTED_MAX_WRITTEN_WKT_DEPTH = 8;

    private static final int EXPECTED_MAX_WRITTEN_JSON_DEPTH = 8;

    /**
     * The deepest <em>semantic</em> nesting in real data: {@code COMPD_CS[PROJCS[GEOGCS[…]]]} is
     * three coordinate reference systems, because a projected CRS's base geographic CRS is one in
     * its own right. The histogram is pinned too, so that a change which flattened the graph — and
     * so quietly disarmed the semantic guard — would fail here rather than pass.
     */
    private static final int EXPECTED_MAX_CRS_DEPTH = 3;

    private static final int EXPECTED_AT_CRS_DEPTH_1 = 1240;
    private static final int EXPECTED_AT_CRS_DEPTH_2 = 4367;
    private static final int EXPECTED_AT_CRS_DEPTH_3 = 64;

    private static final int EXPECTED_ENTRY_COUNT = 5671;

    @Test
    public void theDeepestRealWktInTheShippedDictionaryIsFarInsideTheLimit() throws IOException {
        Properties corpus = loadCorpus();
        Assume.assumeTrue("proj4j-epsg is not on the classpath", corpus != null);

        int scanned = 0;
        int deepest = 0;
        String deepestKey = null;
        List<String> unparsable = new ArrayList<String>();
        for (String key : sortedKeys(corpus)) {
            String wkt = corpus.getProperty(key);
            WktNode root;
            try {
                root = WktParser.parse(wkt);
            } catch (RuntimeException e) {
                unparsable.add(key + ": " + e.getMessage());
                continue;
            }
            scanned++;
            int d = treeDepth(root);
            if (d > deepest) {
                deepest = d;
                deepestKey = key;
            }
        }

        assertEquals("every shipped definition must still parse under the new depth cap -- "
                + "a cap below real data is a functional regression: " + head(unparsable),
                0, unparsable.size());
        assertEquals("the corpus is a committed input; its size is pinned",
                EXPECTED_ENTRY_COUNT, scanned);
        assertTrue("CONTROL FAILED: the depth meter reported " + deepest + ", which is what it "
                        + "would report if it were not measuring nesting at all",
                deepest >= 5);
        assertEquals("deepest real WKT was EPSG:" + deepestKey,
                EXPECTED_MAX_TREE_DEPTH, deepest);
        assertTrue("the cap must sit far above real data, not just above it",
                MAX_DEPTH >= 8 * deepest);
    }

    /**
     * The same question for what this library <em>emits</em>, and for the semantic layer.
     *
     * <p>Scans the <b>whole</b> corpus, not just its compound entries. Filtering to
     * {@code COMPD_CS} would be an assumption about where the deep documents are, and an
     * unverified assumption in a headroom measurement is how a cap ends up below real data. Every
     * definition is read, written as WKT2 in both dialects and as PROJJSON, and read back — so the
     * acceptance half of all four guards is exercised 5,671 times.
     */
    @Test
    public void theDeepestDocumentThisLibraryWritesIsFarInsideTheLimit() throws IOException {
        Properties corpus = loadCorpus();
        Assume.assumeTrue("proj4j-epsg is not on the classpath", corpus != null);

        int written = 0;
        int compound = 0;
        int deepestWkt2019 = 0;
        int deepestWkt2015 = 0;
        int deepestJson = 0;
        int deepestCrs = 0;
        String deepestWktKey = null;
        String deepestJsonKey = null;
        int[] crsHistogram = new int[MAX_CRS_DEPTH + 2];

        for (String key : sortedKeys(corpus)) {
            String wkt = corpus.getProperty(key);
            if (wkt.indexOf("COMPD_CS") >= 0) {
                compound++;
            }
            CrsDefinition def = new WktReader().readDefinition(wkt);

            int d2019 = treeDepth(new WktWriter(WktDialect.WKT2_2019).toNode(def));
            int d2015 = treeDepth(new WktWriter(WktDialect.WKT2_2015).toNode(def));
            int dWkt = Math.max(d2019, d2015);
            if (dWkt > deepestWkt2019) {
                deepestWktKey = key;
            }
            deepestWkt2019 = Math.max(deepestWkt2019, d2019);
            deepestWkt2015 = Math.max(deepestWkt2015, d2015);

            String json = new ProjJsonWriter().write(def);
            int dJson = textDepth(json, '{', '[', '}', ']');
            if (dJson > deepestJson) {
                deepestJsonKey = key;
            }
            deepestJson = Math.max(deepestJson, dJson);
            // and it reads back, which is the acceptance half
            new ProjJsonReader().readDefinition(json);

            int dCrs = crsDepth(def);
            deepestCrs = Math.max(deepestCrs, dCrs);
            if (dCrs < crsHistogram.length) {
                crsHistogram[dCrs]++;
            }
            written++;
        }

        assertEquals("every shipped definition must survive a full write and re-read",
                EXPECTED_ENTRY_COUNT, written);
        assertTrue("CONTROL FAILED: no compound definitions were found, so the deep cases were "
                + "never exercised", compound >= 50);
        assertTrue("CONTROL FAILED: the WKT depth meter returned " + deepestWkt2019
                + ", which is what it would report if it were not measuring nesting",
                deepestWkt2019 >= 5);
        assertTrue("CONTROL FAILED: the PROJJSON depth meter returned " + deepestJson,
                deepestJson >= 5);
        assertTrue("CONTROL FAILED: the CRS depth meter returned " + deepestCrs, deepestCrs >= 2);

        assertEquals("deepest WKT2:2019 this library writes, from EPSG:" + deepestWktKey,
                EXPECTED_MAX_WRITTEN_WKT_DEPTH, deepestWkt2019);
        assertEquals("deepest WKT2:2015 this library writes",
                EXPECTED_MAX_WRITTEN_WKT_DEPTH, deepestWkt2015);
        assertEquals("deepest PROJJSON this library writes, from EPSG:" + deepestJsonKey,
                EXPECTED_MAX_WRITTEN_JSON_DEPTH, deepestJson);
        assertEquals("deepest semantic CRS nesting in real data",
                EXPECTED_MAX_CRS_DEPTH, deepestCrs);
        assertEquals("definitions at CRS depth 1", EXPECTED_AT_CRS_DEPTH_1, crsHistogram[1]);
        assertEquals("definitions at CRS depth 2", EXPECTED_AT_CRS_DEPTH_2, crsHistogram[2]);
        assertEquals("definitions at CRS depth 3", EXPECTED_AT_CRS_DEPTH_3, crsHistogram[3]);

        assertTrue("the syntactic cap must sit far above what this library emits, not just above it",
                MAX_DEPTH >= 8 * deepestWkt2019 && MAX_DEPTH >= 8 * deepestJson);
        assertTrue("the semantic cap must sit far above real data",
                MAX_CRS_DEPTH >= 8 * deepestCrs);
    }

    // ------------------------------------------------------------------------------- helpers

    private static Properties loadCorpus() throws IOException {
        InputStream in = RealCorpusDepthHeadroomTest.class.getClassLoader()
                .getResourceAsStream(CORPUS);
        if (in == null) {
            return null;
        }
        try {
            Properties p = new Properties();
            Reader r = new InputStreamReader(in, "UTF-8");
            p.load(r);
            return p;
        } finally {
            in.close();
        }
    }

    /** Deterministic iteration, so a tie between equally deep entries always names the same one. */
    private static List<String> sortedKeys(Properties corpus) {
        List<String> keys = new ArrayList<String>(corpus.stringPropertyNames());
        Collections.sort(keys);
        return keys;
    }

    /** Nested CRSs in a definition graph, the outermost being 1 — {@code WktReader}'s convention. */
    private static int crsDepth(CrsDefinition def) {
        int best = 0;
        if (def.getBaseCrs() != null) {
            best = Math.max(best, crsDepth(def.getBaseCrs()));
        }
        if (def.getHubCrs() != null) {
            best = Math.max(best, crsDepth(def.getHubCrs()));
        }
        for (int i = 0; i < def.getComponents().size(); i++) {
            best = Math.max(best, crsDepth(def.getComponents().get(i)));
        }
        return best + 1;
    }

    private static int treeDepth(WktNode node) {
        int best = 0;
        for (int i = 0; i < node.childCount(); i++) {
            best = Math.max(best, treeDepth(node.child(i)));
        }
        return best + 1;
    }

    /** Nesting of {@code text}, ignoring brackets inside quoted strings. */
    private static int textDepth(String text, char open1, char open2, char close1, char close2) {
        int depth = 0;
        int max = 0;
        boolean inQuotes = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inQuotes) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inQuotes = false;
                }
                continue;
            }
            if (c == '"') {
                inQuotes = true;
            } else if (c == open1 || c == open2) {
                depth++;
                max = Math.max(max, depth);
            } else if (c == close1 || c == close2) {
                depth--;
            }
        }
        return max;
    }

    private static String head(List<String> items) {
        if (items.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(5, items.size()); i++) {
            sb.append("\n  ").append(items.get(i));
        }
        if (items.size() > 5) {
            sb.append("\n  ... and ").append(items.size() - 5).append(" more");
        }
        return sb.toString();
    }
}
