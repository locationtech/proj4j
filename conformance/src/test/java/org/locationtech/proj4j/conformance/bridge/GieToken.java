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

/**
 * One entry of PROJ's {@code paralist} ({@code 9.8.1:src/init.cpp}): a key, an
 * optional value, its position in the definition, and the {@code used} flag.
 *
 * <p>{@code used} is the only bookkeeping PROJ's parameter machinery has. Every
 * successful {@code pj_param} lookup sets it, and it is what
 * {@code pj_pr_list()} ({@code src/pr_list.cpp}) reports as
 * {@code "#--- following specified but NOT used"}. It is deliberately mutable
 * here for the same reason.
 */
public final class GieToken {

    private final String key;
    private final String value;
    private final boolean hasValue;
    private final int index;
    private boolean used;

    GieToken(String key, String value, boolean hasValue, int index) {
        this.key = key;
        this.value = value;
        this.hasValue = hasValue;
        this.index = index;
    }

    /** The part before the first {@code '='}, with any leading {@code '+'} stripped. */
    public String key() {
        return key;
    }

    /**
     * The part after the first {@code '='}, or {@code null} for a flag-style token
     * such as {@code inv} or {@code step}. An empty string means the definition
     * literally said {@code key=}.
     */
    public String value() {
        return value;
    }

    /** Whether an {@code '='} was present at all. */
    public boolean hasValue() {
        return hasValue;
    }

    /** Zero-based position in the definition, duplicates included. */
    public int index() {
        return index;
    }

    /** The pipeline step separator, {@code +step}. */
    public boolean isStep() {
        return "step".equals(key);
    }

    /** Whether any lookup has matched this token. */
    public boolean used() {
        return used;
    }

    /** Set the {@code used} flag, as a successful {@code pj_param} does. */
    public void markUsed() {
        this.used = true;
    }

    /** The token as it would be written in a proj string, {@code '+'} included. */
    public String text() {
        return hasValue ? "+" + key + "=" + value : "+" + key;
    }

    @Override
    public String toString() {
        return text() + (used ? "" : " (unused)");
    }
}
