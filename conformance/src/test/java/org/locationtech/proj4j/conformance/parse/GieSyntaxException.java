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
package org.locationtech.proj4j.conformance.parse;

/**
 * A <em>fatal, file-level</em> {@code .gie} lexing error — the two cases where
 * gie abandons the whole file rather than recording an assertion failure:
 *
 * <ul>
 *   <li>strict mode met a line that is not blank, not decorative, not a
 *       comment, and does not begin with a verb
 *       ({@code "unsupported command line N"}, {@code gie.cpp:1607});</li>
 *   <li>the block-delimiter count is odd or zero at EOF
 *       ({@code "Missing '</gie>'"}, {@code gie.cpp:466-474}).</li>
 * </ul>
 *
 * <p>Anything a lexer could plausibly "recover" from is <em>not</em> an error
 * here: an unknown bare word inside a non-strict {@code <gie>} block is
 * swallowed as continuation text, which {@code builtins.gie} depends on.
 */
public final class GieSyntaxException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String source;
    private final int line;

    public GieSyntaxException(String source, int line, String message) {
        super(source + (line > 0 ? ":" + line : "") + ": " + message);
        this.source = source;
        this.line = line;
    }

    /** The file the error was found in. */
    public String source() {
        return source;
    }

    /** 1-based offending line, or 0 when the error is about the file as a whole. */
    public int line() {
        return line;
    }
}
