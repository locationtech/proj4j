/*
 * Copyright 2026 The Proj4J Contributors.
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
package org.locationtech.proj4j.conformance.runner;

/**
 * Answers gie's {@code require_grid} question: is this grid file resolvable?
 *
 * <p>This is the Java stand-in for {@code proj_grid_info(filename)} plus the
 * {@code PROJ_NETWORK=ON} fallback at {@code 9.8.1:src/apps/gie.cpp:566-593}. The network branch is
 * deliberately <strong>not</strong> reproduced: the conformance module is hermetic (see the surefire
 * configuration in {@code conformance/pom.xml}) and a run whose outcome depended on CDN reachability
 * would not be a baseline. A grid we do not ship is therefore missing, and the assertions that need
 * it become {@link org.locationtech.proj4j.conformance.manifest.AssertionOutcome#SKIP} — never
 * passes.
 *
 * <p>Three of the corpus's {@code require_grid} uses are live at 9.8.1: {@code BETA2007.gsb}
 * (vendored under {@code proj-data/}, so always present) and the two GeoTIFF grids
 * {@code us_nga_egm08_25.tif} and {@code fr_ign_RAF20.tif} (not vendored, so always skipped).
 */
public interface GieGridAvailability {

    /**
     * @param gridFilename the argument of a {@code require_grid} verb, e.g. {@code BETA2007.gsb}
     * @return {@code true} if the grid can be resolved
     */
    boolean isAvailable(String gridFilename);

    /**
     * Resolves grids against the vendored {@code proj-data/} directory on the test classpath.
     *
     * <p>Stateless and immutable, so a single instance is safe to share.
     */
    final class OnClasspath implements GieGridAvailability {

        /** Where {@code conformance/sync-upstream.sh} puts the vendored grids. */
        public static final String RESOURCE_PREFIX = "/proj-data/";

        /** The shared instance. */
        public static final OnClasspath INSTANCE = new OnClasspath();

        private OnClasspath() {}

        @Override
        public boolean isAvailable(String gridFilename) {
            if (gridFilename == null) {
                return false;
            }
            String name = gridFilename.trim();
            if (name.isEmpty()) {
                return false;
            }
            // proj_grid_info() takes a bare filename and searches PROJ_LIB; there is no notion of a
            // path here, so a slash would be a corpus bug rather than something to resolve.
            return OnClasspath.class.getResource(RESOURCE_PREFIX + name) != null;
        }

        @Override
        public String toString() {
            return "GieGridAvailability.OnClasspath[" + RESOURCE_PREFIX + "]";
        }
    }

    /** Every grid is missing — every {@code require_grid} block skips. For tests. */
    final class NoneAvailable implements GieGridAvailability {

        /** The shared instance. */
        public static final NoneAvailable INSTANCE = new NoneAvailable();

        private NoneAvailable() {}

        @Override
        public boolean isAvailable(String gridFilename) {
            return false;
        }

        @Override
        public String toString() {
            return "GieGridAvailability.NoneAvailable";
        }
    }

    /** Every grid is present — nothing skips. For tests. */
    final class AllAvailable implements GieGridAvailability {

        /** The shared instance. */
        public static final AllAvailable INSTANCE = new AllAvailable();

        private AllAvailable() {}

        @Override
        public boolean isAvailable(String gridFilename) {
            return true;
        }

        @Override
        public String toString() {
            return "GieGridAvailability.AllAvailable";
        }
    }
}
