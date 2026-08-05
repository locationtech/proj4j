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
package org.locationtech.proj4j.vertical;

import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.InvalidValueException;

/**
 * The {@code AUTHORITY:horizontal+vertical} syntax, split into its three parts.
 *
 * <h2>The forms PROJ accepts, and the one thing that makes this non-trivial</h2>
 *
 * <p>PROJ's {@code createFromUserInput} reads a compound CRS name as two CRS references
 * joined by {@code '+'}. Both of these name the same object:
 *
 * <pre>
 * EPSG:4326+5773          the vertical code inherits the horizontal authority
 * EPSG:4326+EPSG:5773     both halves fully qualified</pre>
 *
 * <p>The trap is that {@code '+'} is also the sigil that starts every proj-string parameter,
 * so {@code "+proj=longlat +datum=WGS84"} contains three of them and is emphatically not a
 * compound name. {@link #looksLikeCompound(String)} therefore requires the {@code '+'} to sit
 * between two non-empty parts <em>neither of which contains whitespace or an {@code '='}</em>,
 * and requires the name not to start with {@code '+'} at all. Getting that wrong in the other
 * direction would be worse than not supporting compounds: a proj-string would be silently
 * truncated at its first parameter.
 *
 * <p>Immutable.
 *
 * @since 1.5
 */
public final class CompoundCrsName {

    private final String horizontal;
    private final String verticalAuthority;
    private final String verticalCode;

    private CompoundCrsName(final String horizontal, final String verticalAuthority,
                            final String verticalCode) {
        this.horizontal = horizontal;
        this.verticalAuthority = verticalAuthority;
        this.verticalCode = verticalCode;
    }

    /**
     * A cheap, conservative test that a name is a compound CRS reference rather than a
     * proj-string or a plain {@code authority:code}.
     *
     * @param name the candidate; may be {@code null}
     * @return whether {@link #parse(String)} will accept it
     */
    public static boolean looksLikeCompound(final String name) {
        if (name == null) {
            return false;
        }
        final String trimmed = name.trim();
        if (trimmed.isEmpty() || trimmed.charAt(0) == '+') {
            return false;
        }
        final int plus = trimmed.indexOf('+');
        if (plus <= 0 || plus == trimmed.length() - 1) {
            return false;
        }
        // Exactly one '+', and nothing that smells of a parameter list on either side.
        if (trimmed.indexOf('+', plus + 1) >= 0) {
            return false;
        }
        return isReference(trimmed.substring(0, plus)) && isReference(trimmed.substring(plus + 1));
    }

    private static boolean isReference(final String part) {
        if (part.isEmpty()) {
            return false;
        }
        for (int i = 0; i < part.length(); i++) {
            final char c = part.charAt(i);
            if (c == '=' || c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                return false;
            }
        }
        return true;
    }

    /**
     * Splits a compound CRS name.
     *
     * @param name a name of the form {@code EPSG:4326+5773} or {@code EPSG:4326+EPSG:5773}
     * @return the parts; never {@code null}
     * @throws InvalidValueException if the name is not a compound reference
     */
    public static CompoundCrsName parse(final String name) {
        if (!looksLikeCompound(name)) {
            throw new InvalidValueException(ErrorCause.INVALID_CRS_SYNTAX,
                    "not a compound CRS name: " + name + ". Expected AUTHORITY:code+code or "
                            + "AUTHORITY:code+AUTHORITY:code, for example EPSG:4326+5773.");
        }
        final String trimmed = name.trim();
        final int plus = trimmed.indexOf('+');
        final String left = trimmed.substring(0, plus);
        final String right = trimmed.substring(plus + 1);

        final int colon = right.indexOf(':');
        if (colon > 0) {
            return new CompoundCrsName(left, right.substring(0, colon), right.substring(colon + 1));
        }
        // Bare code: inherit the horizontal half's authority, defaulting to EPSG exactly as
        // CRSFactory.createFromName does for an unqualified code.
        final int leftColon = left.indexOf(':');
        final String authority = leftColon > 0 ? left.substring(0, leftColon) : "EPSG";
        return new CompoundCrsName(left, authority, right);
    }

    /** @return the horizontal half as written, e.g. {@code "EPSG:4326"}. */
    public String horizontal() {
        return horizontal;
    }

    /** @return the vertical half's authority, never {@code null}. */
    public String verticalAuthority() {
        return verticalAuthority;
    }

    /** @return the vertical half's code, never {@code null}. */
    public String verticalCode() {
        return verticalCode;
    }

    /** @return the vertical half as {@code AUTHORITY:code}. */
    public String verticalIdentifier() {
        return verticalAuthority + ":" + verticalCode;
    }

    @Override
    public String toString() {
        return horizontal + "+" + verticalIdentifier();
    }
}
