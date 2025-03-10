/*
 * Copyright 2025, PROJ4J contributors
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
package org.locationtech.proj4j.geoapi;

import java.io.Serializable;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import org.opengis.util.GenericName;
import org.opengis.util.InternationalString;
import org.opengis.util.LocalName;
import org.opengis.util.NameSpace;
import org.opengis.util.ScopedName;


/**
 * An alternative name for an object.
 * Note that the EPSG database puts short names in aliases.
 * The long names are rather the primary object names.
 *
 * @author Martin Desruisseaux (Geomatys)
 */
@SuppressWarnings("serial")
final class Alias implements LocalName, Serializable {
    /**
     * The name to provide as an alias.
     */
    private final String name;

    /**
     * Creates a new alias.
     *
     * @param name the name to provide as an alias.
     */
    private Alias(final String name) {
        this.name = name;
    }

    /**
     * Returns the given name as an alias.
     *
     * @param  name the alias, or {@code null}
     * @return the alias, or an empty collection if the given name was null
     */
    static Collection<GenericName> wrap(final String name) {
        return (name != null) ? Collections.singletonList(new Alias(name)) : Collections.emptyList();
    }

    @Override
    public NameSpace scope() {
        return null;
    }

    @Override
    public int depth() {
        return 1;
    }

    @Override
    public List<LocalName> getParsedNames() {
        return Collections.singletonList(this);
    }

    @Override
    public LocalName head() {
        return this;
    }

    @Override
    public LocalName tip() {
        return this;
    }

    @Override
    public GenericName toFullyQualifiedName() {
        return this;
    }

    @Override
    public ScopedName push(GenericName scope) {
        throw new UnsupportedOperationException("Not supported.");
    }

    @Override
    public InternationalString toInternationalString() {
        return LocalizedString.wrap(name);
    }

    @Override
    public String toString() {
        return name;
    }

    @Override
    public int compareTo(GenericName o) {
        int c = name.compareTo(o.head().toString());
        if (c == 0) {
            c = depth() - o.depth();
        }
        return c;
    }

    @Override
    public boolean equals(Object o) {
        return (o instanceof Alias) && name.equals(((Alias) o).name);
    }

    @Override
    public int hashCode() {
        return name.hashCode() ^ getClass().hashCode();
    }
}
