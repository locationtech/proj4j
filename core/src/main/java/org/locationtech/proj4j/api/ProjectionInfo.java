/*
 * Copyright 2026, PROJ4J contributors
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
package org.locationtech.proj4j.api;

import java.util.Optional;

/**
 * One {@code +proj=} name, its human-readable description, and whether Proj4J can actually
 * instantiate it.
 *
 * <p>The descriptions come from {@link org.locationtech.proj4j.Registry}, which has carried about a
 * hundred of them since 2009 &mdash; {@code "Albers Equal Area"}, {@code "Azimuthal Equidistant"}
 * &mdash; with no way to read them. They existed for one message, about a projection registered but
 * not implemented, and that message is now unreachable, so they were write-only. This class and
 * {@link Proj#projections()} are what they were for.
 *
 * <p>{@link #isImplemented()} is the capability boundary Proj4J has and PROJ does not: a handful of
 * names are registered so that {@code +proj=} reports "not implemented" rather than "unknown",
 * which are different facts and lead a caller to different actions.
 *
 * <p>Immutable and safe to share between threads.
 *
 * @see Proj#projections()
 * @see org.locationtech.proj4j.ErrorCause#PROJECTION_NOT_IMPLEMENTED
 * @since 1.5.0
 */
public final class ProjectionInfo implements Comparable<ProjectionInfo> {

    private final String name;
    private final String description;
    private final boolean implemented;

    ProjectionInfo(String name, String description, boolean implemented) {
        this.name = name;
        this.description = description;
        this.implemented = implemented;
    }

    /**
     * The {@code +proj=} value, for example {@code "aea"}.
     *
     * @return the name; never null, never empty
     */
    public String name() {
        return name;
    }

    /**
     * The human-readable name, for example {@code "Albers Equal Area"}.
     *
     * @return the description, or empty if the registry has none for this name
     */
    public Optional<String> description() {
        return Optional.ofNullable(description);
    }

    /**
     * Whether {@code +proj=}{@link #name()} can be used, as opposed to being registered so that it
     * can be refused by name.
     *
     * @return true iff the projection class can be instantiated
     */
    public boolean isImplemented() {
        return implemented;
    }

    @Override
    public int compareTo(ProjectionInfo other) {
        return name.compareTo(other.name);
    }

    @Override
    public String toString() {
        return name + (description == null ? "" : " -- " + description)
                + (implemented ? "" : "  [REGISTERED BUT NOT IMPLEMENTED]");
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ProjectionInfo)) {
            return false;
        }
        ProjectionInfo that = (ProjectionInfo) o;
        return name.equals(that.name) && implemented == that.implemented
                && (description == null ? that.description == null
                        : description.equals(that.description));
    }

    @Override
    public int hashCode() {
        return 31 * name.hashCode() + (implemented ? 1 : 0);
    }
}
