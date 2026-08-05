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

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;

/**
 * Locates a named data resource (a grid, today) without ever consulting ambient state.
 * <p>
 * Implementations are discovered through {@link java.util.ServiceLoader} via
 * {@code META-INF/services/org.locationtech.proj4j.resource.ResourceResolver}, so a consumer with a
 * shaded jar or an object-store backend registers a resolver and ships no proj4j patch.
 *
 * <h2>Ordering is total and deterministic</h2>
 * Discovered resolvers are sorted by {@code (priority(), name())} — <em>never</em> by
 * {@code ServiceLoader} discovery order, which varies with classpath ordering and would reintroduce
 * exactly the nondeterminism this SPI exists to remove. Two resolvers with the same
 * {@code priority()} must therefore have different {@code name()}s; see
 * {@link ResourceResolvers#serviceLoaderResolvers()}, which rejects a duplicate
 * {@code (priority, name)} pair rather than picking one arbitrarily.
 *
 * <h2>Thread-safety and I/O contract</h2>
 * {@link #resolve} and {@link #listAvailable} <strong>must be safe for concurrent
 * invocation</strong> and must not hold a lock across I/O. {@link ResourceHandle#open()} must return
 * a fresh, independently positioned reader on each call.
 *
 * <h2>What a resolver must never do</h2>
 * <ul>
 *   <li>Consult the process working directory for an unqualified name. proj4j's 1.4.x resolver did,
 *       which made the answer depend on whatever a framework had staged into a shared container
 *       work dir.</li>
 *   <li>Read an environment variable. Ambient environment must never change a numeric answer. An
 *       application that <em>wants</em> {@code PROJ_DATA} semantics constructs a
 *       {@link DirectoryResourceResolver} over it explicitly, in its own code, where it is
 *       reviewable.</li>
 *   <li>Perform network I/O without reporting {@link #isNetworkBacked()} {@code == true}.</li>
 * </ul>
 */
public interface ResourceResolver {

    /**
     * A short, stable, unique identifier used both for diagnostics and as the tie-break in the
     * resolver ordering. Must not be null or empty.
     */
    String name();

    /**
     * MUST be {@code true} for anything performing off-host I/O.
     * <p>
     * proj4j core ships no network-backed resolver at all — absent, not disabled — so that
     * <em>&ldquo;can this reach the network?&rdquo;</em> is answerable by {@code mvn dependency:tree}
     * and by {@code jar tf}, not only by reading code.
     */
    default boolean isNetworkBacked() {
        return false;
    }

    /**
     * Lower runs first. Ties are broken by {@link #name()}, so {@code ServiceLoader} order never
     * affects results.
     */
    default int priority() {
        return 100;
    }

    /**
     * @param resourceName an unqualified resource name such as {@code "conus"}. Implementations
     *                     must treat this as <strong>untrusted input</strong>: it originates in a
     *                     {@code +nadgrids=} token in a possibly per-row, possibly user-supplied
     *                     CRS string.
     * @return the handle, or {@code null} if this resolver does not have the resource. Returning
     *         {@code null} is not an error and must not be logged as one.
     */
    ResourceHandle resolve(String resourceName) throws IOException;

    /**
     * Best-effort enumeration. An empty collection means <em>&ldquo;cannot enumerate&rdquo;</em>,
     * NOT <em>&ldquo;none installed&rdquo;</em> — classpath resources are not listable without a
     * generated index. Check {@link #isEnumerable()} to tell the two apart.
     */
    default Collection<String> listAvailable() {
        return Collections.emptyList();
    }

    /**
     * @return {@code true} iff {@link #listAvailable()} returns a complete list.
     */
    default boolean isEnumerable() {
        return false;
    }
}
