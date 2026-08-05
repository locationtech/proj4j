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

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * A lexed {@code .gie} file: its source path and the ordered commands gie
 * would have dispatched. Immutable.
 */
public final class GieFile {

    private final String source;
    private final List<GieCommand> commands;

    public GieFile(String source, List<GieCommand> commands) {
        this.source = source;
        this.commands = Collections.unmodifiableList(
                new ArrayList<GieCommand>(commands == null ? Collections.<GieCommand>emptyList() : commands));
    }

    /** Where this came from — a file path, or a synthetic name for a string. */
    public String source() {
        return source;
    }

    /** The commands, in dispatch order. Unmodifiable. */
    public List<GieCommand> commands() {
        return commands;
    }

    public int size() {
        return commands.size();
    }

    /** All commands with the given verb, in order. */
    public List<GieCommand> commands(GieVerb verb) {
        List<GieCommand> out = new ArrayList<GieCommand>();
        for (GieCommand c : commands) {
            if (c.verb() == verb) {
                out.add(c);
            }
        }
        return Collections.unmodifiableList(out);
    }

    /** Count of commands per verb. Verbs with no occurrences are absent. */
    public Map<GieVerb, Integer> verbCounts() {
        Map<GieVerb, Integer> counts = new EnumMap<GieVerb, Integer>(GieVerb.class);
        for (GieCommand c : commands) {
            Integer n = counts.get(c.verb());
            counts.put(c.verb(), n == null ? 1 : n + 1);
        }
        return counts;
    }

    @Override
    public String toString() {
        return source + " (" + commands.size() + " commands)";
    }
}
