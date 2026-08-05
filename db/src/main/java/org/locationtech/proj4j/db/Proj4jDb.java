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
package org.locationtech.proj4j.db;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

import org.locationtech.proj4j.resource.ClasspathResourceResolver;
import org.locationtech.proj4j.resource.ResourceHandle;
import org.locationtech.proj4j.resource.ResourceResolver;

/**
 * The entry point: opens the shipped {@code proj4j-db} index.
 * <p>
 * Everything is located through {@link ResourceResolver}. Nothing here reads an environment variable,
 * a system property or the process working directory, and there is no network code — a Spark
 * executor's working directory is framework-chosen and shared, and a file dropped there must never
 * outrank the packaged one.
 *
 * <h2>Two version sources that must agree</h2>
 * {@link #open()} cross-checks the {@code metadata} table <em>inside</em> the index against the
 * build-stamped {@code db.properties} sidecar next to it, and <strong>throws if they disagree</strong>.
 * That catches a hand-edited or mismatched artifact before it produces a wrong answer, which an EPSG
 * version string alone cannot do: the same EPSG version can be packaged differently. Both are
 * surfaced, so a job can log {@link #sidecar()}'s {@code artifactSha256} per executor and prove all
 * executors ran the same bytes.
 */
public final class Proj4jDb {

    private Proj4jDb() {
    }

    /**
     * Opens the index from this class's own class loader.
     *
     * @return the database, or {@code null} if the data is not on the classpath
     * @throws IOException if the data is present but unreadable, mis-versioned, fails its SHA-256
     *                     self-check, or disagrees with its sidecar
     */
    public static PjdxDatabase open() throws IOException {
        return open(Proj4jDb.class.getClassLoader());
    }

    /**
     * Opens the index from a given class loader.
     *
     * @return the database, or {@code null} if the data is not visible to {@code loader}
     */
    public static PjdxDatabase open(ClassLoader loader) throws IOException {
        return open(resolver(loader));
    }

    /**
     * Opens the index through an arbitrary resolver, e.g. a
     * {@code DirectoryResourceResolver} over an unpacked data directory.
     *
     * @return the database, or {@code null} if the resolver does not have
     *         {@code proj4j-db.pjdx}
     */
    public static PjdxDatabase open(ResourceResolver resolver) throws IOException {
        if (resolver == null) {
            throw new IllegalArgumentException("resolver");
        }
        ResourceHandle handle = resolver.resolve(PjdxFormat.RESOURCE_NAME);
        if (handle == null) {
            return null;
        }
        PjdxDatabase db = PjdxDatabase.open(handle);
        try {
            crossCheck(db, sidecar(resolver));
        } catch (IOException e) {
            db.close();
            throw e;
        } catch (RuntimeException e) {
            db.close();
            throw e;
        }
        return db;
    }

    /**
     * The default resolver: the shipped {@code /proj4j-data/db/} classpath prefix, made enumerable by
     * its generated {@code INDEX} manifest.
     */
    public static ResourceResolver resolver(ClassLoader loader) {
        return new ClasspathResourceResolver(loader, PjdxFormat.RESOURCE_PREFIX, "INDEX");
    }

    /**
     * The build-stamped sidecar: {@code projSourceRev}, {@code projSourceCommit}, {@code projVersion},
     * {@code epsgVersion}, {@code formatVersion}, {@code artifactSha256}, {@code artifactBytes},
     * {@code generatedAtUtc}.
     *
     * @return an unmodifiable map in key order; empty if the sidecar is absent
     */
    public static Map<String, String> sidecar() throws IOException {
        return sidecar(resolver(Proj4jDb.class.getClassLoader()));
    }

    /** @see #sidecar() */
    public static Map<String, String> sidecar(ResourceResolver resolver) throws IOException {
        ResourceHandle handle = resolver.resolve(PjdxFormat.PROPERTIES_NAME);
        if (handle == null) {
            return Collections.emptyMap();
        }
        Properties p = new Properties();
        InputStream in = new org.locationtech.proj4j.db.internal.ByteReaderInputStream(handle.open());
        try {
            p.load(in);
        } finally {
            in.close();
        }
        TreeMap<String, String> out = new TreeMap<String, String>();
        for (String key : p.stringPropertyNames()) {
            out.put(key, p.getProperty(key));
        }
        return Collections.unmodifiableMap(out);
    }

    /**
     * Fails if the index's own {@code metadata} and the sidecar disagree about what this artifact is.
     * <p>
     * Deliberately not lenient. A mismatch means one of the two files was replaced independently of the
     * other, and the whole point of shipping both is that neither can be trusted alone.
     */
    static void crossCheck(PjdxDatabase db, Map<String, String> sidecar) throws IOException {
        if (sidecar.isEmpty()) {
            throw new IOException(db.name() + " has no " + PjdxFormat.PROPERTIES_NAME
                    + " sidecar next to it. The sidecar is the second of two independent version"
                    + " sources; without it a mismatched database cannot be detected.");
        }
        Map<String, String> metadata = db.metadata();
        checkEqual(db, "epsgVersion", sidecar.get("epsgVersion"), metadata.get("EPSG.VERSION"),
                "EPSG.VERSION");
        checkEqual(db, "projVersion", sidecar.get("projVersion"), metadata.get("PROJ.VERSION"),
                "PROJ.VERSION");
        String declaredFormat = sidecar.get("formatVersion");
        if (declaredFormat != null
                && Integer.parseInt(declaredFormat.trim()) != PjdxFormat.FORMAT_VERSION) {
            throw new IOException(db.name() + ": sidecar declares .pjdx format version "
                    + declaredFormat + ", this reader is version " + PjdxFormat.FORMAT_VERSION);
        }
        String declaredBytes = sidecar.get("artifactBytes");
        if (declaredBytes != null && Long.parseLong(declaredBytes.trim()) != db.sizeBytes()) {
            throw new IOException(db.name() + ": sidecar declares " + declaredBytes
                    + " bytes, the index is " + db.sizeBytes());
        }
        // contentSha256, not artifactSha256: the sidecar carries both, and they cover different byte
        // ranges. artifactSha256 is the whole file, which is what the build-time enforcer gate
        // compares; contentSha256 is the digest embedded in the header, covering bytes [64, len),
        // which the reader has already verified against the bytes it read. Comparing the wrong one
        // here would fail on every correct artifact -- and did, on the first run of the verifier.
        String declaredSha = sidecar.get("contentSha256");
        if (declaredSha != null && !declaredSha.trim().equalsIgnoreCase(db.contentSha256())) {
            throw new IOException(db.name() + ": sidecar records contentSha256 " + declaredSha
                    + " but the index's own content digest is " + db.contentSha256()
                    + ". The two files are from different builds.");
        }
    }

    private static void checkEqual(PjdxDatabase db, String sidecarKey, String sidecarValue,
                                  String metadataValue, String metadataKey) throws IOException {
        if (sidecarValue == null || metadataValue == null) {
            return;
        }
        if (!sidecarValue.trim().equals(metadataValue.trim())) {
            throw new IOException(db.name() + ": " + PjdxFormat.PROPERTIES_NAME + "'s " + sidecarKey
                    + " is '" + sidecarValue + "' but the index's metadata table says " + metadataKey
                    + " = '" + metadataValue + "'. Refusing to answer from a database whose two"
                    + " version sources disagree.");
        }
    }
}
