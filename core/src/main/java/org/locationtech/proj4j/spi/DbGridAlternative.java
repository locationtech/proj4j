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
 * The mapping from an authority's grid file name to the file PROJ actually reads, plus what kind of
 * file it is.
 * <p>
 * Three fields here are load-bearing for proj4j specifically:
 * <ul>
 *   <li>{@link #projMethod()} says which operator the grid feeds — {@code hgridshift},
 *       {@code vgridshift}, {@code gridshift}, {@code geoid_like}, {@code geocentricoffset},
 *       {@code tinshift}, {@code velocity_grid}, {@code defmodel}. proj4j can execute some of these and
 *       not others, and the difference is {@code UNSUPPORTED_OPERATION_METHOD} rather than a wrong
 *       number.</li>
 *   <li>{@link #inverseDirection()} says the PROJ grid runs the opposite way from the authority's
 *       declaration. Ignoring it applies the shift with the wrong sign — twice the error, still
 *       plausible.</li>
 *   <li>{@link #openLicense()} gates redistribution. The grid-pack manifest generator refuses any grid
 *       with {@code open_license = 0}.</li>
 * </ul>
 * {@link #url()} is <strong>information for a human, never an action.</strong> proj4j contains no
 * network code at all, so a grid reachable only by that URL is reported as missing, full stop.
 */
public final class DbGridAlternative {

    private final String originalGridName;
    private final String projGridName;
    private final String oldProjGridName;
    private final String projGridFormat;
    private final String projMethod;
    private final boolean inverseDirection;
    private final String url;
    private final Boolean directDownload;
    private final Boolean openLicense;
    private final String directory;

    public DbGridAlternative(String originalGridName, String projGridName, String oldProjGridName,
                             String projGridFormat, String projMethod, boolean inverseDirection,
                             String url, Boolean directDownload, Boolean openLicense,
                             String directory) {
        this.originalGridName = originalGridName;
        this.projGridName = projGridName;
        this.oldProjGridName = oldProjGridName;
        this.projGridFormat = projGridFormat;
        this.projMethod = projMethod;
        this.inverseDirection = inverseDirection;
        this.url = url;
        this.directDownload = directDownload;
        this.openLicense = openLicense;
        this.directory = directory;
    }

    /** As the authority spells it, e.g. {@code "OSTN15_NTv2_OSGBtoETRS.gsb"}. */
    public String originalGridName() {
        return originalGridName;
    }

    /** PROJ 7+, e.g. {@code "uk_os_OSTN15_NTv2_OSGBtoETRS.tif"}. */
    public String projGridName() {
        return projGridName;
    }

    /** PROJ &lt; 7, e.g. {@code "OSTN15_NTv2_OSGBtoETRS.gsb"}; may be null. */
    public String oldProjGridName() {
        return oldProjGridName;
    }

    /** One of {@code GTiff}, {@code GTX}, {@code NTv2}, {@code JSON}. */
    public String projGridFormat() {
        return projGridFormat;
    }

    /** See the class comment. */
    public String projMethod() {
        return projMethod;
    }

    /** {@code true} iff the PROJ grid direction is reversed relative to the authority's. */
    public boolean inverseDirection() {
        return inverseDirection;
    }

    /** May be null. Never fetched by proj4j. */
    public String url() {
        return url;
    }

    /** May be null when unknown, which is not the same as {@code false}. */
    public Boolean directDownload() {
        return directDownload;
    }

    /** May be null when unknown. Treat null as "not established", never as permission. */
    public Boolean openLicense() {
        return openLicense;
    }

    /** Optional subdirectory hint; may be null. */
    public String directory() {
        return directory;
    }

    @Override
    public String toString() {
        return originalGridName + " -> " + projGridName + " (" + projGridFormat + ", " + projMethod
                + (inverseDirection ? ", inverse" : "") + ")";
    }
}
