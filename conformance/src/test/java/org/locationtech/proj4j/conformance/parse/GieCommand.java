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
 * One assembled {@code .gie} command: a verb plus its normalised argument
 * string. Immutable.
 *
 * <p>{@link #line()} is the 1-based number of the line the verb appeared on,
 * <em>not</em> the last line of a continuation. It is what a failure report
 * should quote, and it is the stable identity of an assertion across corpus
 * re-syncs. (Note that gie's own {@code F->lineno} ends up holding the
 * <em>last</em> line of a strict-mode continuation; {@link #lastLine()}
 * carries that value for diagnostic parity.)
 */
public final class GieCommand {

    private final GieVerb verb;
    private final String args;
    private final int line;
    private final int lastLine;
    private final String raw;

    public GieCommand(GieVerb verb, String args, int line, int lastLine, String raw) {
        if (verb == null) {
            throw new IllegalArgumentException("verb");
        }
        this.verb = verb;
        this.args = args == null ? "" : args;
        this.line = line;
        this.lastLine = lastLine;
        this.raw = raw == null ? "" : raw;
    }

    /** The verb this line resolved to under prefix matching. */
    public GieVerb verb() {
        return verb;
    }

    /**
     * The assembled argument string after {@code pj_chomp} + {@code pj_shrink}:
     * continuation lines joined, comments gone, whitespace collapsed,
     * {@code '+'} and {@code ';'} removed, {@code ','} and {@code '='} greedy.
     * Never {@code null}; empty when the verb takes no arguments.
     */
    public String args() {
        return args;
    }

    /** 1-based source line of the verb's first line. */
    public int line() {
        return line;
    }

    /** 1-based source line of the command's last physical line. */
    public int lastLine() {
        return lastLine;
    }

    /**
     * The raw, unassembled source text of every physical line that contributed
     * to this command, joined with {@code '\n'}. For diagnostics only.
     */
    public String raw() {
        return raw;
    }

    @Override
    public String toString() {
        return line + ": " + verb.token() + (args.isEmpty() ? "" : " " + args);
    }
}
