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
package org.locationtech.proj4j.db.gen;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.locationtech.proj4j.db.PjdxFormat;
import org.locationtech.proj4j.db.gen.QuoteDump.Table;

/**
 * Transcodes a {@code sqlite3 .mode quote} dump of PROJ's {@code proj.db} into a {@code .pjdx} index.
 * Build-time only, run by {@code -Pregen-db}; excluded from the published jar.
 * <p>
 * Usage: {@code GenerateIndex <dump> <outputDir> <projRev> <projRevSha>}
 *
 * <h2>The schema subset, and what is deliberately dropped</h2>
 * Transcoded: {@code metadata}, {@code unit_of_measure}, {@code celestial_body}, {@code ellipsoid},
 * {@code prime_meridian}, {@code geodetic_datum}, {@code vertical_datum}, both
 * {@code *_datum_ensemble_member} tables, {@code coordinate_system}, {@code axis},
 * {@code geodetic_crs}, {@code projected_crs}, {@code vertical_crs}, {@code compound_crs},
 * {@code engineering_crs}, {@code conversion_table} (with names resolved from
 * {@code conversion_method} and {@code conversion_param}), {@code helmert_transformation_table} (with
 * the method name resolved from {@code coordinate_operation_method}), {@code grid_transformation},
 * {@code other_transformation}, {@code concatenated_operation}, {@code concatenated_operation_step},
 * {@code usage}, {@code extent}, {@code grid_alternatives}, {@code alias_name},
 * {@code supersession} and {@code deprecation}.
 * <p>
 * Dropped on purpose, each with a reason:
 * <ul>
 *   <li>{@code coordinate_metadata} (198 rows, 921,600 B of the SQLite file) — coordinate epochs for
 *       point-motion operations, which proj4j cannot execute yet. Shipping it would be shipping data
 *       for a capability that does not exist.</li>
 *   <li>{@code scope} (288 rows) — a usage's scope is descriptive text; PROJ's own operation selection
 *       never reads it. The {@code usage} rows are transcoded as an object-to-extent index, and the
 *       scope reference goes with the rest of the row.</li>
 *   <li>{@code sqlite_stat1} — query-planner statistics for a query planner we do not have.</li>
 *   <li>{@code grid_packages}, {@code builtin_authorities},
 *       {@code versioned_auth_name_mapping} — {@code grid_packages} is vestigial upstream
 *       ({@code grid_alternatives.package_name} carries a {@code CHECK} that it is always NULL); the
 *       authority list is derived from the data instead of asserted separately, so the two cannot
 *       disagree.</li>
 *   <li>{@code authority_to_authority_preference} (6 rows) and {@code geoid_model} (67 rows) — no
 *       consumer in the SPI yet. Both are cheap to add when there is one; adding them now would be
 *       shipping an accessor nothing calls.</li>
 *   <li>{@code description} and {@code anchor} on datums and CRSs — free text, never read by
 *       selection or by the facade's output. {@code extent.description} <em>is</em> kept, because it
 *       is the string a human is shown as the area of use.</li>
 * </ul>
 *
 * <h2>Helmert parameters are ported, not remembered</h2>
 * {@code helmert_transformation_table} stores its parameters as named columns, not as the
 * {@code paramN} slots the other operation tables use. The mapping to EPSG parameter codes, and the
 * conditional structure that decides which parameters exist for a 3-, 7-, 8-, 10-, 15-parameter or
 * Molodensky-Badekas case, is transcribed from {@code 9.8.1:src/iso19111/factory.cpp:6337-6450} with
 * the codes read out of {@code 9.8.1:src/proj_constants.h:509-559}. Getting a parameter code wrong here
 * would bind a value to the wrong slot silently, which is exactly the failure this table's javadoc
 * warns about.
 */
public final class GenerateIndex {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private GenerateIndex() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("usage: GenerateIndex <dump> <outputDir> <projRev> <projRevSha>");
            System.exit(2);
        }
        Path dump = Paths.get(args[0]);
        Path outDir = Paths.get(args[1]);
        String projRev = args[2];
        String projRevSha = args[3];

        long t0 = System.currentTimeMillis();
        QuoteDump d = QuoteDump.read(dump);
        System.out.println("read " + dump + " in " + (System.currentTimeMillis() - t0) + " ms");
        for (Map.Entry<String, Table> e : d.tables().entrySet()) {
            System.out.println(String.format("  %-32s %7d rows", e.getKey(),
                    Integer.valueOf(e.getValue().rows.size())));
        }

        GenerateIndex g = new GenerateIndex();
        byte[] index = g.build(d);

        Files.createDirectories(outDir);
        Path indexPath = outDir.resolve(PjdxFormat.RESOURCE_NAME);
        Files.write(indexPath, index, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        String sha = hex(MessageDigest.getInstance("SHA-256").digest(index));

        // db.properties: the second of the two independent version sources DatabaseInfo cross-checks.
        // epsgVersion is read back out of the metadata table, never hardcoded, so the sidecar cannot
        // claim a version the data does not have.
        String epsgVersion = g.metadataValue(d, "EPSG.VERSION");
        String projVersion = g.metadataValue(d, "PROJ.VERSION");
        StringBuilder props = new StringBuilder();
        props.append("# Generated by org.locationtech.proj4j.db.gen.GenerateIndex. Do not edit.\n");
        props.append("# generatedAtUtc is taken from SOURCE_DATE_EPOCH when set, so a reproducible\n");
        props.append("# build produces a byte-identical sidecar as well as a byte-identical index.\n");
        props.append("projSourceRev=").append(projRev).append('\n');
        props.append("projSourceCommit=").append(projRevSha).append('\n');
        props.append("projVersion=").append(projVersion).append('\n');
        props.append("epsgVersion=").append(epsgVersion).append('\n');
        props.append("formatVersion=").append(PjdxFormat.FORMAT_VERSION).append('\n');
        // Two digests, deliberately, because they answer different questions. artifactSha256 covers
        // the whole file and is what the Maven enforcer's requireFileChecksum gate compares, so a
        // hand-edited artifact fails the build. contentSha256 is the digest embedded in the header at
        // offset 32, covering bytes [64, len) -- the reader verifies that one against the bytes it
        // actually read, so it is the value a job logs per executor to prove they ran the same data.
        props.append("artifactSha256=").append(sha).append('\n');
        props.append("contentSha256=").append(hex(Arrays.copyOfRange(index,
                PjdxFormat.SHA256_OFFSET,
                PjdxFormat.SHA256_OFFSET + PjdxFormat.SHA256_BYTES))).append('\n');
        props.append("artifactBytes=").append(index.length).append('\n');
        props.append("generatedAtUtc=").append(sourceDate()).append('\n');
        Files.write(outDir.resolve(PjdxFormat.PROPERTIES_NAME), props.toString().getBytes(UTF8),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        // The enumerable-resolver manifest, exactly as the grid packs use.
        String indexManifest = PjdxFormat.PROPERTIES_NAME + "\n" + PjdxFormat.RESOURCE_NAME + "\n";
        Files.write(outDir.resolve("INDEX"), indexManifest.getBytes(UTF8),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        System.out.println();
        System.out.println("wrote " + indexPath);
        System.out.println("  bytes        " + index.length);
        System.out.println("  sha256       " + sha);
        System.out.println("  strings      " + g.writer.pool.count() + " ("
                + g.writer.pool.totalBytes() + " B of UTF-8)");
        System.out.println();
        System.out.println("section sizes:");
        long sum = 0;
        for (Map.Entry<Integer, Long> e : g.writer.sectionSizes().entrySet()) {
            System.out.println(String.format("  %-3d %-32s %9d", e.getKey(),
                    sectionName(e.getKey().intValue()), e.getValue()));
            sum += e.getValue().longValue();
        }
        System.out.println(String.format("  %-36s %9d", "total sections", Long.valueOf(sum)));
        System.out.println();
        System.out.println("Set <proj4j.db.sha256> in db/pom.xml to:");
        System.out.println("  " + sha);
    }

    private static String sourceDate() {
        String epoch = System.getenv("SOURCE_DATE_EPOCH");
        long seconds;
        if (epoch != null && !epoch.trim().isEmpty()) {
            seconds = Long.parseLong(epoch.trim());
        } else {
            seconds = System.currentTimeMillis() / 1000L;
        }
        return java.time.Instant.ofEpochSecond(seconds).toString();
    }

    private final PjdxWriter writer = new PjdxWriter();

    private String metadataValue(QuoteDump d, String key) {
        Table t = d.table("metadata");
        for (Object[] row : t.rows) {
            if (key.equals(t.text(row, "key"))) {
                return t.text(row, "value");
            }
        }
        return "";
    }

    // ------------------------------------------------------------------ build

    private byte[] build(final QuoteDump d) throws IOException {
        addMetadata(d);
        addUnits(d);
        addCelestialBodies(d);
        addEllipsoids(d);
        addPrimeMeridians(d);
        addDatums(d);
        addEnsembleMembers(d);
        addCoordinateSystems(d);
        addAxes(d);
        addCrsTables(d);
        addConversions(d);
        addHelmert(d);
        addGridTransformations(d);
        addOtherTransformations(d);
        addConcatenatedOperations(d);
        addExtents(d);
        addGridAlternatives(d);
        addAliases(d);
        addSupersessions(d);
        addDeprecations(d);

        writer.collect();
        // Index key strings that appear nowhere else must be collected too: the normalised search names
        // are synthesised, not copied from a column.
        collectNormalisedNames(d);
        writer.pool.finish();
        writer.encodeTables();

        buildCrsIndexes(d);
        buildOperationIndexes(d);
        buildUsageIndex(d);
        buildNameIndex(d);
        buildAuthorityIndex(d);

        return writer.serialize();
    }

    // ------------------------------------------------------------------ simple tables

    private void addMetadata(QuoteDump d) {
        final Table t = d.table("metadata");
        writer.addTable(PjdxFormat.S_METADATA, "metadata", t.rows, 1,
                new PjdxWriter.KeyExtractor() {
                    @Override
                    public Object[] key(Object[] row) {
                        return new Object[]{t.text(row, "key")};
                    }
                },
                new PjdxWriter.RowEmitter() {
                    @Override
                    public void emit(PjdxWriter.Enc e, Object[] row) {
                        e.str(t.text(row, "value"));
                    }
                });
    }

    private void addUnits(QuoteDump d) {
        final Table t = d.table("unit_of_measure");
        writer.addTable(PjdxFormat.S_UNIT, "unit_of_measure", t.rows, 2, authCode(t),
                new PjdxWriter.RowEmitter() {
                    @Override
                    public void emit(PjdxWriter.Enc e, Object[] row) {
                        e.str(t.text(row, "name"));
                        e.str(t.text(row, "type"));
                        e.dbl(t.real(row, "conv_factor"));
                        e.str(t.text(row, "proj_short_name"));
                        e.bool(t.bool(row, "deprecated"));
                    }
                });
    }

    private void addCelestialBodies(QuoteDump d) {
        final Table t = d.table("celestial_body");
        writer.addTable(PjdxFormat.S_CELESTIAL_BODY, "celestial_body", t.rows, 2, authCode(t),
                new PjdxWriter.RowEmitter() {
                    @Override
                    public void emit(PjdxWriter.Enc e, Object[] row) {
                        e.str(t.text(row, "name"));
                        e.dbl(t.real(row, "semi_major_axis"));
                    }
                });
    }

    private void addEllipsoids(QuoteDump d) {
        final Table t = d.table("ellipsoid");
        writer.addTable(PjdxFormat.S_ELLIPSOID, "ellipsoid", t.rows, 2, authCode(t),
                new PjdxWriter.RowEmitter() {
                    @Override
                    public void emit(PjdxWriter.Enc e, Object[] row) {
                        e.str(t.text(row, "name"));
                        e.str(t.text(row, "celestial_body_auth_name"));
                        e.str(t.text(row, "celestial_body_code"));
                        e.dbl(t.real(row, "semi_major_axis"));
                        e.str(t.text(row, "uom_auth_name"));
                        e.str(t.text(row, "uom_code"));
                        e.dbl(t.real(row, "inv_flattening"));
                        e.dbl(t.real(row, "semi_minor_axis"));
                        e.bool(t.bool(row, "deprecated"));
                    }
                });
    }

    private void addPrimeMeridians(QuoteDump d) {
        final Table t = d.table("prime_meridian");
        writer.addTable(PjdxFormat.S_PRIME_MERIDIAN, "prime_meridian", t.rows, 2, authCode(t),
                new PjdxWriter.RowEmitter() {
                    @Override
                    public void emit(PjdxWriter.Enc e, Object[] row) {
                        e.str(t.text(row, "name"));
                        e.dbl(t.real(row, "longitude"));
                        e.str(t.text(row, "uom_auth_name"));
                        e.str(t.text(row, "uom_code"));
                        e.bool(t.bool(row, "deprecated"));
                    }
                });
    }

    private void addDatums(QuoteDump d) {
        final Table g = d.table("geodetic_datum");
        writer.addTable(PjdxFormat.S_GEODETIC_DATUM, "geodetic_datum", g.rows, 2, authCode(g),
                new PjdxWriter.RowEmitter() {
                    @Override
                    public void emit(PjdxWriter.Enc e, Object[] row) {
                        e.str(g.text(row, "name"));
                        e.str(g.text(row, "ellipsoid_auth_name"));
                        e.str(g.text(row, "ellipsoid_code"));
                        e.str(g.text(row, "prime_meridian_auth_name"));
                        e.str(g.text(row, "prime_meridian_code"));
                        e.str(g.text(row, "publication_date"));
                        e.dbl(g.real(row, "frame_reference_epoch"));
                        e.dbl(g.real(row, "ensemble_accuracy"));
                        e.bool(g.bool(row, "deprecated"));
                    }
                });
        final Table v = d.table("vertical_datum");
        writer.addTable(PjdxFormat.S_VERTICAL_DATUM, "vertical_datum", v.rows, 2, authCode(v),
                new PjdxWriter.RowEmitter() {
                    @Override
                    public void emit(PjdxWriter.Enc e, Object[] row) {
                        e.str(v.text(row, "name"));
                        e.str(v.text(row, "publication_date"));
                        e.dbl(v.real(row, "frame_reference_epoch"));
                        e.dbl(v.real(row, "ensemble_accuracy"));
                        e.bool(v.bool(row, "deprecated"));
                    }
                });
    }

    private void addEnsembleMembers(QuoteDump d) {
        addEnsemble(d.table("geodetic_datum_ensemble_member"),
                PjdxFormat.S_GEODETIC_ENSEMBLE_MEMBER);
        addEnsemble(d.table("vertical_datum_ensemble_member"),
                PjdxFormat.S_VERTICAL_ENSEMBLE_MEMBER);
    }

    private void addEnsemble(final Table t, int section) {
        writer.addTable(section, t.name, t.rows, 3,
                new PjdxWriter.KeyExtractor() {
                    @Override
                    public Object[] key(Object[] row) {
                        return new Object[]{t.text(row, "ensemble_auth_name"),
                                t.text(row, "ensemble_code"),
                                Integer.valueOf(t.integer(row, "sequence"))};
                    }
                },
                new PjdxWriter.RowEmitter() {
                    @Override
                    public void emit(PjdxWriter.Enc e, Object[] row) {
                        e.str(t.text(row, "member_auth_name"));
                        e.str(t.text(row, "member_code"));
                    }
                });
    }

    private void addCoordinateSystems(QuoteDump d) {
        final Table t = d.table("coordinate_system");
        writer.addTable(PjdxFormat.S_COORDINATE_SYSTEM, "coordinate_system", t.rows, 2, authCode(t),
                new PjdxWriter.RowEmitter() {
                    @Override
                    public void emit(PjdxWriter.Enc e, Object[] row) {
                        e.str(t.text(row, "type"));
                        e.uint(t.integer(row, "dimension"));
                    }
                });
    }

    private void addAxes(QuoteDump d) {
        final Table t = d.table("axis");
        // Keyed by (cs, order, axis authority, axis code) rather than (cs, order).
        //
        // Upstream has exactly one coordinate system whose axis orders are not distinct: PROJ:ENh, a
        // 3-dimensional Cartesian CS whose axes are numbered 1, 2 and *2* -- Easting (PROJ:1),
        // Northing (PROJ:2) and Ellipsoidal height (PROJ:3). Verified against 9.8.1's own data; every
        // other one of the 149 coordinate systems is well-formed.
        //
        // Keying on (cs, order) alone would leave those two rows tied, and the tie would then be
        // broken by encoded row bytes -- which puts "Ellipsoidal height" before "Northing" and
        // silently reorders the axes of a 3D system. Adding the axis's own identity to the key makes
        // the order (order, axis code), which is what PROJ's own `ORDER BY coordinate_system_order`
        // effectively yields, since SQLite returns equal keys in primary-key order.
        writer.addTable(PjdxFormat.S_AXIS, "axis", t.rows, 5,
                new PjdxWriter.KeyExtractor() {
                    @Override
                    public Object[] key(Object[] row) {
                        return new Object[]{t.text(row, "coordinate_system_auth_name"),
                                t.text(row, "coordinate_system_code"),
                                Integer.valueOf(t.integer(row, "coordinate_system_order")),
                                t.text(row, "auth_name"), t.text(row, "code")};
                    }
                },
                new PjdxWriter.RowEmitter() {
                    @Override
                    public void emit(PjdxWriter.Enc e, Object[] row) {
                        e.str(t.text(row, "name"));
                        e.str(t.text(row, "abbrev"));
                        e.str(t.text(row, "orientation"));
                        e.str(t.text(row, "uom_auth_name"));
                        e.str(t.text(row, "uom_code"));
                    }
                });
    }

    private void addCrsTables(QuoteDump d) {
        final Table g = d.table("geodetic_crs");
        writer.addTable(PjdxFormat.S_GEODETIC_CRS, "geodetic_crs", g.rows, 2, authCode(g),
                new PjdxWriter.RowEmitter() {
                    @Override
                    public void emit(PjdxWriter.Enc e, Object[] row) {
                        e.str(g.text(row, "name"));
                        e.str(g.text(row, "type"));
                        e.str(g.text(row, "coordinate_system_auth_name"));
                        e.str(g.text(row, "coordinate_system_code"));
                        e.str(g.text(row, "datum_auth_name"));
                        e.str(g.text(row, "datum_code"));
                        e.str(g.text(row, "text_definition"));
                        e.bool(g.bool(row, "deprecated"));
                    }
                });
        final Table p = d.table("projected_crs");
        writer.addTable(PjdxFormat.S_PROJECTED_CRS, "projected_crs", p.rows, 2, authCode(p),
                new PjdxWriter.RowEmitter() {
                    @Override
                    public void emit(PjdxWriter.Enc e, Object[] row) {
                        e.str(p.text(row, "name"));
                        e.str(p.text(row, "coordinate_system_auth_name"));
                        e.str(p.text(row, "coordinate_system_code"));
                        e.str(p.text(row, "geodetic_crs_auth_name"));
                        e.str(p.text(row, "geodetic_crs_code"));
                        e.str(p.text(row, "conversion_auth_name"));
                        e.str(p.text(row, "conversion_code"));
                        e.str(p.text(row, "text_definition"));
                        e.bool(p.bool(row, "deprecated"));
                    }
                });
        final Table v = d.table("vertical_crs");
        writer.addTable(PjdxFormat.S_VERTICAL_CRS, "vertical_crs", v.rows, 2, authCode(v),
                new PjdxWriter.RowEmitter() {
                    @Override
                    public void emit(PjdxWriter.Enc e, Object[] row) {
                        e.str(v.text(row, "name"));
                        e.str(v.text(row, "coordinate_system_auth_name"));
                        e.str(v.text(row, "coordinate_system_code"));
                        e.str(v.text(row, "datum_auth_name"));
                        e.str(v.text(row, "datum_code"));
                        e.bool(v.bool(row, "deprecated"));
                    }
                });
        final Table c = d.table("compound_crs");
        writer.addTable(PjdxFormat.S_COMPOUND_CRS, "compound_crs", c.rows, 2, authCode(c),
                new PjdxWriter.RowEmitter() {
                    @Override
                    public void emit(PjdxWriter.Enc e, Object[] row) {
                        e.str(c.text(row, "name"));
                        e.str(c.text(row, "horiz_crs_auth_name"));
                        e.str(c.text(row, "horiz_crs_code"));
                        e.str(c.text(row, "vertical_crs_auth_name"));
                        e.str(c.text(row, "vertical_crs_code"));
                        e.bool(c.bool(row, "deprecated"));
                    }
                });
        final Table en = d.table("engineering_crs");
        writer.addTable(PjdxFormat.S_ENGINEERING_CRS, "engineering_crs", en.rows, 2, authCode(en),
                new PjdxWriter.RowEmitter() {
                    @Override
                    public void emit(PjdxWriter.Enc e, Object[] row) {
                        e.str(en.text(row, "name"));
                        e.bool(en.bool(row, "deprecated"));
                    }
                });
    }

    // ------------------------------------------------------------------ conversions

    private void addConversions(QuoteDump d) {
        final Table t = d.table("conversion_table");
        final Map<String, String> methodNames = nameMap(d.table("conversion_method"));
        final Map<String, String> paramNames = nameMap(d.table("conversion_param"));
        writer.addTable(PjdxFormat.S_CONVERSION, "conversion_table", t.rows, 2, authCode(t),
                new PjdxWriter.RowEmitter() {
                    @Override
                    public void emit(PjdxWriter.Enc e, Object[] row) {
                        e.str(t.text(row, "name"));
                        String ma = t.text(row, "method_auth_name");
                        String mc = t.text(row, "method_code");
                        e.str(ma);
                        e.str(mc);
                        e.str(ma == null || mc == null ? null : methodNames.get(ma + ' ' + mc));
                        List<Object[]> params = new ArrayList<Object[]>(7);
                        for (int i = 1; i <= 7; i++) {
                            String pa = t.text(row, "param" + i + "_auth_name");
                            String pc = t.text(row, "param" + i + "_code");
                            if (pa == null || pc == null) {
                                continue;
                            }
                            params.add(new Object[]{pa, pc, paramNames.get(pa + ' ' + pc),
                                    Double.valueOf(t.real(row, "param" + i + "_value")),
                                    t.text(row, "param" + i + "_uom_auth_name"),
                                    t.text(row, "param" + i + "_uom_code")});
                        }
                        emitParams(e, params);
                        e.bool(t.bool(row, "deprecated"));
                    }
                });
    }

    private static void emitParams(PjdxWriter.Enc e, List<Object[]> params) {
        e.uint(params.size());
        for (Object[] p : params) {
            e.str((String) p[0]);
            e.str((String) p[1]);
            e.str((String) p[2]);
            e.dbl(((Double) p[3]).doubleValue());
            e.str((String) p[4]);
            e.str((String) p[5]);
        }
    }

    private static Map<String, String> nameMap(Table t) {
        Map<String, String> m = new HashMap<String, String>();
        for (Object[] row : t.rows) {
            m.put(t.text(row, "auth_name") + ' ' + t.text(row, "code"), t.text(row, "name"));
        }
        return m;
    }

    // ------------------------------------------------------------------ operations

    /**
     * EPSG parameter codes and names for the Helmert columns. Transcribed from
     * {@code 9.8.1:src/proj_constants.h:509-559}; the conditional structure below is
     * {@code factory.cpp:6337-6450}.
     */
    private static final String[][] HELMERT_PARAMS = {
            {"tx", "8605", "X-axis translation", "translation"},
            {"ty", "8606", "Y-axis translation", "translation"},
            {"tz", "8607", "Z-axis translation", "translation"},
            {"rx", "8608", "X-axis rotation", "rotation"},
            {"ry", "8609", "Y-axis rotation", "rotation"},
            {"rz", "8610", "Z-axis rotation", "rotation"},
            {"scale_difference", "8611", "Scale difference", "scale_difference"},
            {"rate_tx", "1040", "Rate of change of X-axis translation", "rate_translation"},
            {"rate_ty", "1041", "Rate of change of Y-axis translation", "rate_translation"},
            {"rate_tz", "1042", "Rate of change of Z-axis translation", "rate_translation"},
            {"rate_rx", "1043", "Rate of change of X-axis rotation", "rate_rotation"},
            {"rate_ry", "1044", "Rate of change of Y-axis rotation", "rate_rotation"},
            {"rate_rz", "1045", "Rate of change of Z-axis rotation", "rate_rotation"},
            {"rate_scale_difference", "1046", "Rate of change of Scale difference",
                    "rate_scale_difference"},
            {"epoch", "1047", "Parameter reference epoch", "epoch"},
            {"epoch", "1049", "Transformation reference epoch", "epoch"},
            {"px", "8617", "Ordinate 1 of evaluation point", "pivot"},
            {"py", "8618", "Ordinate 2 of evaluation point", "pivot"},
            {"pz", "8667", "Ordinate 3 of evaluation point", "pivot"}};

    private void addHelmert(QuoteDump d) {
        final Table t = d.table("helmert_transformation_table");
        final Map<String, String> methodNames = nameMap(d.table("coordinate_operation_method"));
        writer.addTable(PjdxFormat.S_HELMERT_TRANSFORMATION, "helmert_transformation_table", t.rows, 2,
                authCode(t),
                new PjdxWriter.RowEmitter() {
                    @Override
                    public void emit(PjdxWriter.Enc e, Object[] row) {
                        e.str(t.text(row, "name"));
                        String ma = t.text(row, "method_auth_name");
                        String mc = t.text(row, "method_code");
                        e.str(ma);
                        e.str(mc);
                        e.str(ma == null || mc == null ? null : methodNames.get(ma + ' ' + mc));
                        e.str(t.text(row, "source_crs_auth_name"));
                        e.str(t.text(row, "source_crs_code"));
                        e.str(t.text(row, "target_crs_auth_name"));
                        e.str(t.text(row, "target_crs_code"));
                        e.dbl(t.real(row, "accuracy"));
                        emitParams(e, helmertParams(t, row));
                        e.str(t.text(row, "operation_version"));
                        e.bool(t.bool(row, "deprecated"));
                    }
                });
    }

    /**
     * Reproduces {@code factory.cpp}'s branch structure exactly: {@code tx/ty/tz} always;
     * {@code rx/ry/rz/scale_difference} iff {@code rx} is present; then <em>one</em> of the rate block
     * plus parameter code 1047, the 8-parameter epoch as code 1049, or the Molodensky-Badekas pivot.
     * Those three are mutually exclusive upstream, and treating them as independent would emit
     * parameters PROJ never emits.
     */
    private static List<Object[]> helmertParams(Table t, Object[] row) {
        List<Object[]> out = new ArrayList<Object[]>(15);
        addHelmertParam(out, t, row, 0);
        addHelmertParam(out, t, row, 1);
        addHelmertParam(out, t, row, 2);
        boolean hasRotation = !Double.isNaN(t.real(row, "rx"));
        if (hasRotation) {
            addHelmertParam(out, t, row, 3);
            addHelmertParam(out, t, row, 4);
            addHelmertParam(out, t, row, 5);
            addHelmertParam(out, t, row, 6);
        }
        boolean hasRates = !Double.isNaN(t.real(row, "rate_tx"));
        boolean hasEpochUom = t.text(row, "epoch_uom_auth_name") != null;
        boolean hasPivot = !Double.isNaN(t.real(row, "px"));
        if (hasRates) {
            for (int i = 7; i <= 14; i++) {
                addHelmertParam(out, t, row, i);
            }
        } else if (hasEpochUom) {
            addHelmertParam(out, t, row, 15);
        } else if (hasPivot) {
            addHelmertParam(out, t, row, 16);
            addHelmertParam(out, t, row, 17);
            addHelmertParam(out, t, row, 18);
        }
        return out;
    }

    private static void addHelmertParam(List<Object[]> out, Table t, Object[] row, int i) {
        String[] spec = HELMERT_PARAMS[i];
        out.add(new Object[]{"EPSG", spec[1], spec[2], Double.valueOf(t.real(row, spec[0])),
                t.text(row, spec[3] + "_uom_auth_name"), t.text(row, spec[3] + "_uom_code")});
    }

    private void addGridTransformations(QuoteDump d) {
        final Table t = d.table("grid_transformation");
        writer.addTable(PjdxFormat.S_GRID_TRANSFORMATION, "grid_transformation", t.rows, 2, authCode(t),
                new PjdxWriter.RowEmitter() {
                    @Override
                    public void emit(PjdxWriter.Enc e, Object[] row) {
                        emitTransformationHead(e, t, row, 2);
                        List<String> grids = new ArrayList<String>(2);
                        addGrid(grids, t.text(row, "grid_name"));
                        addGrid(grids, t.text(row, "grid2_name"));
                        e.uint(grids.size());
                        for (String g : grids) {
                            e.str(g);
                        }
                        e.str(t.text(row, "interpolation_crs_auth_name"));
                        e.str(t.text(row, "interpolation_crs_code"));
                        e.str(t.text(row, "operation_version"));
                        e.bool(t.bool(row, "deprecated"));
                    }
                });
    }

    private void addOtherTransformations(QuoteDump d) {
        final Table t = d.table("other_transformation");
        writer.addTable(PjdxFormat.S_OTHER_TRANSFORMATION, "other_transformation", t.rows, 2,
                authCode(t),
                new PjdxWriter.RowEmitter() {
                    @Override
                    public void emit(PjdxWriter.Enc e, Object[] row) {
                        emitTransformationHead(e, t, row, 9);
                        List<String> grids = new ArrayList<String>(1);
                        addGrid(grids, t.text(row, "grid_name"));
                        e.uint(grids.size());
                        for (String g : grids) {
                            e.str(g);
                        }
                        e.str(t.text(row, "interpolation_crs_auth_name"));
                        e.str(t.text(row, "interpolation_crs_code"));
                        e.str(t.text(row, "operation_version"));
                        e.bool(t.bool(row, "deprecated"));
                    }
                });
    }

    private static void addGrid(List<String> grids, String name) {
        if (name != null && !name.isEmpty()) {
            grids.add(name);
        }
    }

    /**
     * The head shared by {@code grid_transformation} and {@code other_transformation}: name, method,
     * source, target, accuracy, parameters. Both carry {@code paramN_name} inline, unlike
     * {@code conversion_table}.
     */
    private static void emitTransformationHead(PjdxWriter.Enc e, Table t, Object[] row, int maxParams) {
        e.str(t.text(row, "name"));
        e.str(t.text(row, "method_auth_name"));
        e.str(t.text(row, "method_code"));
        e.str(t.text(row, "method_name"));
        e.str(t.text(row, "source_crs_auth_name"));
        e.str(t.text(row, "source_crs_code"));
        e.str(t.text(row, "target_crs_auth_name"));
        e.str(t.text(row, "target_crs_code"));
        e.dbl(t.real(row, "accuracy"));
        List<Object[]> params = new ArrayList<Object[]>(maxParams);
        for (int i = 1; i <= maxParams; i++) {
            String pa = t.text(row, "param" + i + "_auth_name");
            String pc = t.text(row, "param" + i + "_code");
            if (pa == null || pc == null) {
                continue;
            }
            params.add(new Object[]{pa, pc, t.text(row, "param" + i + "_name"),
                    Double.valueOf(t.real(row, "param" + i + "_value")),
                    t.text(row, "param" + i + "_uom_auth_name"),
                    t.text(row, "param" + i + "_uom_code")});
        }
        emitParams(e, params);
    }

    private void addConcatenatedOperations(QuoteDump d) {
        final Table t = d.table("concatenated_operation");
        writer.addTable(PjdxFormat.S_CONCATENATED_OPERATION, "concatenated_operation", t.rows, 2,
                authCode(t),
                new PjdxWriter.RowEmitter() {
                    @Override
                    public void emit(PjdxWriter.Enc e, Object[] row) {
                        e.str(t.text(row, "name"));
                        e.str(t.text(row, "source_crs_auth_name"));
                        e.str(t.text(row, "source_crs_code"));
                        e.str(t.text(row, "target_crs_auth_name"));
                        e.str(t.text(row, "target_crs_code"));
                        e.dbl(t.real(row, "accuracy"));
                        e.str(t.text(row, "operation_version"));
                        e.bool(t.bool(row, "deprecated"));
                    }
                });

        // A step names an operation by (auth, code) only. Which table it lives in is resolved here,
        // once, at build time -- including the 30 of 868 steps that reference a *conversion* rather
        // than a transformation, which a reader forced to guess would get wrong.
        final Map<String, Integer> operationTags = new HashMap<String, Integer>();
        putTags(operationTags, d.table("helmert_transformation_table"),
                PjdxFormat.TAG_HELMERT_TRANSFORMATION);
        putTags(operationTags, d.table("grid_transformation"), PjdxFormat.TAG_GRID_TRANSFORMATION);
        putTags(operationTags, d.table("other_transformation"), PjdxFormat.TAG_OTHER_TRANSFORMATION);
        putTags(operationTags, d.table("concatenated_operation"),
                PjdxFormat.TAG_CONCATENATED_OPERATION);
        putTags(operationTags, d.table("conversion_table"), PjdxFormat.TAG_CONVERSION);

        final Table s = d.table("concatenated_operation_step");
        writer.addTable(PjdxFormat.S_CONCATENATED_STEP, "concatenated_operation_step", s.rows, 3,
                new PjdxWriter.KeyExtractor() {
                    @Override
                    public Object[] key(Object[] row) {
                        return new Object[]{s.text(row, "operation_auth_name"),
                                s.text(row, "operation_code"),
                                Integer.valueOf(s.integer(row, "step_number"))};
                    }
                },
                new PjdxWriter.RowEmitter() {
                    @Override
                    public void emit(PjdxWriter.Enc e, Object[] row) {
                        String sa = s.text(row, "step_auth_name");
                        String sc = s.text(row, "step_code");
                        Integer tag = operationTags.get(sa + ' ' + sc);
                        if (tag == null) {
                            throw new IllegalStateException("concatenated_operation "
                                    + s.text(row, "operation_auth_name") + ":"
                                    + s.text(row, "operation_code") + " step "
                                    + s.text(row, "step_number") + " references " + sa + ":" + sc
                                    + ", which is in none of the transformation or conversion tables."
                                    + " Emitting a guessed table tag would make the step decode to the"
                                    + " wrong operation.");
                        }
                        e.uint(tag.intValue());
                        e.str(sa);
                        e.str(sc);
                        String dir = s.text(row, "step_direction");
                        e.uint("forward".equals(dir) ? PjdxFormat.DIRECTION_FORWARD
                                : "reverse".equals(dir) ? PjdxFormat.DIRECTION_REVERSE
                                : PjdxFormat.DIRECTION_UNSPECIFIED);
                    }
                });
    }

    private static void putTags(Map<String, Integer> into, Table t, int tag) {
        for (Object[] row : t.rows) {
            into.put(t.text(row, "auth_name") + ' ' + t.text(row, "code"), Integer.valueOf(tag));
        }
    }

    // ------------------------------------------------------------------ extents, grids, names

    private void addExtents(QuoteDump d) {
        final Table t = d.table("extent");
        writer.addTable(PjdxFormat.S_EXTENT, "extent", t.rows, 2, authCode(t),
                new PjdxWriter.RowEmitter() {
                    @Override
                    public void emit(PjdxWriter.Enc e, Object[] row) {
                        e.str(t.text(row, "name"));
                        e.str(t.text(row, "description"));
                        // Stored west, south, east, north -- not upstream's south, north, west, east.
                        // The reader reads them in this order; both sides say so explicitly.
                        e.dbl(t.real(row, "west_lon"));
                        e.dbl(t.real(row, "south_lat"));
                        e.dbl(t.real(row, "east_lon"));
                        e.dbl(t.real(row, "north_lat"));
                        e.bool(t.bool(row, "deprecated"));
                    }
                });
    }

    private void addGridAlternatives(QuoteDump d) {
        final Table t = d.table("grid_alternatives");
        writer.addTable(PjdxFormat.S_GRID_ALTERNATIVE, "grid_alternatives", t.rows, 1,
                new PjdxWriter.KeyExtractor() {
                    @Override
                    public Object[] key(Object[] row) {
                        return new Object[]{t.text(row, "original_grid_name")};
                    }
                },
                new PjdxWriter.RowEmitter() {
                    @Override
                    public void emit(PjdxWriter.Enc e, Object[] row) {
                        e.str(t.text(row, "proj_grid_name"));
                        e.str(t.text(row, "old_proj_grid_name"));
                        e.str(t.text(row, "proj_grid_format"));
                        e.str(t.text(row, "proj_method"));
                        e.bool(t.bool(row, "inverse_direction"));
                        e.str(t.text(row, "url"));
                        e.tri(t.tri(row, "direct_download"));
                        e.tri(t.tri(row, "open_license"));
                        e.str(t.text(row, "directory"));
                    }
                });
    }

    private void addAliases(QuoteDump d) {
        final Table t = d.table("alias_name");
        writer.addTable(PjdxFormat.S_ALIAS, "alias_name", t.rows, 4,
                new PjdxWriter.KeyExtractor() {
                    @Override
                    public Object[] key(Object[] row) {
                        return new Object[]{Integer.valueOf(tagOf(t.text(row, "table_name"))),
                                t.text(row, "auth_name"), t.text(row, "code"),
                                t.text(row, "alt_name")};
                    }
                },
                new PjdxWriter.RowEmitter() {
                    @Override
                    public void emit(PjdxWriter.Enc e, Object[] row) {
                        e.str(t.text(row, "source"));
                    }
                });
    }

    private void addSupersessions(QuoteDump d) {
        final Table t = d.table("supersession");
        writer.addTable(PjdxFormat.S_SUPERSESSION, "supersession", t.rows, 3,
                new PjdxWriter.KeyExtractor() {
                    @Override
                    public Object[] key(Object[] row) {
                        return new Object[]{
                                Integer.valueOf(tagOf(t.text(row, "superseded_table_name"))),
                                t.text(row, "superseded_auth_name"), t.text(row, "superseded_code")};
                    }
                },
                new PjdxWriter.RowEmitter() {
                    @Override
                    public void emit(PjdxWriter.Enc e, Object[] row) {
                        e.uint(tagOf(t.text(row, "replacement_table_name")));
                        e.str(t.text(row, "replacement_auth_name"));
                        e.str(t.text(row, "replacement_code"));
                        e.str(t.text(row, "source"));
                        e.bool(t.bool(row, "same_source_target_crs"));
                    }
                });
    }

    private void addDeprecations(QuoteDump d) {
        final Table t = d.table("deprecation");
        writer.addTable(PjdxFormat.S_DEPRECATION, "deprecation", t.rows, 3,
                new PjdxWriter.KeyExtractor() {
                    @Override
                    public Object[] key(Object[] row) {
                        return new Object[]{Integer.valueOf(tagOf(t.text(row, "table_name"))),
                                t.text(row, "deprecated_auth_name"),
                                t.text(row, "deprecated_code")};
                    }
                },
                new PjdxWriter.RowEmitter() {
                    @Override
                    public void emit(PjdxWriter.Enc e, Object[] row) {
                        e.str(t.text(row, "replacement_auth_name"));
                        e.str(t.text(row, "replacement_code"));
                        e.str(t.text(row, "source"));
                    }
                });
    }

    // ------------------------------------------------------------------ indexes

    private static final String[] CRS_TABLES = {
            "geodetic_crs", "projected_crs", "vertical_crs", "compound_crs", "engineering_crs"};

    private static final int[] CRS_TAGS = {
            PjdxFormat.TAG_GEODETIC_CRS, PjdxFormat.TAG_PROJECTED_CRS, PjdxFormat.TAG_VERTICAL_CRS,
            PjdxFormat.TAG_COMPOUND_CRS, PjdxFormat.TAG_ENGINEERING_CRS};

    private static final int[] CRS_SECTIONS = {
            PjdxFormat.S_GEODETIC_CRS, PjdxFormat.S_PROJECTED_CRS, PjdxFormat.S_VERTICAL_CRS,
            PjdxFormat.S_COMPOUND_CRS, PjdxFormat.S_ENGINEERING_CRS};

    private void buildCrsIndexes(QuoteDump d) {
        PjdxWriter.PendingIndex byCode =
                writer.newIndex(PjdxFormat.X_CRS_BY_CODE, "X_CRS_BY_CODE", 4);
        PjdxWriter.PendingIndex byDatum =
                writer.newIndex(PjdxFormat.X_CRS_BY_DATUM, "X_CRS_BY_DATUM", 6);

        for (int i = 0; i < CRS_TABLES.length; i++) {
            Table t = d.table(CRS_TABLES[i]);
            for (Object[] row : t.rows) {
                String a = t.text(row, "auth_name");
                String c = t.text(row, "code");
                int aid = writer.pool.id(a);
                int cid = writer.pool.id(c);
                int rowIndex = writer.rowIndex(CRS_SECTIONS[i], aid, cid);
                if (rowIndex < 0) {
                    throw new IllegalStateException(CRS_TABLES[i] + " row " + a + ":" + c
                            + " has no encoded row");
                }
                byCode.add(aid, cid, CRS_TAGS[i], rowIndex);
            }
        }

        // Geodetic and vertical CRSs indexed by their datum. This is the pivot query for operation
        // search: the authority publishes transformations between CRSs, so relating two datums means
        // finding the CRSs on each.
        addCrsByDatum(byDatum, d.table("geodetic_crs"), PjdxFormat.TAG_GEODETIC_DATUM,
                PjdxFormat.TAG_GEODETIC_CRS);
        addCrsByDatum(byDatum, d.table("vertical_crs"), PjdxFormat.TAG_VERTICAL_DATUM,
                PjdxFormat.TAG_VERTICAL_CRS);
    }

    private void addCrsByDatum(PjdxWriter.PendingIndex byDatum, Table t, int datumTag,
                               int crsTag) {
        for (Object[] row : t.rows) {
            String da = t.text(row, "datum_auth_name");
            String dc = t.text(row, "datum_code");
            if (da == null || dc == null) {
                // 0 of the shipped geodetic CRSs, but the schema permits it for a text_definition CRS.
                continue;
            }
            byDatum.add(datumTag, writer.pool.id(da), writer.pool.id(dc), crsTag,
                    writer.pool.id(t.text(row, "auth_name")), writer.pool.id(t.text(row, "code")));
        }
    }

    private static final String[] OP_TABLES = {
            "helmert_transformation_table", "grid_transformation", "other_transformation",
            "concatenated_operation"};

    private static final int[] OP_TAGS = {
            PjdxFormat.TAG_HELMERT_TRANSFORMATION, PjdxFormat.TAG_GRID_TRANSFORMATION,
            PjdxFormat.TAG_OTHER_TRANSFORMATION, PjdxFormat.TAG_CONCATENATED_OPERATION};

    private static final int[] OP_SECTIONS = {
            PjdxFormat.S_HELMERT_TRANSFORMATION, PjdxFormat.S_GRID_TRANSFORMATION,
            PjdxFormat.S_OTHER_TRANSFORMATION, PjdxFormat.S_CONCATENATED_OPERATION};

    private void buildOperationIndexes(QuoteDump d) {
        PjdxWriter.PendingIndex bySrc =
                writer.newIndex(PjdxFormat.X_OP_BY_SOURCE_TARGET, "X_OP_BY_SOURCE_TARGET", 6);
        PjdxWriter.PendingIndex byTgt =
                writer.newIndex(PjdxFormat.X_OP_BY_TARGET_SOURCE, "X_OP_BY_TARGET_SOURCE", 6);
        for (int i = 0; i < OP_TABLES.length; i++) {
            Table t = d.table(OP_TABLES[i]);
            for (Object[] row : t.rows) {
                int aid = writer.pool.id(t.text(row, "auth_name"));
                int cid = writer.pool.id(t.text(row, "code"));
                int rowIndex = writer.rowIndex(OP_SECTIONS[i], aid, cid);
                int sa = writer.pool.id(t.text(row, "source_crs_auth_name"));
                int sc = writer.pool.id(t.text(row, "source_crs_code"));
                int ta = writer.pool.id(t.text(row, "target_crs_auth_name"));
                int tc = writer.pool.id(t.text(row, "target_crs_code"));
                bySrc.add(sa, sc, ta, tc, OP_TAGS[i], rowIndex);
                byTgt.add(ta, tc, sa, sc, OP_TAGS[i], rowIndex);
            }
        }
    }

    private void buildUsageIndex(QuoteDump d) {
        Table t = d.table("usage");
        PjdxWriter.PendingIndex x =
                writer.newIndex(PjdxFormat.X_USAGE_BY_OBJECT, "X_USAGE_BY_OBJECT", 5);
        for (Object[] row : t.rows) {
            x.add(tagOf(t.text(row, "object_table_name")),
                    writer.pool.id(t.text(row, "object_auth_name")),
                    writer.pool.id(t.text(row, "object_code")),
                    writer.pool.id(t.text(row, "extent_auth_name")),
                    writer.pool.id(t.text(row, "extent_code")));
        }
    }

    /**
     * The CRS name index covers both the CRS's own name and every {@code alias_name} for it, under the
     * same normalisation the reader applies. Pass one cannot collect these strings from a column,
     * because they are synthesised — hence {@link #collectNormalisedNames}.
     */
    private void buildNameIndex(QuoteDump d) {
        PjdxWriter.PendingIndex x =
                writer.newIndex(PjdxFormat.X_CRS_BY_NAME, "X_CRS_BY_NAME", 4);
        for (int i = 0; i < CRS_TABLES.length; i++) {
            Table t = d.table(CRS_TABLES[i]);
            for (Object[] row : t.rows) {
                String norm = PjdxFormat.normalizeName(t.text(row, "name"));
                if (norm == null || norm.isEmpty()) {
                    continue;
                }
                x.add(writer.pool.id(norm), CRS_TAGS[i], writer.pool.id(t.text(row, "auth_name")),
                        writer.pool.id(t.text(row, "code")));
            }
        }
        Table alias = d.table("alias_name");
        for (Object[] row : alias.rows) {
            int tag = tagOf(alias.text(row, "table_name"));
            if (!isCrsTag(tag)) {
                continue;
            }
            String norm = PjdxFormat.normalizeName(alias.text(row, "alt_name"));
            if (norm == null || norm.isEmpty()) {
                continue;
            }
            x.add(writer.pool.id(norm), tag, writer.pool.id(alias.text(row, "auth_name")),
                    writer.pool.id(alias.text(row, "code")));
        }
    }

    private void collectNormalisedNames(QuoteDump d) {
        for (String table : CRS_TABLES) {
            Table t = d.table(table);
            for (Object[] row : t.rows) {
                writer.pool.add(PjdxFormat.normalizeName(t.text(row, "name")));
            }
        }
        Table alias = d.table("alias_name");
        for (Object[] row : alias.rows) {
            if (isCrsTag(tagOf(alias.text(row, "table_name")))) {
                writer.pool.add(PjdxFormat.normalizeName(alias.text(row, "alt_name")));
            }
        }
    }

    private static boolean isCrsTag(int tag) {
        for (int t : CRS_TAGS) {
            if (t == tag) {
                return true;
            }
        }
        return false;
    }

    private void buildAuthorityIndex(QuoteDump d) {
        PjdxWriter.PendingIndex x =
                writer.newIndex(PjdxFormat.X_AUTHORITIES, "X_AUTHORITIES", 1);
        java.util.TreeSet<String> auths = new java.util.TreeSet<String>();
        for (String table : CRS_TABLES) {
            collectAuthorities(auths, d.table(table));
        }
        for (String table : OP_TABLES) {
            collectAuthorities(auths, d.table(table));
        }
        collectAuthorities(auths, d.table("conversion_table"));
        collectAuthorities(auths, d.table("geodetic_datum"));
        collectAuthorities(auths, d.table("vertical_datum"));
        collectAuthorities(auths, d.table("ellipsoid"));
        collectAuthorities(auths, d.table("prime_meridian"));
        collectAuthorities(auths, d.table("unit_of_measure"));
        collectAuthorities(auths, d.table("extent"));
        for (String a : auths) {
            x.add(writer.pool.id(a));
        }
    }

    private static void collectAuthorities(java.util.Set<String> into, Table t) {
        for (Object[] row : t.rows) {
            into.add(t.text(row, "auth_name"));
        }
    }

    // ------------------------------------------------------------------ helpers

    private static PjdxWriter.KeyExtractor authCode(final Table t) {
        return new PjdxWriter.KeyExtractor() {
            @Override
            public Object[] key(Object[] row) {
                return new Object[]{t.text(row, "auth_name"), t.text(row, "code")};
            }
        };
    }

    /** Upstream table name to the format's table tag. Fails loudly on an unknown name. */
    static int tagOf(String tableName) {
        for (int i = 0; i < PjdxFormat.TAG_TABLE_NAMES.length; i++) {
            if (PjdxFormat.TAG_TABLE_NAMES[i].equals(tableName)) {
                return i;
            }
        }
        throw new IllegalStateException("no table tag for upstream table '" + tableName
                + "'; the transcoder must be updated deliberately rather than dropping the row");
    }

    private static String sectionName(int sectionId) {
        java.lang.reflect.Field[] fields = PjdxFormat.class.getFields();
        for (java.lang.reflect.Field f : fields) {
            if (f.getType() == int.class
                    && (f.getName().startsWith("S_") || f.getName().startsWith("X_"))) {
                try {
                    if (f.getInt(null) == sectionId) {
                        return f.getName();
                    }
                } catch (IllegalAccessException ignored) {
                    // Public static field of a public class; cannot happen.
                }
            }
        }
        return "section " + sectionId;
    }

    private static String hex(byte[] b) {
        char[] digits = "0123456789abcdef".toCharArray();
        char[] out = new char[b.length * 2];
        for (int i = 0; i < b.length; i++) {
            int v = b[i] & 0xFF;
            out[i * 2] = digits[v >>> 4];
            out[i * 2 + 1] = digits[v & 0xF];
        }
        return new String(out);
    }

    static {
        // Guards a silent mismatch: the tag table and the section arrays are written by hand and must
        // agree, and a mistake here would misfile whole tables rather than fail.
        if (CRS_TABLES.length != CRS_TAGS.length || CRS_TABLES.length != CRS_SECTIONS.length
                || OP_TABLES.length != OP_TAGS.length || OP_TABLES.length != OP_SECTIONS.length) {
            throw new AssertionError("CRS/operation table, tag and section arrays disagree: "
                    + Arrays.toString(CRS_TABLES) + " " + Arrays.toString(OP_TABLES));
        }
        if (HELMERT_PARAMS.length != 19) {
            throw new AssertionError("HELMERT_PARAMS must have 19 entries");
        }
    }
}
