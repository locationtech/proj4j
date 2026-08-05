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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses a {@code sqlite3 .mode quote} dump. Build-time only; not shipped in the jar.
 *
 * <h2>Why this format</h2>
 * {@code .mode quote} is the only sqlite3 output mode that is <em>lossless</em> for the data proj4j
 * needs, and that matters more than convenience:
 * <ul>
 *   <li>Text is single-quoted with internal quotes doubled, so a value containing a comma, a tab or a
 *       newline survives. One of the 4,314 {@code extent} descriptions genuinely contains a newline;
 *       a tab- or comma-separated dump would silently lose the rest of that row.</li>
 *   <li>{@code NULL} is a bare keyword, distinct from the empty string. Upstream leans on that
 *       distinction hard — a null {@code accuracy} means "the authority published none", and an
 *       {@code open_license} of null means "not established", not "no".</li>
 *   <li>Reals are printed by sqlite's own shortest-round-trip formatter, so
 *       {@code Double.parseDouble} reproduces the stored {@code double} bit for bit. Verified against
 *       the source for every double the transcoder writes.</li>
 * </ul>
 * The alternative — {@code .mode json} — would need a JSON parser, and a JSON parser is exactly the
 * kind of thing that becomes a runtime dependency.
 *
 * <h2>Column mapping is by name</h2>
 * The header row is parsed, not assumed. The generator asks for columns by name and fails loudly if
 * one is missing, so an upstream schema change is a build failure rather than a silent one-column
 * shift that would put a prime meridian's longitude into its unit code.
 */
final class QuoteDump {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** One dumped table: its column names and its rows, values typed as below. */
    static final class Table {
        final String name;
        final String[] columns;
        final List<Object[]> rows = new ArrayList<Object[]>();
        private final Map<String, Integer> columnIndex = new LinkedHashMap<String, Integer>();

        Table(String name, String[] columns) {
            this.name = name;
            this.columns = columns;
            for (int i = 0; i < columns.length; i++) {
                columnIndex.put(columns[i], Integer.valueOf(i));
            }
        }

        int column(String columnName) {
            Integer i = columnIndex.get(columnName);
            if (i == null) {
                throw new IllegalStateException("table " + name + " has no column '" + columnName
                        + "'; it has " + columnIndex.keySet()
                        + ". The upstream schema has changed and the transcoder must be updated"
                        + " deliberately rather than guessing.");
            }
            return i.intValue();
        }

        /**
         * A value as canonical text. Integers become their decimal spelling, which is what makes an
         * {@code INTEGER_OR_TEXT} code comparable with the string a caller typed: 1,803 geodetic CRS
         * codes are integers, 345 are text, and both must answer to {@code crs("EPSG", "4326")}.
         */
        String text(Object[] row, String columnName) {
            Object v = row[column(columnName)];
            if (v == null) {
                return null;
            }
            if (v instanceof Long) {
                return v.toString();
            }
            if (v instanceof Double) {
                // No code or name column is a real in the transcoded subset; if that ever changes,
                // fail rather than invent a spelling.
                throw new IllegalStateException("table " + name + " column " + columnName
                        + " holds a real (" + v + ") where text was expected");
            }
            return (String) v;
        }

        /** @return the value, or {@link Double#NaN} for NULL. */
        double real(Object[] row, String columnName) {
            Object v = row[column(columnName)];
            if (v == null) {
                return Double.NaN;
            }
            if (v instanceof Long) {
                return ((Long) v).doubleValue();
            }
            if (v instanceof Double) {
                return ((Double) v).doubleValue();
            }
            return Double.parseDouble((String) v);
        }

        int integer(Object[] row, String columnName) {
            Object v = row[column(columnName)];
            if (v == null) {
                throw new IllegalStateException("table " + name + " column " + columnName + " is NULL"
                        + " where an integer is required");
            }
            if (v instanceof Long) {
                return ((Long) v).intValue();
            }
            return Integer.parseInt(v.toString());
        }

        boolean bool(Object[] row, String columnName) {
            Object v = row[column(columnName)];
            return v != null && !"0".equals(v.toString());
        }

        /** @return null for SQL NULL, otherwise the boolean. Upstream really uses all three states. */
        Boolean tri(Object[] row, String columnName) {
            Object v = row[column(columnName)];
            return v == null ? null : Boolean.valueOf(!"0".equals(v.toString()));
        }
    }

    private final Map<String, Table> tables = new LinkedHashMap<String, Table>();

    static QuoteDump read(Path path) throws IOException {
        QuoteDump d = new QuoteDump();
        Reader r = new InputStreamReader(Files.newInputStream(path), UTF8);
        try {
            d.parse(new BufferedReader(r, 1 << 16));
        } finally {
            r.close();
        }
        return d;
    }

    Table table(String name) {
        Table t = tables.get(name);
        if (t == null) {
            throw new IllegalStateException("the dump has no table '" + name + "'; it has "
                    + tables.keySet());
        }
        return t;
    }

    Map<String, Table> tables() {
        return tables;
    }

    private void parse(BufferedReader in) throws IOException {
        Table current = null;
        boolean expectHeader = false;
        String line;
        while ((line = in.readLine()) != null) {
            if (line.startsWith("#TABLE ")) {
                current = null;
                expectHeader = true;
                pendingName = line.substring(7).trim();
                continue;
            }
            if (line.equals("#END")) {
                break;
            }
            if (line.isEmpty()) {
                continue;
            }
            // A quoted value may contain a newline, so a logical row can span physical lines. Values
            // are accumulated until the quote state closes.
            StringBuilder logical = new StringBuilder(line);
            while (!quotesBalanced(logical)) {
                String more = in.readLine();
                if (more == null) {
                    throw new IOException("unterminated quoted value at end of dump");
                }
                logical.append('\n').append(more);
            }
            Object[] values = split(logical.toString());
            if (expectHeader) {
                String[] cols = new String[values.length];
                for (int i = 0; i < values.length; i++) {
                    cols[i] = String.valueOf(values[i]);
                }
                current = new Table(pendingName, cols);
                tables.put(pendingName, current);
                expectHeader = false;
                continue;
            }
            if (current == null) {
                throw new IOException("data row before any #TABLE marker: " + line);
            }
            if (values.length != current.columns.length) {
                throw new IOException("table " + current.name + ": expected "
                        + current.columns.length + " values, got " + values.length);
            }
            current.rows.add(values);
        }
    }

    private String pendingName;

    /** True iff every {@code '} in {@code s} is matched, treating {@code ''} as an escaped quote. */
    private static boolean quotesBalanced(CharSequence s) {
        boolean inQuote = false;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\'') {
                if (inQuote && i + 1 < s.length() && s.charAt(i + 1) == '\'') {
                    i++;
                } else {
                    inQuote = !inQuote;
                }
            }
        }
        return !inQuote;
    }

    /**
     * Splits one logical row. Values are {@code String} for quoted text, {@code Long} for integers,
     * {@code Double} for reals, {@code null} for {@code NULL}, and a hex {@code String} for a blob
     * (which the transcoded subset never contains, but is parsed rather than mis-parsed).
     */
    private static Object[] split(String s) {
        List<Object> out = new ArrayList<Object>();
        int i = 0;
        int n = s.length();
        while (i <= n) {
            if (i == n) {
                // Trailing empty field only if the row ended with a comma.
                if (n > 0 && s.charAt(n - 1) == ',') {
                    out.add(null);
                }
                break;
            }
            char c = s.charAt(i);
            if (c == '\'') {
                StringBuilder sb = new StringBuilder();
                i++;
                while (true) {
                    if (i >= n) {
                        throw new IllegalStateException("unterminated string in row: " + s);
                    }
                    char d = s.charAt(i);
                    if (d == '\'') {
                        if (i + 1 < n && s.charAt(i + 1) == '\'') {
                            sb.append('\'');
                            i += 2;
                            continue;
                        }
                        i++;
                        break;
                    }
                    sb.append(d);
                    i++;
                }
                out.add(sb.toString());
            } else {
                int start = i;
                while (i < n && s.charAt(i) != ',') {
                    i++;
                }
                String token = s.substring(start, i).trim();
                out.add(scalar(token));
            }
            if (i < n && s.charAt(i) == ',') {
                i++;
                if (i == n) {
                    out.add(null);
                    break;
                }
            } else if (i < n) {
                throw new IllegalStateException("expected ',' at offset " + i + " in row: " + s);
            }
        }
        return out.toArray();
    }

    private static Object scalar(String token) {
        if (token.isEmpty() || "NULL".equals(token)) {
            return null;
        }
        if (token.length() > 2 && (token.charAt(0) == 'X' || token.charAt(0) == 'x')
                && token.charAt(1) == '\'') {
            return token;
        }
        boolean real = false;
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c == '.' || c == 'e' || c == 'E' || c == 'n' || c == 'N' || c == 'i' || c == 'I') {
                real = true;
                break;
            }
        }
        try {
            return real ? (Object) Double.valueOf(token) : (Object) Long.valueOf(token);
        } catch (NumberFormatException e) {
            // Not a number after all -- an unquoted token sqlite would only emit for a keyword we do
            // not expect. Keep it as text rather than losing it.
            return token;
        }
    }
}
