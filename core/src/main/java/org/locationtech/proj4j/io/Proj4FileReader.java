/*******************************************************************************
 * Copyright 2009, 2017 Martin Davis
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
package org.locationtech.proj4j.io;

import org.locationtech.proj4j.resource.ResourceNames;
import org.locationtech.proj4j.util.Pair;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StreamTokenizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Reads PROJ.4 {@code +init=} dictionaries from the classpath.
 *
 * <p>Lookups are served from {@link InitFileCache}, which parses each dictionary <b>once</b> into a
 * {@code Map<String,String[]>} plus a reverse index. Before that cache existed every call re-opened
 * the resource and re-tokenised the whole file, allocating an {@code ArrayList} and a {@link Pair}
 * per entry scanned: {@code CrsParseBenchmark} measured {@code createFromName} at 39,632 B/op for
 * the first {@code proj4/nad/epsg} entry, 4,002,108 for the middle one and 7,919,398 for the last,
 * against 1,920&ndash;4,112 for the parse-only control.
 *
 * <p>The streaming scan is still here and still used: for a dictionary too large to cache, and as
 * the reference implementation the cached path is tested against.
 */
public class Proj4FileReader {

    /** Every init dictionary lives under this classpath prefix. */
    static final String RESOURCE_PREFIX = "proj4/nad/";

    private final InitFileCache cache;

    public Proj4FileReader() {
        this(InitFileCache.instance());
    }

    /** For tests, which need a cache with a budget of their own choosing. */
    Proj4FileReader(InitFileCache cache) {
        this.cache = cache;
    }

    /**
     * The classpath resource name suffix, and the {@link InitFileCache} key, for an authority.
     *
     * <p>Locale.ROOT, not the default locale: this is a classpath resource NAME, not text for a
     * reader. Under tr_TR the default-locale rule maps 'I' to dotless 'i' (U+0131), so "ESRI"
     * lowercases to "esri" spelled with U+0131, the lookup for proj4/nad/esri never resolves,
     * and every one of the ESRI-authority definitions becomes unreachable.
     *
     * <p>Because the cache key is exactly this string, a cache entry and the resource it was read
     * from cannot disagree. The <em>code within the file</em> is a separate question and is
     * deliberately <b>not</b> folded: it was compared with {@code String.equals} and a
     * {@code HashMap} lookup uses the same comparison, so {@code EPSG:4326} and {@code epsg:4326}
     * still resolve to the same definition while {@code EPSG:4326} and {@code EPSG:4326 } still do
     * not.
     */
    static String fileKey(String authorityCode) {
        return authorityCode.toLowerCase(Locale.ROOT);
    }

    /**
     * Refuses an authority that must not become part of a classpath resource name.
     *
     * <h4>This was the one unguarded path into the resource layer</h4>
     *
     * <p>{@code +init=<authority>:<code>} takes the authority straight out of the CRS string — the
     * same untrusted, possibly per-row string a {@code +nadgrids=} token comes from — lowercases it
     * and concatenates it onto {@code "proj4/nad/"}. <em>Every</em> grid path is validated by
     * {@link ResourceNames}, at three layers; this one was validated nowhere, so
     * {@code +init=../../foo:bar} reached {@code ClassLoader.getResourceAsStream} as
     * {@code "proj4/nad/../../foo"} with nothing having looked at it. Whether a given classloader
     * normalises that away is a property of the deployment, not of this code, which is precisely
     * the kind of "it happens to be safe here" the resource layer exists to replace.
     *
     * <p>It is the same rule and the same implementation the resolvers use, not a second copy: a
     * name refused for a grid is refused for an init file. Refusal is deliberately not silent —
     * returning {@code null} would be indistinguishable from "no such CRS", and the caller deserves
     * to be told the name was rejected and by which rule.
     *
     * <p>{@link IllegalStateException} is chosen to match, exactly, what an unresolvable authority
     * already threw two lines further down ({@code "Unable to access CRS file: ..."}). That keeps
     * the refusal in the family every caller already handles — {@code InitFileExpander} maps it to
     * {@code INVALID_INIT_KEY}, PROJ's own classification for an init file it cannot open
     * ({@code init.cpp:105,119,134}) — instead of introducing a second unchecked type that one of
     * them would miss.
     *
     * @throws IllegalStateException if the authority cannot be part of a resource name
     */
    static String checkedFileKey(String authorityCode) {
        String key = fileKey(authorityCode);
        ResourceNames.Rule violation = ResourceNames.violation(key);
        if (violation != null) {
            throw new IllegalStateException("Refusing +init= authority \"" + authorityCode
                    + "\": " + violation.description() + " (" + violation + ")."
                    + " An init-file authority becomes part of the classpath resource name \""
                    + RESOURCE_PREFIX + "<authority>\", so it is held to the same rule as a grid"
                    + " name.");
        }
        return key;
    }

    public String[] readParametersFromFile(String authorityCode, String name)
            throws IOException {
        // TODO: read comment preceding CS string as CS description
        // TODO: use simpler parser than StreamTokenizer for speed and flexibility
        // TODO: parse CSes line-at-a-time (this allows preserving CS param string for later access)

        String key = checkedFileKey(authorityCode);
        InitFileCache.Dictionary dict = cache.get(key);
        if (dict == InitFileCache.Dictionary.MISSING) {
            throw new IllegalStateException("Unable to access CRS file: " + RESOURCE_PREFIX + key);
        }
        if (dict == InitFileCache.Dictionary.OVERSIZED) {
            return streamParametersFromFile(key, name);
        }
        String[] params = dict.parameters(name);
        // Defensive copy, and it is not optional. CoordinateReferenceSystem keeps the array it is
        // handed and getParameters() returns it by reference, so a caller mutating one element of
        // a shared array would corrupt this dictionary for every later lookup, process-wide - the
        // exact "silently returns the wrong CRS" failure this cache exists not to introduce.
        return params == null ? null : params.clone();
    }

    /**
     * The pre-cache implementation: open the resource and tokenise until the name matches.
     * Retained because a dictionary too large to cache must still be readable, and because it is
     * the reference the cached path is tested against.
     *
     * @param fileKey the already-lowercased authority, i.e. {@link #fileKey(String)}'s result
     */
    String[] streamParametersFromFile(String fileKey, String name) throws IOException {
        String filename = RESOURCE_PREFIX + fileKey;
        InputStream inStr = Proj4FileReader.class.getClassLoader().getResourceAsStream(filename);
        if (inStr == null) {
            throw new IllegalStateException("Unable to access CRS file: " + filename);
        }
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(inStr));
        String[] args;
        try {
            args = readFile(reader, name);
        } finally {
            reader.close();
        }
        return args;
    }

    static StreamTokenizer createTokenizer(BufferedReader reader) {
        StreamTokenizer t = new StreamTokenizer(reader);
        t.commentChar('#');
        t.ordinaryChars('0', '9');
        t.ordinaryChars('.', '.');
        t.ordinaryChars('-', '-');
        t.ordinaryChars('+', '+');
        t.wordChars('0', '9');
        t.wordChars('\'', '\'');
        t.wordChars('"', '"');
        t.wordChars('_', '_');
        t.wordChars('.', '.');
        t.wordChars('-', '-');
        t.wordChars('+', '+');
        t.wordChars(',', ',');
        t.wordChars('@', '@');
        return t;
    }

    private String[] readFile(BufferedReader reader, String name) throws IOException {
        StreamTokenizer t = createTokenizer(reader);

        t.nextToken();
        while (t.ttype == '<') {
            Pair<String, List> pair = parseTokenizer(t);
            String crsName = pair.fst();
            List v = pair.snd();

            // found requested CRS?
            if (crsName.equals(name)) {
                String[] args = (String[]) v.toArray(new String[0]);
                return args;
            }
        }
        return null;
    }

    private static void addParam(List v, String key, String value) {
        String plusKey = key;
        if (!key.startsWith("+"))
            plusKey = "+" + key;

        if (value != null)
            v.add(plusKey + "=" + value);
        else
            v.add(plusKey);
    }

    /**
     * Gets the list of PROJ.4 parameters which define
     * the coordinate system specified by <code>name</code>.
     *
     * @param crsName the name of the coordinate system
     * @return the PROJ.4 projection parameters which define the coordinate system
     */
    public String[] getParameters(String crsName) {
        try {
            int p = crsName.indexOf(':');
            if (p >= 0) {
                String auth = crsName.substring(0, p);
                String id = crsName.substring(p + 1);
                return readParametersFromFile(auth, id);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    public String readEpsgCodeFromFile(String[] params) throws IOException {
        InitFileCache.Dictionary dict = cache.get("epsg");
        if (dict == InitFileCache.Dictionary.MISSING) {
            throw new IllegalStateException("Unable to access CRS file: EPSG");
        }
        if (dict == InitFileCache.Dictionary.OVERSIZED) {
            return streamEpsgCodeFromFile(params);
        }
        return dict.codeForParameters(params);
    }

    /** The pre-cache implementation of {@link #readEpsgCodeFromFile}; see the forward twin. */
    String streamEpsgCodeFromFile(String[] params) throws IOException {
        InputStream inStr = Proj4FileReader.class.getClassLoader()
                .getResourceAsStream(RESOURCE_PREFIX + "epsg");

        if (inStr == null) {
            throw new IllegalStateException("Unable to access CRS file: EPSG");
        }
        BufferedReader reader = new BufferedReader(new InputStreamReader(inStr));

        try {
            StreamTokenizer t = createTokenizer(reader);

            t.nextToken();
            while (t.ttype == '<') {
                Pair<String, List> pair = parseTokenizer(t);
                String crsName = pair.fst();
                List v = pair.snd();

                String[] paramsParsed = (String[]) v.toArray(new String[0]);

                if (Arrays.equals(params, paramsParsed)) return crsName;
            }
        } finally {
            reader.close();
        }
        return null;
    }

    static Pair<String, List> parseTokenizer(StreamTokenizer t) throws IOException {
        t.nextToken();
        if (t.ttype != StreamTokenizer.TT_WORD)
            throw new IOException(t.lineno() + ": Word expected after '<'");
        String crsName = t.sval;
        t.nextToken();
        if (t.ttype != '>')
            throw new IOException(t.lineno() + ": '>' expected");
        t.nextToken();
        List v = new ArrayList();

        while (t.ttype != '<') {
            if (t.ttype == '+')
                t.nextToken();
            if (t.ttype != StreamTokenizer.TT_WORD)
                throw new IOException(t.lineno() + ": Word expected after '+'");
            String key = t.sval;
            t.nextToken();


            // parse =arg, if any
            if (t.ttype == '=') {
                t.nextToken();
                //Removed check to allow for proj4 hack +nadgrids=@null
                //if ( t.ttype != StreamTokenizer.TT_WORD )
                //  throw new IOException( t.lineno()+": Value expected after '='" );
                String value = t.sval;
                t.nextToken();
                addParam(v, key, value);
            } else {
                // add param with no value
                addParam(v, key, null);
            }
        }
        t.nextToken();
        if (t.ttype != '>')
            throw new IOException(t.lineno() + ": '<>' expected");
        t.nextToken();

        return Pair.create(crsName, v);
    }
}
