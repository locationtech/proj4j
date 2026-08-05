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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Assembles the one deterministic resolver chain proj4j uses to find data.
 *
 * <pre>
 * 1. explicit resolvers, in {@link #addResolver} call order
 * 2. {@link ServiceLoader} results, sorted by (priority(), name())
 * 3. built-in  classpath:/proj4j-data/grids/     (the grid packs)
 * 4. built-in  classpath:/proj4/nad/             (legacy proj4j-epsg compatibility)
 * </pre>
 *
 * <h2>What is deliberately absent</h2>
 * <ul>
 *   <li><strong>The process working directory.</strong> proj4j 1.4.x tried
 *       {@code new File(gridName)} <em>first</em>. On a Spark or Flink executor the CWD is a
 *       framework-chosen container work dir, shared between tasks and writable by {@code --files}
 *       staging, shuffle spill and arbitrary user code, so a file named {@code conus} landing there
 *       silently outranked the packaged grid: the same CRS pair and the same coordinate could give a
 *       different answer per executor and per run. It was also an untrusted-input file-open
 *       primitive, since {@code gridName} comes from a {@code +nadgrids=} token in a possibly
 *       per-row CRS string, and {@code new File} accepts {@code ../} and absolute paths. PROJ itself
 *       puts the CWD at step 11 of 12; proj4j put it at step 1 of 2. It is now at no step at all.</li>
 *   <li><strong>Every environment variable.</strong> No {@code PROJ_DATA}, no {@code PROJ_LIB}, no
 *       {@code PROJ_NETWORK}. Ambient environment must never change a numeric answer. An application
 *       that wants those semantics adds a {@link DirectoryResourceResolver} itself, in its own code.</li>
 *   <li><strong>All network code.</strong> Absent, not disabled — so
 *       <em>&ldquo;can this reach the network?&rdquo;</em> is answered by {@code mvn dependency:tree}
 *       and {@code jar tf}, not by auditing flags. A discovered resolver reporting
 *       {@link ResourceResolver#isNetworkBacked()} is rejected at chain-construction time unless the
 *       application called {@link #setAllowNetwork(boolean) setAllowNetwork(true)} — it fails when
 *       the chain is built, not on some unlucky row four million rows in.</li>
 * </ul>
 *
 * <h2>Total ordering</h2>
 * {@code ServiceLoader} iteration order follows classpath order, which differs between a dev IDE, a
 * shaded jar and a Spark executor with {@code --jars}. Sorting by {@code (priority, name)} is what
 * makes discovery order irrelevant. Two service-provided resolvers sharing both a priority and a name
 * are rejected outright rather than silently ordered by luck.
 */
public final class ResourceResolvers {

    /** Where grid packs put their data. Hyphenated so it is not a valid Java package name, which
     * keeps JPMS from encapsulating it in a resource-only module. */
    public static final String GRID_PREFIX = "proj4j-data/grids";

    /** Legacy prefix used by {@code proj4j-epsg} 1.4.x and earlier. Kept last, for compatibility. */
    public static final String LEGACY_GRID_PREFIX = "proj4/nad";

    /** Generated manifest inside a grid pack that makes the classpath resolver enumerable. */
    public static final String GRID_INDEX = "INDEX";

    private static final Comparator<ResourceResolver> ORDER = new Comparator<ResourceResolver>() {
        @Override
        public int compare(ResourceResolver a, ResourceResolver b) {
            int byPriority = Integer.compare(a.priority(), b.priority());
            if (byPriority != 0) {
                return byPriority;
            }
            return a.name().compareTo(b.name());
        }
    };

    private static final CopyOnWriteArrayList<ResourceResolver> EXPLICIT =
            new CopyOnWriteArrayList<ResourceResolver>();

    private static volatile boolean allowNetwork = false;

    /** Memoised chain; invalidated by any mutation. */
    private static volatile ChainedResourceResolver chain;

    private ResourceResolvers() {
    }

    /**
     * Appends a resolver ahead of everything discovered or built in. Call order is preserved and is
     * part of the contract.
     *
     * @throws IllegalStateException if the resolver is network-backed and network access has not been
     *                               explicitly allowed.
     */
    public static void addResolver(ResourceResolver resolver) {
        if (resolver == null) {
            throw new IllegalArgumentException("resolver");
        }
        if (resolver.name() == null || resolver.name().isEmpty()) {
            throw new IllegalArgumentException("resolver.name() must be non-empty: " + resolver);
        }
        rejectIfNetworkBacked(resolver);
        EXPLICIT.add(resolver);
        chain = null;
    }

    /** Removes all explicitly added resolvers. Discovered and built-in resolvers are unaffected. */
    public static void clearResolvers() {
        EXPLICIT.clear();
        chain = null;
    }

    /** Explicitly added resolvers, in call order. */
    public static List<ResourceResolver> explicitResolvers() {
        return Collections.unmodifiableList(new ArrayList<ResourceResolver>(EXPLICIT));
    }

    /**
     * proj4j core contains no network code, so this is {@code false} and there is nothing to turn on.
     * It exists so that {@code isNetworkEnabled()} is a question with an answer rather than a matter
     * of reading the classpath.
     */
    public static boolean isNetworkEnabled() {
        return allowNetwork && chainContainsNetworkResolver();
    }

    /**
     * Opts in to network-backed resolvers. Without a network-backed resolver on the classpath this
     * changes nothing: turning the network on requires the artifact <em>and</em> this call.
     */
    public static void setAllowNetwork(boolean allow) {
        allowNetwork = allow;
        chain = null;
    }

    /**
     * {@link ServiceLoader}-discovered resolvers, sorted by {@code (priority(), name())}.
     *
     * @throws ServiceConfigurationError if two providers share both a priority and a name, which
     *                                   would leave the order decided by classpath layout.
     */
    public static List<ResourceResolver> serviceLoaderResolvers() {
        List<ResourceResolver> found = new ArrayList<ResourceResolver>();
        ServiceLoader<ResourceResolver> loader = ServiceLoader.load(ResourceResolver.class);
        Iterator<ResourceResolver> it = loader.iterator();
        while (it.hasNext()) {
            ResourceResolver r = it.next();
            if (r.name() == null || r.name().isEmpty()) {
                throw new ServiceConfigurationError(
                        "ResourceResolver " + r.getClass().getName() + " has a null or empty name()");
            }
            found.add(r);
        }
        Collections.sort(found, ORDER);
        for (int i = 1; i < found.size(); i++) {
            ResourceResolver a = found.get(i - 1);
            ResourceResolver b = found.get(i);
            if (a.priority() == b.priority() && a.name().equals(b.name())) {
                throw new ServiceConfigurationError("Two ResourceResolver providers share priority "
                        + a.priority() + " and name '" + a.name() + "' (" + a.getClass().getName()
                        + " and " + b.getClass().getName() + "). The chain order would then depend on "
                        + "classpath ordering; give them distinct names or priorities.");
            }
        }
        return Collections.unmodifiableList(found);
    }

    /** Built-in resolvers, in fixed order: grid packs, then the legacy {@code proj4j-epsg} prefix. */
    public static List<ResourceResolver> builtInResolvers() {
        ClassLoader cl = classLoader();
        List<ResourceResolver> built = new ArrayList<ResourceResolver>(2);
        built.add(new ClasspathResourceResolver(cl, GRID_PREFIX, GRID_INDEX, 1000));
        built.add(new ClasspathResourceResolver(cl, LEGACY_GRID_PREFIX, null, 1001));
        return Collections.unmodifiableList(built);
    }

    /**
     * The single chain used for all data resolution. Memoised, first-match-wins, and wrapped so that
     * a missing resource is probed once rather than once per row.
     */
    public static ChainedResourceResolver resolver() {
        ChainedResourceResolver c = chain;
        if (c == null) {
            synchronized (ResourceResolvers.class) {
                c = chain;
                if (c == null) {
                    c = buildChain();
                    chain = c;
                }
            }
        }
        return c;
    }

    private static ChainedResourceResolver buildChain() {
        List<ResourceResolver> ordered = new ArrayList<ResourceResolver>();
        ordered.addAll(EXPLICIT);
        ordered.addAll(serviceLoaderResolvers());
        ordered.addAll(builtInResolvers());
        List<ResourceResolver> wrapped = new ArrayList<ResourceResolver>(ordered.size());
        for (ResourceResolver r : ordered) {
            rejectIfNetworkBacked(r);
            wrapped.add(r instanceof CachingResourceResolver ? r : new CachingResourceResolver(r));
        }
        return new ChainedResourceResolver(wrapped);
    }

    private static void rejectIfNetworkBacked(ResourceResolver r) {
        if (r.isNetworkBacked() && !allowNetwork) {
            throw new IllegalStateException("ResourceResolver '" + r.name() + "' ("
                    + r.getClass().getName() + ") reports isNetworkBacked() == true, but network "
                    + "access has not been enabled. proj4j core ships no network code; a "
                    + "network-backed resolver must be both added to the classpath and enabled via "
                    + "ResourceResolvers.setAllowNetwork(true).");
        }
    }

    private static boolean chainContainsNetworkResolver() {
        return resolver().isNetworkBacked();
    }

    private static ClassLoader classLoader() {
        // The class's own loader, not the thread context loader: the context loader is set by the
        // framework and varies per task, which is precisely the ambient input being removed here.
        ClassLoader cl = ResourceResolvers.class.getClassLoader();
        return cl != null ? cl : ClassLoader.getSystemClassLoader();
    }

    /**
     * A multi-line, human-readable description of exactly how data is being located, suitable for
     * logging once per JVM. Names every resolver in order, says whether each can be enumerated, and
     * states the network position explicitly so an empty grid list is never mistaken for
     * "nothing installed".
     */
    public static String describeResolution() {
        StringBuilder sb = new StringBuilder();
        sb.append("proj4j data resolution chain (first match wins):\n");
        int i = 1;
        for (ResourceResolver r : resolver().delegates()) {
            sb.append("  ").append(i++).append(". ").append(r.name())
                    .append("  priority=").append(r.priority())
                    .append("  enumerable=").append(r.isEnumerable());
            if (r.isEnumerable()) {
                sb.append("  entries=").append(r.listAvailable().size());
            }
            sb.append('\n');
        }
        sb.append("  working directory: NEVER CONSULTED\n");
        sb.append("  environment variables: NONE READ (no PROJ_DATA, PROJ_LIB, PROJ_NETWORK)\n");
        sb.append("  network: ").append(isNetworkEnabled() ? "ENABLED"
                : "ABSENT (no network-backed resolver on the classpath)").append('\n');
        return sb.toString();
    }
}
