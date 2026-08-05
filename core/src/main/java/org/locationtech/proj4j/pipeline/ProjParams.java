/*
 * Copyright 2026 The Proj4J Contributors.
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
package org.locationtech.proj4j.pipeline;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * PROJ's {@code paralist} — an ordered, immutable list of {@code key=value} tokens
 * with {@code pj_param}'s three lookup semantics
 * ({@code 9.8.1:src/param.cpp}, {@code src/init.cpp}).
 *
 * <ol>
 * <li><b>First match wins.</b> {@code pj_param_exists} walks front to back and
 *     returns the <em>first</em> token whose key matches. This is why
 *     {@code +init=} and {@code +datum=} expansions are <em>appended</em>: a user
 *     token written earlier shadows whatever the expansion says.</li>
 * <li><b>Lookup stops at the first {@code step} token</b> ({@code param.cpp:72-105})
 *     unless the key sought <em>is</em> {@code step}. That single rule is the
 *     entire mechanism scoping a pipeline step's parameters, and it is why
 *     {@link #find} refuses to look past one.</li>
 * <li><b>Key matching is exact</b> — terminated by {@code '='} or by the end of
 *     the token — so the key {@code a} never matches {@code axis}. Tokenising up
 *     front gives this for free.</li>
 * </ol>
 *
 * <p>Tokenising is <b>total</b>: this class never rejects a definition. Rejection
 * is a later, separate act (see {@link PipelineFactory}), which is what allows an
 * unrecognised key to be retained and ignored exactly as PROJ retains and ignores
 * it — PROJ has no allow-list of any kind.
 *
 * <p>Immutable and thread-safe.
 *
 * @since 1.5
 */
public final class ProjParams {

    /** The token that both separates and scopes pipeline steps. */
    static final String STEP = "step";

    private final List<String> tokens;

    private ProjParams(final List<String> tokens) {
        this.tokens = tokens;
    }

    /**
     * Tokenise a proj-string.
     *
     * <p>Whitespace and {@code ';'} separate tokens; a leading {@code '+'} and a
     * trailing {@code '\'} (gie's line-continuation marker, which the corpus lexer
     * normally strips) are removed. {@code null} is treated as the empty string.
     *
     * @param definition the definition, with or without {@code '+'} prefixes
     * @return the parameter list; never {@code null}
     */
    public static ProjParams parse(final String definition) {
        final String in = definition == null ? "" : definition;
        final List<String> out = new ArrayList<String>();
        final int n = in.length();
        int i = 0;
        while (i < n) {
            while (i < n && isSeparator(in.charAt(i))) {
                i++;
            }
            if (i >= n) {
                break;
            }
            final int start = i;
            while (i < n && !isSeparator(in.charAt(i))) {
                i++;
            }
            String token = in.substring(start, i);
            while (token.startsWith("+")) {
                token = token.substring(1);
            }
            while (token.endsWith("\\")) {
                token = token.substring(0, token.length() - 1);
            }
            if (!token.isEmpty()) {
                out.add(token);
            }
        }
        return new ProjParams(Collections.unmodifiableList(out));
    }

    /**
     * @param tokens already-normalised tokens, without {@code '+'} prefixes
     * @return a parameter list over a defensive copy of {@code tokens}
     */
    public static ProjParams of(final List<String> tokens) {
        return new ProjParams(Collections.unmodifiableList(new ArrayList<String>(tokens)));
    }

    private static boolean isSeparator(final char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == 0x0b || c == ';';
    }

    // ------------------------------------------------------------------- basics

    /** @return the tokens in order, duplicates included; unmodifiable. */
    public List<String> tokens() {
        return tokens;
    }

    /** @return the number of tokens. */
    public int size() {
        return tokens.size();
    }

    /** @return whether there are no tokens at all. */
    public boolean isEmpty() {
        return tokens.isEmpty();
    }

    // -------------------------------------------------------------- pj_param(3)

    /**
     * {@code pj_param_exists}: the index of the first token whose key is
     * {@code key}, <b>stopping at the first {@code step}</b> unless {@code key} is
     * itself {@code step}.
     *
     * @param key the key sought, without {@code '+'}
     * @return the index, or {@code -1}
     */
    public int find(final String key) {
        final boolean seekingStep = STEP.equals(key);
        for (int i = 0; i < tokens.size(); i++) {
            final String token = tokens.get(i);
            if (!seekingStep && STEP.equals(token)) {
                return -1;
            }
            if (keyEquals(token, key)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean keyEquals(final String token, final String key) {
        if (!token.startsWith(key)) {
            return false;
        }
        return token.length() == key.length() || token.charAt(key.length()) == '=';
    }

    /**
     * {@code pj_param} type {@code 't'}: is the key present in scope?
     *
     * @param key the key
     * @return whether a token with that key precedes the first {@code step}
     */
    public boolean has(final String key) {
        return find(key) >= 0;
    }

    /**
     * The value of the first in-scope token with this key.
     *
     * @param key the key
     * @return the value, or {@code null} when the key is absent <em>or</em> the
     *         token carried no {@code '='}. Use {@link #has} to distinguish.
     */
    public String value(final String key) {
        final int i = find(key);
        if (i < 0) {
            return null;
        }
        final String token = tokens.get(i);
        final int eq = token.indexOf('=');
        return eq < 0 ? null : token.substring(eq + 1);
    }

    /**
     * {@code pj_param} type {@code 'd'}.
     *
     * @param key          the key
     * @param defaultValue returned when the key is absent or has no value
     * @return the value parsed in the C locale
     * @throws PipelineDefinitionException if the value is present but unparseable
     */
    public double doubleValue(final String key, final double defaultValue) {
        final String raw = value(key);
        if (raw == null || raw.isEmpty()) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (final NumberFormatException e) {
            throw new PipelineDefinitionException(PipelineErrorCode.ILLEGAL_ARG_VALUE,
                    "invalid value for +" + key + ": " + raw, e);
        }
    }

    /**
     * {@code pj_param} type {@code 'b'}: {@code ''}, {@code T} and {@code t} are
     * true; {@code F} and {@code f} are false; anything else is an error.
     *
     * @param key the key
     * @return the flag, {@code false} when the key is absent
     * @throws PipelineDefinitionException on a value that is not a PROJ boolean
     */
    public boolean booleanValue(final String key) {
        final int i = find(key);
        if (i < 0) {
            return false;
        }
        final String raw = value(key);
        if (raw == null || raw.isEmpty() || "T".equals(raw) || "t".equals(raw)) {
            return true;
        }
        if ("F".equals(raw) || "f".equals(raw)) {
            return false;
        }
        throw new PipelineDefinitionException(PipelineErrorCode.ILLEGAL_ARG_VALUE,
                "+" + key + "=" + raw + " is not a PROJ boolean");
    }

    // -------------------------------------------------------------------- steps

    /**
     * The number of tokens that are exactly {@code token}, scanning the whole list
     * rather than stopping at a {@code step}.
     *
     * <p>Used for the two counts PROJ takes over a raw {@code argv}: {@code step}
     * occurrences, and {@code inv} occurrences within one step's argument list
     * ({@code pipeline.cpp:519-522}, which <em>toggles</em> per occurrence).
     *
     * @param token the exact token text
     * @return the count
     */
    public int countExact(final String token) {
        int n = 0;
        for (int i = 0; i < tokens.size(); i++) {
            if (tokens.get(i).equals(token)) {
                n++;
            }
        }
        return n;
    }

    /**
     * Split on {@code step}, dropping the {@code step} tokens themselves.
     *
     * <p>Element 0 is the <em>global</em> scope: everything before the first
     * {@code step}, which is where {@code proj=pipeline} and any pipeline-wide
     * {@code +ellps} or {@code +towgs84} live. Elements 1..n are the steps.
     *
     * @return at least one element
     */
    public List<ProjParams> splitOnStep() {
        final List<ProjParams> out = new ArrayList<ProjParams>();
        List<String> current = new ArrayList<String>();
        for (int i = 0; i < tokens.size(); i++) {
            final String token = tokens.get(i);
            if (STEP.equals(token)) {
                out.add(new ProjParams(Collections.unmodifiableList(current)));
                current = new ArrayList<String>();
            } else {
                current.add(token);
            }
        }
        out.add(new ProjParams(Collections.unmodifiableList(current)));
        return out;
    }

    /**
     * This list with {@code extra} appended.
     *
     * <p>Appending, never prepending, is the whole point: an expansion must be
     * shadowed by any token the user wrote, and first-match-wins turns "appended"
     * into "lower precedence".
     *
     * @param extra tokens to append, without {@code '+'} prefixes
     * @return a new list
     */
    public ProjParams append(final List<String> extra) {
        if (extra == null || extra.isEmpty()) {
            return this;
        }
        final List<String> out = new ArrayList<String>(tokens.size() + extra.size());
        out.addAll(tokens);
        for (int i = 0; i < extra.size(); i++) {
            String token = extra.get(i);
            if (token == null) {
                continue;
            }
            while (token.startsWith("+")) {
                token = token.substring(1);
            }
            if (!token.isEmpty()) {
                out.add(token);
            }
        }
        return new ProjParams(Collections.unmodifiableList(out));
    }

    /**
     * @param token one token to append, without a {@code '+'} prefix
     * @return a new list
     */
    public ProjParams append(final String token) {
        return append(Collections.singletonList(token));
    }

    /**
     * The tokens as {@code Proj4Parser} wants them: each with a leading
     * {@code '+'}, in order, duplicates preserved.
     *
     * <p>Order is preserved rather than deduplicated because
     * {@code Proj4Parser.createParameterMap} implements first-occurrence-wins
     * itself, so handing it the whole list reproduces PROJ's precedence.
     *
     * @return a fresh array
     */
    public String[] toProj4Args() {
        final String[] out = new String[tokens.size()];
        for (int i = 0; i < tokens.size(); i++) {
            out[i] = "+" + tokens.get(i);
        }
        return out;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tokens.size(); i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append('+').append(tokens.get(i));
        }
        return sb.toString();
    }
}
