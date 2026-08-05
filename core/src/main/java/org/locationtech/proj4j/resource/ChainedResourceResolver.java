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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;

/**
 * First match wins, in the order given. The order is fixed at construction and never re-derived, so
 * the same inputs resolve to the same bytes on every JVM and every executor.
 */
public final class ChainedResourceResolver implements ResourceResolver {

    private final List<ResourceResolver> delegates;
    private final String name;

    public ChainedResourceResolver(List<ResourceResolver> delegates) {
        this.delegates = Collections.unmodifiableList(new ArrayList<ResourceResolver>(delegates));
        StringBuilder sb = new StringBuilder("chain[");
        for (int i = 0; i < this.delegates.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(this.delegates.get(i).name());
        }
        this.name = sb.append(']').toString();
    }

    public List<ResourceResolver> delegates() {
        return delegates;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public boolean isNetworkBacked() {
        for (ResourceResolver r : delegates) {
            if (r.isNetworkBacked()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int priority() {
        return delegates.isEmpty() ? 100 : delegates.get(0).priority();
    }

    @Override
    public ResourceHandle resolve(String resourceName) throws IOException {
        IOException firstFailure = null;
        for (ResourceResolver r : delegates) {
            try {
                ResourceHandle h = r.resolve(resourceName);
                if (h != null) {
                    return h;
                }
            } catch (IOException e) {
                // A resolver that is broken must not shadow a later resolver that works, but the
                // failure must not vanish either: it is reported if nothing else resolves.
                if (firstFailure == null) {
                    firstFailure = e;
                }
            }
        }
        if (firstFailure != null) {
            throw firstFailure;
        }
        return null;
    }

    /**
     * The resolver in this chain that actually produced {@code handle}'s bytes, or {@code null}.
     * Needed because {@link #resolve} intentionally erases which delegate answered, while grid
     * introspection has to report it.
     */
    public ResourceResolver resolverOf(String resourceName) {
        for (ResourceResolver r : delegates) {
            try {
                if (r.resolve(resourceName) != null) {
                    return r;
                }
            } catch (IOException e) {
                // keep looking; resolve() above reports the failure
            }
        }
        return null;
    }

    @Override
    public Collection<String> listAvailable() {
        TreeSet<String> all = new TreeSet<String>();
        for (ResourceResolver r : delegates) {
            if (r.isEnumerable()) {
                all.addAll(r.listAvailable());
            }
        }
        return Collections.unmodifiableList(new ArrayList<String>(all));
    }

    @Override
    public boolean isEnumerable() {
        for (ResourceResolver r : delegates) {
            if (!r.isEnumerable()) {
                return false;
            }
        }
        return true;
    }
}
