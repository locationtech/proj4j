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
package org.locationtech.proj4j.spi;

/**
 * One {@code supersession} row: this object has been replaced by that one.
 * <p>
 * Distinct from {@code deprecation}, and the distinction matters for operation selection.
 * <em>Deprecation</em> says an object should no longer be used at all. <em>Supersession</em> says a
 * <strong>better operation exists for the same job</strong>, and
 * {@link #sameSourceTargetCrs()} says whether the replacement connects the same pair of CRSs. A
 * superseded operation whose replacement has the same source and target must lose the ranking to that
 * replacement; one whose replacement connects a different pair must not, because it is not actually a
 * substitute.
 */
public final class DbSupersession {

    private final DbObjectRef superseded;
    private final DbObjectRef replacement;
    private final String source;
    private final boolean sameSourceTargetCrs;

    public DbSupersession(DbObjectRef superseded, DbObjectRef replacement, String source,
                          boolean sameSourceTargetCrs) {
        this.superseded = superseded;
        this.replacement = replacement;
        this.source = source;
        this.sameSourceTargetCrs = sameSourceTargetCrs;
    }

    public DbObjectRef superseded() {
        return superseded;
    }

    public DbObjectRef replacement() {
        return replacement;
    }

    /** Who says so; may be null. */
    public String source() {
        return source;
    }

    /** See the class comment. */
    public boolean sameSourceTargetCrs() {
        return sameSourceTargetCrs;
    }

    @Override
    public String toString() {
        return superseded + " superseded by " + replacement
                + (sameSourceTargetCrs ? " (same CRS pair)" : " (different CRS pair)");
    }
}
