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

/**
 * A located, not-yet-read resource, together with enough provenance to answer
 * <em>&ldquo;which bytes did this executor actually use?&rdquo;</em> without guessing.
 * <p>
 * A handle is immutable and safe to share between threads; the readers it hands out are not.
 */
public interface ResourceHandle {

    /**
     * The requested resource name, e.g. {@code "us_noaa_conus.tif"} or {@code "conus"}.
     */
    String name();

    /**
     * Where the bytes come from, in a form a human can act on, e.g.
     * {@code "classpath:/proj4j-data/grids/conus"} or {@code "file:/srv/grids/conus"}.
     * <p>
     * This is the value reported by grid introspection, and it is the single most useful datum when
     * two executors disagree about a coordinate.
     */
    String origin();

    /**
     * Size in bytes, or {@code -1} if the resolver cannot determine it without reading.
     */
    long sizeBytes();

    /**
     * Opens a <strong>fresh, independently positioned</strong> reader. Never returns a shared or
     * cached reader, so two threads may each call this and read concurrently.
     */
    SeekableByteReader open() throws IOException;
}
