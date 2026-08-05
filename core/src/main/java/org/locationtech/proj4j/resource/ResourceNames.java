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

/**
 * The single rule deciding whether a resource name lifted out of a proj string may be handed to a
 * resolver. Shared by {@link ClasspathResourceResolver} and {@link DirectoryResourceResolver} so the
 * two cannot drift: a name refused by one is refused by the other.
 *
 * <h2>Public, because one caller lives outside this package</h2>
 *
 * <p>{@code +init=<authority>:<code>} builds a classpath resource name from the authority —
 * {@code "proj4/nad/" + authority.toLowerCase(Locale.ROOT)} — out of the same untrusted CRS string a
 * {@code +nadgrids=} token comes from, and it did so with <strong>no validation at all</strong> while
 * every grid path was guarded. {@code org.locationtech.proj4j.io.Proj4FileReader} now calls
 * {@link #violation(String)} on the authority, so {@code +init=../../foo:bar} is refused by name and
 * by rule rather than left to whatever {@code ClassLoader.getResourceAsStream} makes of it. That
 * caller is in another package, which is the whole reason this type is public: the alternative was a
 * second copy of the rule, and a second copy is a rule that drifts.
 *
 * <h2>Interior path segments are permitted, deliberately</h2>
 *
 * <p>This guard used to reject <em>any</em> name containing {@code /}, on the reasoning that grid
 * names in proj strings are bare file names. That reasoning was wrong about PROJ. PROJ resolves a
 * grid token by appending <em>the whole token</em> to each directory on its search path
 * ({@code filemanager.cpp}, steps 5 and 7), so a relative sub-path is an ordinary, supported spelling
 * — and the conformance corpus uses it heavily:
 * {@code +file=tests/tinshift_simplified_kkj_etrs.json},
 * {@code +xy_grids=tests/nkgrf03vel_realigned_xy_extract.ct2},
 * {@code +grids=tests/us_noaa_nadcon5_nad83_2007_nad83_2011_conus_extract.tif}. Refusing them made
 * roughly a hundred assertions unreachable rather than merely failing.
 *
 * <h2>Why relaxing it does not relax the security position</h2>
 *
 * <p>The guard was never the thing standing between a hostile {@code +nadgrids=} token and an
 * arbitrary file. Each resolver has a structural containment property, and this rule is the standard
 * traversal defence layered on top of it, not a substitute for it:
 *
 * <ul>
 * <li>{@link ClasspathResourceResolver} resolves through {@link ClassLoader#getResource}, which
 *     cannot address anything outside the classpath no matter what string it is given — there is no
 *     filesystem path to escape from. (It additionally refuses any URL whose protocol is not local,
 *     so a classloader configured with a remote URL cannot turn a lookup into network I/O.)</li>
 * <li>{@link DirectoryResourceResolver} <em>is</em> filesystem-backed, so it is the one with real
 *     stakes — and it re-checks containment after {@code normalize()} <em>and</em> again after
 *     {@code toRealPath()}, so a traversal or a symlink out of the root is refused by the path check
 *     even if it somehow got past this rule.</li>
 * </ul>
 *
 * <p>So what remains here is the conventional relative-path hygiene: no absolute name, no
 * {@code ..}, no {@code .}, no empty segment, and nothing whose meaning would depend on which layer
 * interprets it. Rejecting a bare {@code /} bought nothing that these do not.
 *
 * <h2>Deny-list, not allow-list, and why</h2>
 *
 * <p>An allow-list of permitted characters would be stronger in the abstract, but the population it
 * has to accept is not ours: it is every {@code grid_name} in EPSG's {@code grid_transformation}
 * plus every {@code old_proj_grid_name} in {@code grid_alternatives}. Guessing that set wrong fails
 * <em>closed on a legitimate grid</em>, silently, in whatever deployment happens to own that grid —
 * which is a worse failure than the one it prevents, given the containment properties above.
 */
public final class ResourceNames {

    private ResourceNames() {
    }

    /**
     * Why a name was refused. Named rather than boolean so a diagnostic — and a test — can state
     * <em>which</em> rule fired, instead of asserting that something, somewhere, said no.
     */
    public enum Rule {
        /** No name at all. */
        NULL("the name is null"),
        /** The empty string; {@code prefix + ""} is the prefix directory itself. */
        EMPTY("the name is empty"),
        /** A leading separator addresses a filesystem or classpath root, not the resolver's. */
        ABSOLUTE("the name starts with '/' or '\\', addressing a root rather than the resolver's"),
        /** {@code /} is the only separator proj strings and classpath resources use. */
        BACKSLASH("the name contains a '\\', which is not a separator proj4j recognises"),
        /** Includes tab, newline and NUL — a NUL is the classic path-truncation trick. */
        WHITESPACE("the name contains whitespace or a control character"),
        /** A URL escape would make the name mean different things to different layers. */
        PERCENT("the name contains a '%', so its meaning would depend on who decodes it"),
        /** A Windows drive letter, or a URL scheme. */
        COLON("the name contains a ':', which could be a drive letter or a URL scheme"),
        /** {@code ..} traverses upwards; {@code .} is a no-op spelling that only helps evade checks. */
        DOT_SEGMENT("the name contains a '.' or '..' path segment"),
        /** {@code a//b}, a leading {@code //}, or a trailing {@code /}. */
        EMPTY_SEGMENT("the name contains an empty path segment ('//' or a trailing '/')");

        private final String description;

        Rule(String description) {
            this.description = description;
        }

        /** Human-readable, for a diagnostic message. */
        public String description() {
            return description;
        }
    }

    /** {@code true} iff {@link #violation(String)} finds nothing to object to. */
    public static boolean isSafe(String resourceName) {
        return violation(resourceName) == null;
    }

    /**
     * @return the first rule the name breaks, or {@code null} if it breaks none. The order the rules
     *         are checked in is fixed, so the answer is stable and a test can assert it.
     */
    public static Rule violation(String resourceName) {
        if (resourceName == null) {
            return Rule.NULL;
        }
        if (resourceName.isEmpty()) {
            return Rule.EMPTY;
        }
        char first = resourceName.charAt(0);
        if (first == '/' || first == '\\') {
            return Rule.ABSOLUTE;
        }
        for (int i = 0; i < resourceName.length(); i++) {
            char c = resourceName.charAt(i);
            if (c == '\\') {
                return Rule.BACKSLASH;
            }
            // <= ' ' catches space, tab, CR, LF and NUL alike; 0x7f is DEL.
            if (c <= ' ' || c == 0x7f) {
                return Rule.WHITESPACE;
            }
            if (c == '%') {
                return Rule.PERCENT;
            }
            if (c == ':') {
                return Rule.COLON;
            }
        }
        for (int start = 0; ; ) {
            int slash = resourceName.indexOf('/', start);
            int end = slash < 0 ? resourceName.length() : slash;
            int length = end - start;
            if (length == 0) {
                return Rule.EMPTY_SEGMENT;
            }
            if (isDotSegment(resourceName, start, length)) {
                return Rule.DOT_SEGMENT;
            }
            if (slash < 0) {
                return null;
            }
            start = slash + 1;
        }
    }

    private static boolean isDotSegment(String s, int start, int length) {
        if (length > 2 || s.charAt(start) != '.') {
            return false;
        }
        return length == 1 || s.charAt(start + 1) == '.';
    }
}
