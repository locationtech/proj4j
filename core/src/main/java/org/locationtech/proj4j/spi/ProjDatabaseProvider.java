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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.ServiceLoader;

/**
 * Factory for a {@link ProjDatabase}, discoverable through {@link ServiceLoader}.
 *
 * <h2>Discovery is opt-in, and that is the point</h2>
 * Core <strong>never</strong> scans for a provider implicitly. An implicit {@code ServiceLoader} walk
 * touches a classpath proj4j does not control, and that is precisely how a library minding its own
 * business triggers a {@code LinkageError} in somebody else's jar — the failure mode this whole design
 * exists to remove. {@link #discover(ClassLoader)} runs only when an application calls it, or when it hands the
 * result to {@code ProjContext.Builder}.
 *
 * <h2>Duplicates are rejected, not ordered by luck</h2>
 * {@link #discover(ClassLoader)} sorts by {@code (priority(), name())} and <strong>throws</strong> if two providers
 * share both. {@code ServiceLoader} iteration order follows classpath order, which varies between a
 * shaded jar, an IDE and a Spark executor; picking one arbitrarily would make the choice of database a
 * property of the deployment rather than of the code. This mirrors
 * {@code org.locationtech.proj4j.resource.ResourceResolvers}, deliberately.
 */
public interface ProjDatabaseProvider {

    /**
     * A stable identifier, e.g. {@code "pjdx"}. Must not be null or empty, and must not vary between
     * runs.
     */
    String name();

    /**
     * Lower runs first. Ties are broken by {@link #name()}; two providers with the same
     * {@code (priority, name)} are a configuration error and are rejected.
     */
    int priority();

    /**
     * Opens the database.
     *
     * @return an open database, or {@code null} if this provider's data is not on the classpath. Null
     *         rather than an exception, so that "the artifact is absent" is distinguishable from "the
     *         artifact is present and corrupt" — the second must throw.
     * @throws IOException if the data is present but unreadable, mis-versioned, or fails its integrity
     *                     check. Never swallow that: a silently absent database degrades to a
     *                     plausible wrong answer.
     */
    ProjDatabase open() throws IOException;

    /**
     * All providers visible to {@code loader}, sorted by {@code (priority, name)}.
     *
     * @param loader the class loader to scan, or {@code null} for this class's own loader
     * @return an unmodifiable, sorted list; empty if no {@code proj4j-db}-style artifact is present
     * @throws IllegalStateException if two providers share a {@code (priority, name)} pair
     */
    static List<ProjDatabaseProvider> discover(ClassLoader loader) {
        ServiceLoader<ProjDatabaseProvider> sl = loader == null
                ? ServiceLoader.load(ProjDatabaseProvider.class, ProjDatabaseProvider.class.getClassLoader())
                : ServiceLoader.load(ProjDatabaseProvider.class, loader);
        List<ProjDatabaseProvider> found = new ArrayList<ProjDatabaseProvider>();
        for (Iterator<ProjDatabaseProvider> it = sl.iterator(); it.hasNext(); ) {
            ProjDatabaseProvider p = it.next();
            if (p.name() == null || p.name().isEmpty()) {
                throw new IllegalStateException(
                        "ProjDatabaseProvider " + p.getClass().getName() + " has no name()");
            }
            found.add(p);
        }
        Collections.sort(found, new Comparator<ProjDatabaseProvider>() {
            @Override
            public int compare(ProjDatabaseProvider a, ProjDatabaseProvider b) {
                int c = Integer.compare(a.priority(), b.priority());
                return c != 0 ? c : a.name().compareTo(b.name());
            }
        });
        for (int i = 1; i < found.size(); i++) {
            ProjDatabaseProvider a = found.get(i - 1);
            ProjDatabaseProvider b = found.get(i);
            if (a.priority() == b.priority() && a.name().equals(b.name())) {
                throw new IllegalStateException("Two ProjDatabaseProviders share (priority, name) = ("
                        + a.priority() + ", " + a.name() + "): " + a.getClass().getName() + " and "
                        + b.getClass().getName()
                        + ". Which one wins would otherwise depend on classpath order.");
            }
        }
        return Collections.unmodifiableList(found);
    }

    /**
     * The first provider that yields a database, in {@code (priority, name)} order.
     *
     * @return an open database, or {@code null} if no provider has data
     * @throws IOException           if a provider's data is present but unreadable
     * @throws IllegalStateException on a duplicate {@code (priority, name)}
     */
    static ProjDatabase openFirst(ClassLoader loader) throws IOException {
        for (ProjDatabaseProvider p : discover(loader)) {
            ProjDatabase db = p.open();
            if (db != null) {
                return db;
            }
        }
        return null;
    }
}
