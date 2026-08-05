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

/**
 * Thrown when a manifest or corpus-index file is malformed.
 *
 * <p>Parsing is strict and fails on the first bad line rather than skipping it. A manifest that
 * silently drops a line it cannot understand is worse than no manifest: the dropped entry defaults to
 * "expected to pass", so a typo in a key would turn a known failure into a build-breaking regression
 * — or, in the other direction, hide one. The offending {@linkplain #lineNumber() line number} and
 * {@linkplain #line() text} are always reported so the fix is a one-line edit.
 */
public final class ManifestFormatException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String source;
    private final int lineNumber;
    private final String line;

    /**
     * @param source a description of where the text came from, e.g. a path, or {@code "<reader>"}
     * @param lineNumber 1-based line number of the offending line
     * @param line the offending line, verbatim
     * @param problem what is wrong with it
     */
    public ManifestFormatException(String source, int lineNumber, String line, String problem) {
        super(source + ":" + lineNumber + ": " + problem + " [line was: \"" + line + "\"]");
        this.source = source;
        this.lineNumber = lineNumber;
        this.line = line;
    }

    /**
     * @param source a description of where the text came from
     * @param lineNumber 1-based line number of the offending line
     * @param line the offending line, verbatim
     * @param problem what is wrong with it
     * @param cause the underlying failure
     */
    public ManifestFormatException(String source, int lineNumber, String line, String problem, Throwable cause) {
        super(source + ":" + lineNumber + ": " + problem + " [line was: \"" + line + "\"]", cause);
        this.source = source;
        this.lineNumber = lineNumber;
        this.line = line;
    }

    /** @return description of the origin of the malformed text. */
    public String source() {
        return source;
    }

    /** @return 1-based line number of the malformed line. */
    public int lineNumber() {
        return lineNumber;
    }

    /** @return the malformed line, verbatim. */
    public String line() {
        return line;
    }
}
