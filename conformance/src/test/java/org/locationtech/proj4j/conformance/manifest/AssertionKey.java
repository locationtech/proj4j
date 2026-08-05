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
package org.locationtech.proj4j.conformance.manifest;

import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A stable, human-readable identity for one {@code .gie} assertion.
 *
 * <h2>Rendered form</h2>
 *
 * <pre>
 *   gie/builtins.gie#137:4@3f9c1ab0
 *   &lt;corpus-relative path&gt; '#' &lt;operation-block index&gt; ':' &lt;assertion index&gt; '@' &lt;content hash&gt;
 * </pre>
 *
 * <h2>Why this composite, and not something simpler</h2>
 *
 * <p>The expected-outcome manifest is keyed per assertion and must survive two very different kinds
 * of change: <em>our</em> code improving over ~14 stages, and <em>upstream's</em> corpus being
 * re-vendored at a future PROJ revision. Each of the obvious single-field keys fails one of those:
 *
 * <ul>
 *   <li><strong>File + line number</strong> is the most natural key and the worst one. Inserting a
 *       single test at the top of {@code builtins.gie} — upstream does this constantly — renumbers
 *       every one of the 2,185 expectations below it, so a re-vendor turns the whole file over as
 *       "removed" plus "added" and the baseline is destroyed. Line numbers are also not even stable
 *       within one file version, because a {@code \}-continued {@code operation} spans several
 *       physical lines.</li>
 *   <li><strong>File + global ordinal</strong> (the <em>n</em>-th assertion in the file) is stable
 *       against reflow but not against insertion: adding one assertion shifts every later ordinal by
 *       one, and each shifted entry silently re-points at a <em>different</em> assertion. That is the
 *       dangerous failure mode — the manifest still parses, still matches, and now excuses the wrong
 *       test.</li>
 *   <li><strong>A content hash alone</strong> is stable against both, but is not unique: the corpus
 *       repeats identical {@code expect} lines under different operations, and {@code builtins.gie}
 *       contains whole repeated accept/expect pairs. It also sorts randomly, so a regenerated
 *       manifest produces an unreadable diff.</li>
 * </ul>
 *
 * <p>The composite takes the useful property from each:
 *
 * <ol>
 *   <li><strong>Corpus-relative path</strong> ({@code gie/builtins.gie}, {@code gigs/5110.gie.failing}).
 *       Groups the manifest by file, which is how humans and the per-file report read it.</li>
 *   <li><strong>Operation-block index</strong>, 0-based, incremented by each {@code operation} verb
 *       <em>and</em> by each completed {@code crs_src}+{@code crs_dst} pair — the two things that
 *       {@code gie.cpp} treats as starting a new test object and that reset {@code direction},
 *       {@code tolerance}, {@code ignore} and {@code skip_test}. Blocking by the thing that resets
 *       state means an edit inside one block cannot renumber another.</li>
 *   <li><strong>Assertion index within the block</strong>, 0-based over {@code expect} and
 *       {@code roundtrip} in source order. Blocks are small (the GIGS files have exactly three, most
 *       {@code builtins.gie} blocks have a handful of assertions), so the blast radius of an
 *       insertion is one block, not one file.</li>
 *   <li><strong>An 8-hex-digit content hash</strong> of the normalised assertion text
 *       <em>together with</em> the normalised operation definition. This is the anti-silent-repoint
 *       guard: if upstream edits either the coordinate being asserted or the projection definition it
 *       is asserted against, the hash changes, so the old key vanishes and a new one appears. The
 *       diff then reports {@code DISAPPEARED} + {@code NEW} — loud, reviewable — instead of quietly
 *       carrying an expected-failure excuse over to an assertion nobody has ever run. The operation
 *       text is included because {@code expect 1 2} is meaningless without it; two blocks can hold
 *       byte-identical assertions that mean entirely different things.</li>
 * </ol>
 *
 * <p>8 hex digits (32 bits of SHA-1) is deliberately short: it has to be readable in a TSV line and
 * typed into a {@code grep}. Collisions only matter <em>within one (path, block, index) triple</em>,
 * where there is exactly one assertion, so the hash is a change-detector rather than a discriminator
 * and 32 bits is ample. SHA-1 is used as a content fingerprint, never for security.
 *
 * <h2>Normalisation</h2>
 *
 * <p>{@link #normalise(String)} implements a deliberately <em>self-contained</em> approximation of
 * {@code pj_chomp} + {@code pj_shrink} ({@code 9.8.1:src/internal.cpp:153,192}): strip {@code #}
 * comments, join {@code \} continuations, drop {@code +} and {@code ;}, collapse whitespace, and make
 * {@code ,} and {@code =} greedy. It intentionally does <em>not</em> call into the {@code .gie} lexer.
 * Assertion identity must change when <em>upstream text</em> changes and at no other time; if it were
 * computed by the lexer, every refinement of our own parser would silently rewrite every key in the
 * manifest and destroy the baseline. The cost of the duplication is that identity is insensitive to
 * lexer-level distinctions (a purely cosmetic upstream reflow keeps its key) — which is exactly the
 * behaviour wanted.
 *
 * <h2>Ordering</h2>
 *
 * <p>The natural order is (path, block index, assertion index, hash): source order within a file,
 * files alphabetically. A regenerated manifest therefore always lists the same assertion in the same
 * place, so regeneration produces a minimal, reviewable diff.
 *
 * <p>Instances are immutable and safe to use as map and set keys.
 */
public final class AssertionKey implements Comparable<AssertionKey> {

    /** Digest used for {@link #contentHash(String, String)}. A fingerprint, not a security control. */
    public static final String CONTENT_HASH_ALGORITHM = "SHA-1";

    /** Number of hex digits retained from the digest. */
    public static final int CONTENT_HASH_LENGTH = 8;

    private static final Pattern CONTENT_HASH_PATTERN = Pattern.compile("[0-9a-f]{" + CONTENT_HASH_LENGTH + "}");
    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");
    private static final Pattern GREEDY_PUNCTUATION = Pattern.compile("\\s*([,=])\\s*");
    private static final Pattern RENDERED = Pattern.compile("^(.+)#(\\d+):(\\d+)@([0-9a-f]{" + CONTENT_HASH_LENGTH + "})$");
    private static final char HASH_FIELD_SEPARATOR = 0x1f;
    private static final String UTF_8 = "UTF-8";

    private final String filePath;
    private final int operationBlockIndex;
    private final int assertionIndex;
    private final String contentHash;

    private AssertionKey(String filePath, int operationBlockIndex, int assertionIndex, String contentHash) {
        this.filePath = filePath;
        this.operationBlockIndex = operationBlockIndex;
        this.assertionIndex = assertionIndex;
        this.contentHash = contentHash;
    }

    /**
     * Creates a key from an already-computed content hash.
     *
     * @param filePath corpus-relative path, e.g. {@code gie/builtins.gie}; may not be empty and may
     *     not contain {@code '#'}, a tab or a newline (the first would break {@link #parse(String)},
     *     the others the TSV manifest)
     * @param operationBlockIndex 0-based index of the enclosing operation block, non-negative
     * @param assertionIndex 0-based index of the assertion within that block, non-negative
     * @param contentHash exactly {@value #CONTENT_HASH_LENGTH} lower-case hex digits
     * @return the key
     * @throws IllegalArgumentException if any component is malformed
     */
    public static AssertionKey of(String filePath, int operationBlockIndex, int assertionIndex, String contentHash) {
        if (filePath == null || filePath.isEmpty()) {
            throw new IllegalArgumentException("file path must not be empty");
        }
        if (filePath.indexOf('#') >= 0 || filePath.indexOf('\t') >= 0 || filePath.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("file path must not contain '#', a tab or a newline: \"" + filePath + "\"");
        }
        if (operationBlockIndex < 0) {
            throw new IllegalArgumentException("operation block index must be >= 0, was " + operationBlockIndex);
        }
        if (assertionIndex < 0) {
            throw new IllegalArgumentException("assertion index must be >= 0, was " + assertionIndex);
        }
        if (contentHash == null || !CONTENT_HASH_PATTERN.matcher(contentHash).matches()) {
            throw new IllegalArgumentException(
                    "content hash must be " + CONTENT_HASH_LENGTH + " lower-case hex digits, was \"" + contentHash + "\"");
        }
        return new AssertionKey(filePath, operationBlockIndex, assertionIndex, contentHash);
    }

    /**
     * Creates a key, computing the content hash from the assertion and its operation definition.
     *
     * @param filePath corpus-relative path
     * @param operationBlockIndex 0-based operation-block index
     * @param assertionIndex 0-based assertion index within the block
     * @param operationDefinition the raw text of the governing {@code operation} (or the
     *     {@code crs_src}/{@code crs_dst} pair rendered as {@code "<src> -> <dst>"}); may be
     *     {@code null} when no operation was in force
     * @param assertionText the raw text of the {@code expect}/{@code roundtrip} line, conventionally
     *     including its verb and, for {@code expect}, the preceding {@code accept} — whatever the
     *     caller passes must be consistent across runs
     * @return the key
     */
    public static AssertionKey compute(
            String filePath,
            int operationBlockIndex,
            int assertionIndex,
            String operationDefinition,
            String assertionText) {
        return of(filePath, operationBlockIndex, assertionIndex, contentHash(operationDefinition, assertionText));
    }

    /**
     * Computes the short content fingerprint of an assertion in the context of its operation.
     *
     * @param operationDefinition operation text, may be {@code null}
     * @param assertionText assertion text, may be {@code null}
     * @return {@value #CONTENT_HASH_LENGTH} lower-case hex digits
     */
    public static String contentHash(String operationDefinition, String assertionText) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance(CONTENT_HASH_ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(CONTENT_HASH_ALGORITHM + " is required by every JRE", e);
        }
        digest.update(utf8(normalise(operationDefinition)));
        digest.update((byte) HASH_FIELD_SEPARATOR);
        digest.update(utf8(normalise(assertionText)));
        byte[] full = digest.digest();
        StringBuilder hex = new StringBuilder(CONTENT_HASH_LENGTH);
        for (int i = 0; hex.length() < CONTENT_HASH_LENGTH; i++) {
            int b = full[i] & 0xff;
            hex.append(Character.forDigit(b >>> 4, 16));
            hex.append(Character.forDigit(b & 0x0f, 16));
        }
        return hex.substring(0, CONTENT_HASH_LENGTH);
    }

    /**
     * Normalises {@code .gie} source text for hashing: a self-contained approximation of
     * {@code pj_chomp} + {@code pj_shrink}.
     *
     * <p>Strips {@code #} comments, drops a trailing {@code \} continuation marker and joins lines
     * with a single space, removes {@code +} and {@code ;}, collapses whitespace runs, removes
     * whitespace around {@code ,} and {@code =}, and trims. Case is preserved: an upstream case change
     * is a real change.
     *
     * @param text raw text, may be {@code null}
     * @return the normalised form, never {@code null}
     */
    public static String normalise(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder joined = new StringBuilder(text.length());
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].replace('\r', ' ').replace('\t', ' ');
            int comment = line.indexOf('#');
            if (comment >= 0) {
                line = line.substring(0, comment);
            }
            int end = line.length();
            while (end > 0 && line.charAt(end - 1) == ' ') {
                end--;
            }
            line = line.substring(0, end);
            if (line.endsWith("\\")) {
                line = line.substring(0, line.length() - 1);
            }
            if (joined.length() > 0) {
                joined.append(' ');
            }
            joined.append(line);
        }
        String shrunk = joined.toString().replace("+", "").replace(";", "");
        shrunk = WHITESPACE_RUN.matcher(shrunk).replaceAll(" ");
        shrunk = GREEDY_PUNCTUATION.matcher(shrunk).replaceAll("$1");
        return shrunk.trim();
    }

    /**
     * Parses the rendered form produced by {@link #toString()}. Strict: anything that does not match
     * {@code <path>#<int>:<int>@<8 hex>} is rejected.
     *
     * @param rendered the rendered key
     * @return the parsed key
     * @throws IllegalArgumentException if {@code rendered} is not a well-formed key
     */
    public static AssertionKey parse(String rendered) {
        if (rendered == null) {
            throw new IllegalArgumentException("assertion key must not be null");
        }
        Matcher matcher = RENDERED.matcher(rendered);
        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                    "malformed assertion key (expected <path>#<block>:<index>@<8 hex digits>): \"" + rendered + "\"");
        }
        int block;
        int index;
        try {
            block = Integer.parseInt(matcher.group(2));
            index = Integer.parseInt(matcher.group(3));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("assertion key has out-of-range indices: \"" + rendered + "\"", e);
        }
        return of(matcher.group(1), block, index, matcher.group(4));
    }

    /** @return the corpus-relative file path, e.g. {@code gie/builtins.gie}. */
    public String filePath() {
        return filePath;
    }

    /** @return 0-based index of the operation block within the file. */
    public int operationBlockIndex() {
        return operationBlockIndex;
    }

    /** @return 0-based index of the assertion within its operation block. */
    public int assertionIndex() {
        return assertionIndex;
    }

    /** @return the {@value #CONTENT_HASH_LENGTH}-hex-digit content fingerprint. */
    public String contentHash() {
        return contentHash;
    }

    /**
     * Total order: path, then operation block, then assertion index, then hash. This is source order
     * within a file and alphabetical order across files, which is what makes a regenerated manifest
     * diff minimally.
     */
    @Override
    public int compareTo(AssertionKey other) {
        int byPath = filePath.compareTo(other.filePath);
        if (byPath != 0) {
            return byPath;
        }
        if (operationBlockIndex != other.operationBlockIndex) {
            return operationBlockIndex < other.operationBlockIndex ? -1 : 1;
        }
        if (assertionIndex != other.assertionIndex) {
            return assertionIndex < other.assertionIndex ? -1 : 1;
        }
        return contentHash.compareTo(other.contentHash);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AssertionKey)) {
            return false;
        }
        AssertionKey other = (AssertionKey) o;
        return operationBlockIndex == other.operationBlockIndex
                && assertionIndex == other.assertionIndex
                && filePath.equals(other.filePath)
                && contentHash.equals(other.contentHash);
    }

    @Override
    public int hashCode() {
        int result = filePath.hashCode();
        result = 31 * result + operationBlockIndex;
        result = 31 * result + assertionIndex;
        result = 31 * result + contentHash.hashCode();
        return result;
    }

    /** @return the rendered form, round-trippable through {@link #parse(String)}. */
    @Override
    public String toString() {
        return filePath + '#' + operationBlockIndex + ':' + assertionIndex + '@' + contentHash;
    }

    private static byte[] utf8(String s) {
        try {
            return s.getBytes(UTF_8);
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 is required by every JRE", e);
        }
    }
}
