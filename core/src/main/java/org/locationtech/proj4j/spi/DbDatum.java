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

import java.util.Collections;
import java.util.List;

/**
 * A geodetic or vertical datum, including the datum-ensemble case.
 * <p>
 * One class covers both because the only structural difference is that a vertical datum has no
 * ellipsoid and no prime meridian; {@link #type()} says which, and {@link #ellipsoid()} and
 * {@link #primeMeridian()} are null for {@link DbObjectType#VERTICAL_DATUM}.
 * <p>
 * <strong>Ensembles matter for ballpark detection.</strong> {@code EPSG:6326} "World Geodetic System
 * 1984 ensemble" has a non-null {@link #ensembleAccuracy()} (2 m) and 6 members; two CRSs whose datums
 * are different members of the same ensemble are related by an operation whose accuracy is bounded by
 * that figure, not by nothing. Treating an ensemble as an ordinary datum is how {@code EPSG:4267} to
 * {@code EPSG:4269} became "the input unchanged".
 */
public final class DbDatum {

    private final DbObjectType type;
    private final String authName;
    private final String code;
    private final String name;
    private final DbObjectRef ellipsoid;
    private final DbObjectRef primeMeridian;
    private final String publicationDate;
    private final double frameReferenceEpoch;
    private final double ensembleAccuracy;
    private final List<DbObjectRef> ensembleMembers;
    private final boolean deprecated;

    public DbDatum(DbObjectType type, String authName, String code, String name,
                   DbObjectRef ellipsoid, DbObjectRef primeMeridian, String publicationDate,
                   double frameReferenceEpoch, double ensembleAccuracy,
                   List<DbObjectRef> ensembleMembers, boolean deprecated) {
        this.type = type;
        this.authName = authName;
        this.code = code;
        this.name = name;
        this.ellipsoid = ellipsoid;
        this.primeMeridian = primeMeridian;
        this.publicationDate = publicationDate;
        this.frameReferenceEpoch = frameReferenceEpoch;
        this.ensembleAccuracy = ensembleAccuracy;
        this.ensembleMembers = ensembleMembers == null
                ? Collections.<DbObjectRef>emptyList()
                : Collections.unmodifiableList(ensembleMembers);
        this.deprecated = deprecated;
    }

    /** {@link DbObjectType#GEODETIC_DATUM} or {@link DbObjectType#VERTICAL_DATUM}. */
    public DbObjectType type() {
        return type;
    }

    public String authName() {
        return authName;
    }

    public String code() {
        return code;
    }

    public String name() {
        return name;
    }

    /** Null for a vertical datum. */
    public DbObjectRef ellipsoid() {
        return ellipsoid;
    }

    /** Null for a vertical datum. */
    public DbObjectRef primeMeridian() {
        return primeMeridian;
    }

    /** {@code YYYY-MM-DD}, or null. */
    public String publicationDate() {
        return publicationDate;
    }

    /**
     * Set only for a dynamic datum; {@link Double#NaN} otherwise. A dynamic datum needs a coordinate
     * epoch to be transformed rigorously, which is what {@code MISSING_TIME} reports.
     */
    public double frameReferenceEpoch() {
        return frameReferenceEpoch;
    }

    /**
     * Metres, set only for a datum ensemble; {@link Double#NaN} otherwise.
     */
    public double ensembleAccuracy() {
        return ensembleAccuracy;
    }

    /**
     * @return {@code true} iff this is a datum ensemble rather than a single realisation.
     */
    public boolean isEnsemble() {
        return !ensembleMembers.isEmpty() || !Double.isNaN(ensembleAccuracy);
    }

    /**
     * Members in upstream {@code sequence} order, which is the authority's own preference order and
     * therefore deterministic. Empty for a non-ensemble.
     */
    public List<DbObjectRef> ensembleMembers() {
        return ensembleMembers;
    }

    public boolean deprecated() {
        return deprecated;
    }

    public DbObjectRef ref() {
        return new DbObjectRef(type, authName, code);
    }

    @Override
    public String toString() {
        return authName + ":" + code + " " + name + (isEnsemble() ? " [ensemble]" : "");
    }
}
