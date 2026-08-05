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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.locationtech.proj4j.parser.Proj4Keyword;

/**
 * A lenient, PROJ-faithful reading of a proj-string, wrapping — never modifying —
 * proj4j's {@code Proj4Parser} and {@code Proj4Keyword}.
 *
 * <p>This exists because <b>PROJ has no allow-list</b>. {@code init.cpp} retains
 * every {@code +key} verbatim in a {@code paralist} and recognition is
 * <em>pull-based</em> via {@code pj_param}; a token nobody asks for keeps
 * {@code used == 0} and has no effect. There is no enumeration of valid keys
 * anywhere in PROJ. proj4j's {@code Proj4Keyword.supportedParameters()} is a
 * fixed 35-key allow-list (36 counting {@code +proj} itself as special) whose
 * violation is a hard {@code UnsupportedParameterException} — see
 * {@link #allowListSize()} for the measured value — <em>stricter than PROJ</em>, and
 * therefore unable to pass the corpus. {@code builtins.gie}'s non-strict block
 * feeds a literal {@code unknown_keyword} and expects it ignored.
 *
 * <p>Three PROJ semantics are reproduced exactly, and all three differ from what
 * {@code Proj4Parser} does today:
 *
 * <ol>
 * <li><b>First-match-wins.</b> {@code pj_param_exists} ({@code src/param.cpp})
 *     walks the list front-to-back and returns the <em>first</em> match, which is
 *     why {@code +init=}/{@code +datum=} expansions are <em>appended</em> and can
 *     be shadowed by user tokens. {@code Proj4Parser} builds a {@code HashMap}
 *     and so keeps the <em>last</em> occurrence — inverted. See
 *     {@link #find(String)}.</li>
 * <li><b>Lookup stops at the first {@code step} token</b> ({@code param.cpp:72-105})
 *     unless the key sought <em>is</em> {@code step}. This is the entire mechanism
 *     that scopes a pipeline step's parameters. See {@link #steps()}.</li>
 * <li><b>Prefix matching is {@code '='}- or end-terminated</b>, so key {@code a}
 *     must never match {@code axis}. Tokenising up front gives this for free.</li>
 * </ol>
 *
 * <p>Order is preserved throughout, and {@link GieToken#used()} mirrors PROJ's
 * only piece of parameter bookkeeping so {@link #unusedKeys()} can reproduce
 * {@code pj_pr_list}.
 *
 * <p><b>This class never throws on a definition.</b> Tokenising is total.
 * Rejection is a separate, later, deliberate act — see
 * {@link Proj4jGieOperationFactory}. Keeping the two apart is what lets an
 * unknown key be <em>ignored</em> by the parser while still forcing the operation
 * to be reported {@link GieFailureKind#NOT_IMPLEMENTED} rather than silently
 * mis-executed.
 */
public final class GieProjArgs {

    private final String raw;
    private final List<GieToken> tokens;
    private final boolean implicitEllipsoid;

    private GieProjArgs(String raw, List<GieToken> tokens, boolean implicitEllipsoid) {
        this.raw = raw;
        this.tokens = tokens;
        this.implicitEllipsoid = implicitEllipsoid;
    }

    // ------------------------------------------------------------ construction

    /**
     * Tokenise a definition.
     *
     * @param args the definition, normally already through {@code pj_chomp} and
     *             {@code pj_shrink} (which strip comments, collapse whitespace,
     *             drop whitespace-preceded {@code '+'} and {@code ';'}, and make
     *             {@code '='} and {@code ','} greedy). A raw string with
     *             {@code '+'} prefixes is accepted too, so hand-written test
     *             fixtures and corpus input can share one entry point.
     *             {@code null} is treated as empty.
     * @return never {@code null}.
     */
    public static GieProjArgs parse(String args) {
        String in = args == null ? "" : args;
        List<GieToken> out = new ArrayList<GieToken>();
        int n = in.length();
        int i = 0;
        int index = 0;
        while (i < n) {
            while (i < n && isSeparator(in.charAt(i))) {
                i++;
            }
            if (i >= n) {
                break;
            }
            int start = i;
            while (i < n && !isSeparator(in.charAt(i))) {
                i++;
            }
            String tok = in.substring(start, i);
            // pj_shrink drops a whitespace-preceded '+', but accept it anyway.
            while (tok.startsWith("+")) {
                tok = tok.substring(1);
            }
            // A trailing backslash is gie's line-continuation marker; the lexer
            // strips it, but a hand-written fixture may not have.
            while (tok.endsWith("\\")) {
                tok = tok.substring(0, tok.length() - 1);
            }
            if (tok.isEmpty()) {
                continue;
            }
            int eq = tok.indexOf('=');
            if (eq < 0) {
                out.add(new GieToken(tok, null, false, index++));
            } else {
                out.add(new GieToken(tok.substring(0, eq), tok.substring(eq + 1), true, index++));
            }
        }
        return new GieProjArgs(in, out, false);
    }

    private static boolean isSeparator(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == 11 || c == ';';
    }

    // ------------------------------------------------------------------ basics

    /** The definition as handed in. */
    public String raw() {
        return raw;
    }

    /**
     * The {@code paralist}, in order, duplicates included. The list is
     * unmodifiable; the tokens themselves carry a mutable {@code used} flag.
     */
    public List<GieToken> tokens() {
        return Collections.unmodifiableList(tokens);
    }

    /** Number of tokens. */
    public int size() {
        return tokens.size();
    }

    /** Whether there are no tokens at all. */
    public boolean isEmpty() {
        return tokens.isEmpty();
    }

    /** The number of keys proj4j's {@code Proj4Keyword} will accept. */
    public static int allowListSize() {
        return Proj4Keyword.supportedParameters().size();
    }

    // ------------------------------------------------------------- pj_param(3)

    /**
     * {@code pj_param_exists}: the first token whose key equals {@code key},
     * scanning front-to-back and <b>stopping at the first {@code step} token</b>
     * unless {@code key} is itself {@code "step"}.
     *
     * <p>Does not set {@code used} — see {@link #value(String)}.
     *
     * @return the token, or {@code null}.
     */
    public GieToken find(String key) {
        boolean seekingStep = "step".equals(key);
        for (int i = 0; i < tokens.size(); i++) {
            GieToken t = tokens.get(i);
            if (!seekingStep && t.isStep()) {
                return null;
            }
            if (t.key().equals(key)) {
                return t;
            }
        }
        return null;
    }

    /** {@link #find(String)} without marking, i.e. a pure query. */
    public String peek(String key) {
        GieToken t = find(key);
        return t == null ? null : t.value();
    }

    /**
     * {@link #find(String)} and mark the token {@code used}, as every successful
     * {@code pj_param} lookup does.
     *
     * @return the value, {@code null} if absent <em>or</em> if the token had no
     *         {@code '='}. Use {@link #exists(String)} to tell those apart.
     */
    public String value(String key) {
        GieToken t = find(key);
        if (t == null) {
            return null;
        }
        t.markUsed();
        return t.value();
    }

    /** {@code pj_param} type {@code 't'}: presence test. Marks the token used. */
    public boolean exists(String key) {
        GieToken t = find(key);
        if (t == null) {
            return false;
        }
        t.markUsed();
        return true;
    }

    /** Presence test that does not mark. */
    public boolean contains(String key) {
        return find(key) != null;
    }

    // ------------------------------------------------------------------- steps

    /**
     * {@code true} when this is a pipeline: either the (globally scoped)
     * {@code +proj} is {@code pipeline}, or a {@code +step} token is present.
     * Both are checked because the corpus contains multi-step strings whose head
     * says {@code pipeline} and because a stray {@code +step} alone would still
     * scope every later lookup away.
     */
    public boolean isPipeline() {
        return "pipeline".equals(peek("proj")) || stepCount() > 1;
    }

    /** 1 for a plain definition; 1 + the number of {@code +step} tokens otherwise. */
    public int stepCount() {
        int steps = 0;
        for (int i = 0; i < tokens.size(); i++) {
            if (tokens.get(i).isStep()) {
                steps++;
            }
        }
        return steps + 1;
    }

    /**
     * Split on {@code +step}.
     *
     * <p>Element 0 is the <em>global</em> scope — everything before the first
     * {@code step}, which is where {@code +proj=pipeline} and any pipeline-wide
     * {@code +ellps} live. Elements 1..n are the steps themselves, each ending at
     * the next {@code step}, so {@link #find(String)} on one of them naturally
     * honours PROJ's step scoping.
     *
     * <p>The {@code step} token itself is dropped from each slice. Global
     * parameter <em>inheritance</em> into steps is deliberately not modelled:
     * every multi-step operation is classified
     * {@link GieFailureKind#NOT_IMPLEMENTED} today, and guessing at inheritance
     * would be exactly the kind of plausible-but-wrong behaviour this bridge
     * exists to avoid.
     */
    public List<GieProjArgs> steps() {
        List<GieProjArgs> out = new ArrayList<GieProjArgs>();
        List<GieToken> current = new ArrayList<GieToken>();
        for (int i = 0; i < tokens.size(); i++) {
            GieToken t = tokens.get(i);
            if (t.isStep()) {
                out.add(new GieProjArgs(raw, current, implicitEllipsoid));
                current = new ArrayList<GieToken>();
            } else {
                current.add(t);
            }
        }
        out.add(new GieProjArgs(raw, current, implicitEllipsoid));
        return out;
    }

    // ------------------------------------------------------------- bookkeeping

    /** Every key in order, duplicates included. */
    public List<String> keys() {
        List<String> out = new ArrayList<String>(tokens.size());
        for (int i = 0; i < tokens.size(); i++) {
            out.add(tokens.get(i).key());
        }
        return out;
    }

    /** Keys appearing more than once, in first-occurrence order. */
    public List<String> duplicateKeys() {
        Set<String> seen = new HashSet<String>();
        Set<String> dup = new HashSet<String>();
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < tokens.size(); i++) {
            String k = tokens.get(i).key();
            if (!seen.add(k) && dup.add(k)) {
                out.add(k);
            }
        }
        return out;
    }

    /** Tokens no lookup has matched. */
    public List<GieToken> unused() {
        List<GieToken> out = new ArrayList<GieToken>();
        for (int i = 0; i < tokens.size(); i++) {
            if (!tokens.get(i).used()) {
                out.add(tokens.get(i));
            }
        }
        return out;
    }

    /** {@link #unused()} reduced to keys. */
    public List<String> unusedKeys() {
        List<GieToken> u = unused();
        List<String> out = new ArrayList<String>(u.size());
        for (int i = 0; i < u.size(); i++) {
            out.add(u.get(i).key());
        }
        return out;
    }

    /**
     * {@code pj_pr_list()}'s unused-parameter report ({@code src/pr_list.cpp}),
     * which is what {@code proj -v} emits. It is <em>not</em> a warning through
     * PROJ's logging API and is unreachable from {@code proj_create}, so nothing
     * in the corpus asserts on it; it is here because an unused-token list is the
     * only honest way to show what a lenient parser silently absorbed.
     *
     * @return the report, or {@code null} when every token was used.
     */
    public String prList() {
        List<GieToken> u = unused();
        if (u.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("#--- following specified but NOT used");
        for (int i = 0; i < u.size(); i++) {
            sb.append('\n').append(u.get(i).text());
        }
        return sb.toString();
    }

    // -------------------------------------------------------- proj4j interop

    /** Keys outside {@code Proj4Keyword.supportedParameters()}, in order, deduplicated. */
    public List<String> keysOutsideAllowList() {
        List<String> out = new ArrayList<String>();
        Set<String> seen = new HashSet<String>();
        for (int i = 0; i < tokens.size(); i++) {
            GieToken t = tokens.get(i);
            if (t.isStep()) {
                break;
            }
            if (!Proj4Keyword.isSupported(t.key()) && seen.add(t.key())) {
                out.add(t.key());
            }
        }
        return out;
    }

    /**
     * The first occurrence of every key, in order — PROJ's view of the
     * definition, and the opposite of what {@code Proj4Parser}'s {@code HashMap}
     * would produce from the same string.
     */
    public Map<String, String> firstOccurrences() {
        Map<String, String> out = new LinkedHashMap<String, String>();
        for (int i = 0; i < tokens.size(); i++) {
            GieToken t = tokens.get(i);
            if (t.isStep()) {
                break;
            }
            if (!out.containsKey(t.key())) {
                out.put(t.key(), t.value());
            }
        }
        return out;
    }

    /**
     * The definition reduced to what {@code Proj4Parser} can be handed without
     * throwing: allow-listed keys only, <b>first occurrence only</b>, in order,
     * up to the first {@code step}.
     *
     * <p>Filtering here rather than letting {@code Proj4Keyword.checkUnsupported}
     * throw is what makes an unknown key <em>ignored</em>, as PROJ ignores it.
     * Dropping a token is not free, though: if PROJ would have acted on it, the
     * operation must be reported {@link GieFailureKind#NOT_IMPLEMENTED} rather
     * than executed without it. That decision belongs to
     * {@link Proj4jGieOperationFactory}; this method only prepares the input.
     *
     * <p>Feeding the first occurrence only is what fixes the {@code HashMap}
     * inversion: {@code Proj4Parser} would otherwise keep the last.
     */
    public String[] toProj4Args() {
        Map<String, String> first = firstOccurrences();
        List<String> out = new ArrayList<String>();
        for (Map.Entry<String, String> e : first.entrySet()) {
            if (!Proj4Keyword.isSupported(e.getKey())) {
                continue;
            }
            out.add(e.getValue() == null ? "+" + e.getKey() : "+" + e.getKey() + "=" + e.getValue());
        }
        return out.toArray(new String[out.size()]);
    }

    // ----------------------------------------------- the implicit +ellps=GRS80

    /**
     * PROJ's implicit {@code +ellps=GRS80} rule ({@code 9.8.1:src/init.cpp:317-360}).
     * GRS80 is <b>appended to the end</b> of the paralist — so it is shadowed by
     * any user token, per first-match-wins — unless <em>any</em> of:
     *
     * <ul>
     * <li>{@code +no_defs} is present;</li>
     * <li>there is no {@code +proj}, or its token is shorter than 6 characters
     *     (i.e. {@code proj=} with an empty value), or it is {@code pipeline};</li>
     * <li>any of {@code +datum +ellps +a +b +rf +f +e +es} is present.</li>
     * </ul>
     *
     * <p>Note it is <b>not</b> suppressed by {@code +R}: {@code +proj=merc +R=1}
     * does get GRS80 appended, and {@code +R} then overrules it in
     * {@code ell_set.cpp}.
     *
     * <p>Verified against the installed PROJ 9.8.1: {@code proj +proj=merc} on
     * {@code 2 1} gives {@code 222638.981586547 110579.965218250}, identical to
     * {@code +proj=merc +ellps=GRS80} and <em>different</em> from
     * {@code +ellps=WGS84} ({@code …218250} vs {@code …221896}). Without this
     * rule, {@code DatumParameters} defaults {@code a} and {@code es} to
     * {@code NaN} and every such operation returns {@code NaN} — which would be
     * scored as a numerical defect rather than as the parser gap it is.
     */
    public boolean impliesGrs80() {
        if (contains("no_defs")) {
            return false;
        }
        GieToken proj = find("proj");
        if (proj == null || proj.text().length() - 1 < 6 || "pipeline".equals(proj.value())) {
            return false;
        }
        String[] shape = {"datum", "ellps", "a", "b", "rf", "f", "e", "es"};
        for (int i = 0; i < shape.length; i++) {
            if (contains(shape[i])) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether the definition gives the ellipsoid a size at all — {@code +ellps},
     * {@code +datum}, {@code +a}, {@code +b} or {@code +R}. With {@code +no_defs}
     * and none of these, PROJ 9.8.1 fails the definition outright:
     * {@code "pj_init_ctx: Must specify ellipsoid or sphere"} (error 1026),
     * confirmed by running {@code proj +proj=merc +no_defs}.
     */
    public boolean hasEllipsoidSize() {
        return contains("ellps") || contains("datum") || contains("a")
                || contains("b") || contains("R");
    }

    /**
     * This definition with PROJ's implicit {@code +ellps=GRS80} appended when
     * {@link #impliesGrs80()}, otherwise {@code this}. Appending, not prepending,
     * matters: a user token must shadow it.
     */
    public GieProjArgs withImplicitDefaults() {
        if (!impliesGrs80()) {
            return this;
        }
        List<GieToken> copy = new ArrayList<GieToken>(tokens);
        copy.add(new GieToken("ellps", "GRS80", true, tokens.size()));
        return new GieProjArgs(raw, copy, true);
    }

    /** Whether {@link #withImplicitDefaults()} added the {@code ellps=GRS80} token. */
    public boolean implicitEllipsoidAppended() {
        return implicitEllipsoid;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tokens.size(); i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(tokens.get(i).text());
        }
        return sb.toString();
    }
}
