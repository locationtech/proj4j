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

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import org.locationtech.proj4j.spi.ProjDatabase;

/**
 * What CRS metadata this deployment actually has, stated without flattery.
 *
 * <h2>There is no {@code proj.db}, and this class says so</h2>
 *
 * <p>PROJ 9.8.1 resolves an authority code against {@code proj.db}, a 10&nbsp;MB SQLite database
 * carrying <b>EPSG v12.029</b> plus ESRI, IGNF, IAU_2015, NKG and NRCAN. Proj4J ships nothing of
 * the kind. What it ships, in the separate {@code proj4j-epsg} artifact, is a <b>PROJ.4
 * {@code +init=} dictionary</b>: flat text files of the form
 * {@code <4326> +proj=longlat +datum=WGS84 +no_defs}, generated from <b>EPSG v9.2</b>.
 *
 * <p>Those two things are not the same kind of object and the difference is not cosmetic:
 *
 * <table>
 * <caption>what a dictionary can and cannot answer</caption>
 * <tr><th>question</th><th>{@code proj.db}</th><th>the shipped dictionary</th></tr>
 * <tr><td>what are this code's projection parameters?</td><td>yes</td><td><b>yes</b></td></tr>
 * <tr><td>what is this CRS's area of use?</td><td>yes</td><td>no</td></tr>
 * <tr><td>which operations exist between these two CRSs?</td><td>yes</td><td>no &mdash; one is
 *     synthesised from the datum</td></tr>
 * <tr><td>what is that operation's accuracy?</td><td>yes</td><td>no</td></tr>
 * <tr><td>which axis order does the authority declare?</td><td>yes</td><td>no</td></tr>
 * <tr><td>what EPSG version is this?</td><td>yes, in a {@code metadata} table</td>
 *     <td><b>no &mdash; the files carry no version stamp at all</b></td></tr>
 * </table>
 *
 * <p>The last row is why {@link Proj#databaseVersion()} returns
 * {@link Optional#empty()} rather than a string. Reporting {@code "EPSG v9.2"} would be an
 * unverifiable claim about bytes with no version in them; reporting {@code "EPSG v12.029"} because
 * that is what PROJ 9.8.1 ships would be a straightforward lie. So the version is absent, and
 * {@link #vintageNote()} explains the gap in prose that a human will read in a log.
 *
 * <p>{@link #dictionaryPresent()} <em>is</em> a checkable fact &mdash; it is a classpath probe for
 * the dictionary resource &mdash; and it is reported.
 *
 * <p>Immutable and safe to share between threads.
 *
 * @see Proj#databaseVersion()
 * @see Proj#databaseInfo()
 * @since 1.5.0
 */
public final class DatabaseInfo {

    /**
     * The EPSG release the shipped dictionary was generated from, for prose only. It is
     * <b>not</b> returned by {@link #epsgVersion()}, because it is not in the files.
     */
    private static final String DICTIONARY_VINTAGE = "v9.2-era (2017)";

    /** The EPSG release PROJ 9.8.1's own {@code proj.db} carries. */
    private static final String PROJ_EPSG_VERSION = "v12.029";

    private final boolean dictionaryPresent;
    private final String dictionaryOrigin;
    private final String databaseName;
    private final Map<String, String> metadata;

    private DatabaseInfo(boolean dictionaryPresent, String dictionaryOrigin, String databaseName,
                         Map<String, String> metadata) {
        this.dictionaryPresent = dictionaryPresent;
        this.dictionaryOrigin = dictionaryOrigin;
        this.databaseName = databaseName;
        this.metadata = metadata == null
                ? Collections.<String, String>emptyMap()
                : Collections.unmodifiableMap(new TreeMap<String, String>(metadata));
    }

    /**
     * Probes the classpath for the PROJ.4 dictionary and reports what is there, together with the
     * authority database a context carries.
     *
     * @param database the context's database, or null
     * @return the info; never null
     */
    static DatabaseInfo probe(ProjDatabase database) {
        // The one resource-path literal in main code, io/Proj4FileReader.java:128. Probed rather
        // than assumed, because proj4j-epsg is an optional artifact and a deployment without it
        // is legitimate -- createFromParameters and the WKT readers work perfectly well.
        java.net.URL url = DatabaseInfo.class.getClassLoader().getResource("proj4/nad/epsg");
        String name = database == null ? null : database.name();
        Map<String, String> meta = database == null ? null : database.metadata();
        return new DatabaseInfo(url != null, url == null ? null : "classpath:proj4/nad/epsg", name,
                meta);
    }

    /**
     * The database version string, in the sense PROJ means it.
     *
     * <p>Read from the database's own {@code metadata} table when one is configured, so it is a fact
     * about the shipped bytes rather than a constant that could drift from them.
     *
     * <p><b>Empty when no database is configured</b>, and deliberately so. The PROJ.4 {@code +init=}
     * dictionary that stands in for one carries no version stamp anywhere: reporting
     * {@code "EPSG v9.2"} would be an unverifiable claim about bytes with no version in them, and
     * reporting {@code "EPSG v12.029"} because that is what PROJ 9.8.1 has would be a lie. See
     * {@link #vintageNote()} for what a human is told instead.
     *
     * @return for example {@code "PROJ 9.8.1, EPSG v12.029"}, or empty
     */
    public Optional<String> version() {
        if (metadata.isEmpty()) {
            return Optional.empty();
        }
        String proj = metadata.get("PROJ.VERSION");
        String epsg = metadata.get("EPSG.VERSION");
        if (proj == null && epsg == null) {
            return Optional.empty();
        }
        StringBuilder sb = new StringBuilder();
        if (proj != null) {
            sb.append("PROJ ").append(proj);
        }
        if (epsg != null) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append("EPSG ").append(epsg);
        }
        return Optional.of(sb.toString());
    }

    /**
     * The EPSG dataset version the configured database carries, from its own {@code metadata} table.
     *
     * @return for example {@code "v12.029"}, or empty when no database is configured
     */
    public Optional<String> epsgVersion() {
        return Optional.ofNullable(metadata.get("EPSG.VERSION"));
    }

    /**
     * The configured database's {@code metadata} table, verbatim and in key order.
     *
     * <p>One of the two independent version sources that must agree; the other is the build-stamped
     * sidecar the {@code proj4j-db} artifact ships, which its own opener cross-checks and refuses to
     * open on a mismatch.
     *
     * @return an unmodifiable map in key order; empty when no database is configured
     */
    public Map<String, String> metadata() {
        return metadata;
    }

    /**
     * Which database is answering, and where its bytes came from.
     *
     * @return for example {@code "pjdx:classpath:/proj4j-data/db/proj4j-db.pjdx"}, or empty
     */
    public Optional<String> databaseName() {
        return Optional.ofNullable(databaseName);
    }

    /**
     * Whether a real CRS database ({@code proj.db} or a Proj4J transcoding of it) is configured on the
     * context this was probed for.
     *
     * <p>A property of the {@link ProjContext}, not of the classpath: core never scans for a provider
     * implicitly, because an implicit {@code ServiceLoader} walk touches a classpath Proj4J does not
     * control. So this is false until an application opens one and passes it to
     * {@link ProjContext.Builder#database}.
     *
     * @return true iff an authority database is configured
     */
    public boolean isDatabasePresent() {
        return databaseName != null;
    }

    /**
     * Whether the legacy PROJ.4 {@code +init=} dictionary is on the classpath, i.e. whether
     * {@code CRSFactory.createFromName("EPSG:4326")} will resolve.
     *
     * <p>This one <em>is</em> a probe of the running classpath, not a constant.
     *
     * @return true iff {@code proj4/nad/epsg} is resolvable
     */
    public boolean dictionaryPresent() {
        return dictionaryPresent;
    }

    /**
     * Where the dictionary was found.
     *
     * @return the origin, or empty if {@link #dictionaryPresent()} is false
     */
    public Optional<String> dictionaryOrigin() {
        return Optional.ofNullable(dictionaryOrigin);
    }

    /**
     * The prose a human needs in place of a version string: what the metadata actually is, how old
     * it is, and what PROJ 9.8.1 would have had instead.
     *
     * <p>Deliberately a sentence rather than a version number. A caller that wants to gate on
     * capability should use {@link #isDatabasePresent()} or {@link #dictionaryPresent()}; a caller
     * writing a startup log line wants this.
     *
     * @return the note; never null, never empty
     */
    public String vintageNote() {
        if (isDatabasePresent()) {
            StringBuilder sb = new StringBuilder();
            sb.append("An authority database is configured: ").append(databaseName).append(", ")
                    .append(version().isPresent() ? version().get() : "version unstated")
                    .append(". Operation selection, accuracy and area of use are read from it. ");
            sb.append(dictionaryPresent
                    ? "The legacy PROJ.4 dictionary (EPSG " + DICTIONARY_VINTAGE + ") is also "
                            + "present and stays authoritative for the codes it knows, so adding the "
                            + "database cannot move a coordinate that already worked; the database is "
                            + "consulted for codes the dictionary cannot produce."
                    : "The legacy PROJ.4 dictionary is absent, so authority:code lookups resolve "
                            + "against the database alone -- which builds geodetic CRSs but not "
                            + "projected ones. Add proj4j-epsg for those.");
            return sb.toString();
        }
        if (!dictionaryPresent) {
            return "No CRS metadata of any kind is on the classpath: no proj.db, and not the "
                    + "legacy PROJ.4 dictionary either (add proj4j-epsg for authority:code "
                    + "lookups). PROJ.4 parameter strings, WKT and PROJJSON still work.";
        }
        return "No proj.db. Authority codes resolve against the legacy PROJ.4 +init= dictionary in "
                + "proj4j-epsg, generated from EPSG " + DICTIONARY_VINTAGE + "; PROJ 9.8.1 ships "
                + "EPSG " + PROJ_EPSG_VERSION + " instead. The dictionary carries no version stamp, "
                + "so no version string is reported rather than a guessed one. Codes added, "
                + "deprecated or re-parameterised after EPSG " + DICTIONARY_VINTAGE + " are absent "
                + "or stale, and area of use, operation accuracy, authority axis order and "
                + "operation selection are unavailable rather than approximated.";
    }

    /**
     * A multi-line rendering of everything above, for a startup log.
     *
     * @return the description, newline-terminated; never null
     */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("proj4j CRS metadata:\n");
        sb.append("  authority database   = ").append(isDatabasePresent() ? databaseName : "NONE")
                .append('\n');
        sb.append("  databaseVersion()    = ").append(version().isPresent() ? version().get()
                : "<empty> (nothing here can state one honestly)").append('\n');
        if (!metadata.isEmpty()) {
            for (Map.Entry<String, String> e : metadata.entrySet()) {
                sb.append("      ").append(e.getKey()).append(" = ").append(e.getValue())
                        .append('\n');
            }
        }
        sb.append("  legacy dictionary    = ").append(dictionaryPresent
                ? "present at " + dictionaryOrigin : "ABSENT").append('\n');
        sb.append("  vintage              = ").append(dictionaryPresent
                ? "EPSG " + DICTIONARY_VINTAGE + " vs PROJ 9.8.1's EPSG " + PROJ_EPSG_VERSION
                : "n/a").append('\n');
        sb.append(isDatabasePresent()
                ? "  read from the database, not approximated: operation selection, operation "
                        + "accuracy, area of use, authority axis order\n"
                : "  unavailable, not approximated: areaOfUse(), operation accuracy, authority "
                        + "axis order, operation selection\n");
        sb.append("  note: ").append(vintageNote()).append('\n');
        return sb.toString();
    }

    @Override
    public String toString() {
        return "DatabaseInfo[authorityDatabase="
                + (isDatabasePresent() ? databaseName : "absent") + ", dictionary="
                + (dictionaryPresent ? dictionaryOrigin : "absent") + ", version="
                + (version().isPresent() ? version().get() : "<empty>") + "]";
    }
}
