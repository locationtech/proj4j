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
import java.util.Locale;
import org.opengis.util.InternationalString;


/**
 * A string in a specific locale.
 * In the current version, the locale is unspecified.
 *
 * @author Martin Desruisseaux (Geomatys)
 */
@SuppressWarnings("serial")
final class LocalizedString implements InternationalString, Serializable {
    /**
     * The "not known" value. ISO 19111 requires that we return this string
     * if the scope of a datum or coordinate operation is unknown.
     */
    static final LocalizedString UNKNOWN = new LocalizedString("not known");

    /**
     * The localized text.
     */
    private final String text;

    /**
     * Creates a new international string.
     *
     * @param text the localized text
     */
    private LocalizedString(final String text) {
        this.text = text;
    }

    /**
     * Returns the given text as an international string.
     *
     * @param  text the localized text, or {@code null}
     * @return the international string, or {@code null} if the given text was null
     */
    static LocalizedString wrap(final String text) {
        return (text != null) ? new LocalizedString(text) : null;
    }

    @Override
    public String toString() {
        return text;
    }

    @Override
    public String toString(Locale locale) {
        return text;
    }

    @Override
    public int length() {
        return text.length();
    }

    @Override
    public char charAt(int index) {
        return text.charAt(index);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
        return text.subSequence(start, end);
    }

    @Override
    public int compareTo(InternationalString o) {
        return text.compareTo(o.toString());
    }

    @Override
    public boolean equals(Object o) {
        return (o instanceof LocalizedString) && text.equals(((LocalizedString) o).text);
    }

    @Override
    public int hashCode() {
        return text.hashCode() ^ getClass().hashCode();
    }
}
