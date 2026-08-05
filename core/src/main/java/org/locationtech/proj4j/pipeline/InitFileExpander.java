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
package org.locationtech.proj4j.pipeline;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.locationtech.proj4j.io.Proj4FileReader;

/**
 * {@code +init=<file>:<section>} expansion under
 * {@code proj_context_use_proj4_init_rules(ctx, 1)}.
 *
 * <h2>What "proj4 init rules" actually means</h2>
 *
 * <p>Two entirely different things can happen to {@code +init=epsg:27572}. With the
 * modern rules PROJ resolves the code through {@code proj.db}, builds an ISO 19111
 * CRS, and takes axis order and units from the <b>authority</b>. With
 * {@code use_proj4_init_rules} it reads the section verbatim out of the
 * {@code epsg} init <em>file</em> and hands the tokens straight to
 * {@code pj_init} — so axis order, units, prime meridian and datum shift all come
 * from the init string and nothing else looks at the authority
 * ({@code 9.8.1:src/init.cpp:210-315}).
 *
 * <p>Every GIGS file opens with {@code use_proj4_init_rules true}, and the legacy
 * semantics are the point of the test: {@code 2049} carries {@code +axis=wsu},
 * {@code 4807} and {@code 27572} carry {@code +pm=paris}, {@code 2921} uses
 * {@code +units=ft} where {@code 3568} uses {@code +units=us-ft}, {@code 3376} puts
 * {@code +no_uoff} on an {@code omerc}. None of that survives an authority lookup.
 *
 * <p>This class therefore implements only the file path, which is also the only
 * path proj4j can implement: there is no {@code proj.db} here. It reads through the
 * existing {@link Proj4FileReader} rather than re-tokenising the init files, so
 * there is exactly one parser for them.
 *
 * <h2>Appended, never inserted</h2>
 *
 * <p>{@code pj_expand_init_internal} ({@code init.cpp:352-397}) appends the
 * expansion to the <b>end</b> of the parameter list and leaves the
 * {@code init=} token in place. Combined with first-match-wins that makes the
 * expansion the <em>lowest</em>-precedence source, so
 * {@code +init=foo:bar +ellps=intl} overrides the {@code +ellps} the section
 * declares. {@link PipelineFactory} relies on this for
 * {@code +proj=pipeline +towgs84=0,0,0}, which is how {@code gigs/5103.1},
 * {@code 5111.1} and {@code 5112} switch off a double datum shift.
 *
 * <p>Expansions are cached per key, as {@code pj_search_initcache} caches them;
 * without it a 20-file GIGS sweep re-tokenises the 1 MB {@code epsg} file over a
 * hundred times.
 *
 * <h2>Failures here are bad parameter values, not missing grids</h2>
 *
 * <p>Every rejection in this class is
 * {@link PipelineErrorCode#INVALID_INIT_KEY} — {@code errno 1027}, cause
 * {@link org.locationtech.proj4j.ErrorCause#INVALID_PARAM_VALUE}. That matches
 * {@code get_init_string} ({@code init.cpp:105,119,134}), which sets
 * {@code PROJ_ERR_INVALID_OP_ILLEGAL_ARG_VALUE} for a missing colon, an init file it
 * cannot open, and a section it cannot find alike. It is deliberately <em>not</em>
 * {@link PipelineErrorCode#FILE_NOT_FOUND_OR_INVALID}, which is reserved for grid and
 * model files the operation needs and carries
 * {@link org.locationtech.proj4j.ErrorCause#MISSING_GRID}: one enum constant used to
 * serve both, so a caller catching an unreadable grid was told its CRS parameter value
 * was invalid.
 *
 * <p>Thread-safe: the cache is synchronised and the cached lists are unmodifiable.
 */
final class InitFileExpander {

    private final Proj4FileReader reader = new Proj4FileReader();

    private final Map<String, List<String>> cache = new HashMap<String, List<String>>();

    /**
     * Expand one {@code init=} token.
     *
     * @param token the token as it appears in the parameter list, either
     *              {@code "init=epsg:27572"} or bare {@code "epsg:27572"}
     * @return the section's tokens, without {@code '+'} prefixes; never
     *         {@code null} and never empty
     * @throws PipelineDefinitionException if the file or section does not exist
     */
    List<String> expand(final String token) {
        final String key = stripInitPrefix(token);
        synchronized (cache) {
            final List<String> cached = cache.get(key);
            if (cached != null) {
                return cached;
            }
        }
        final int colon = key.indexOf(':');
        if (colon <= 0 || colon == key.length() - 1) {
            throw new PipelineDefinitionException(PipelineErrorCode.INVALID_INIT_KEY,
                    "+init=" + key + " is not of the form <file>:<section>");
        }
        final String file = key.substring(0, colon);
        final String section = key.substring(colon + 1);

        final String[] params;
        try {
            params = reader.readParametersFromFile(file, section);
        } catch (final IOException e) {
            throw new PipelineDefinitionException(PipelineErrorCode.INVALID_INIT_KEY,
                    "+init=" + key + ": could not read the init file", e);
        } catch (final IllegalStateException e) {
            // Proj4FileReader signals a missing resource this way.
            throw new PipelineDefinitionException(PipelineErrorCode.INVALID_INIT_KEY,
                    "+init=" + key + ": no init file \"" + file + "\" on the classpath. The EPSG "
                            + "init dictionary ships in the separate proj4j-epsg artifact.", e);
        }
        if (params == null || params.length == 0) {
            throw new PipelineDefinitionException(PipelineErrorCode.INVALID_INIT_KEY,
                    "+init=" + key + ": no section <" + section + "> in init file \"" + file + "\"");
        }

        final List<String> tokens = normalise(params);
        synchronized (cache) {
            cache.put(key, tokens);
        }
        return tokens;
    }

    /**
     * {@code get_init}'s key handling ({@code init.cpp:220-227}): accept
     * {@code init=file:section}, {@code +init=file:section} and a bare
     * {@code file:section}, keeping everything after the <em>first</em>
     * {@code "init="}.
     */
    private static String stripInitPrefix(final String token) {
        final int at = token.indexOf("init=");
        return at < 0 ? token : token.substring(at + 5);
    }

    /** Strip the {@code '+'} prefixes {@code Proj4FileReader} adds back. */
    private static List<String> normalise(final String[] params) {
        final String[] out = new String[params.length];
        int n = 0;
        for (int i = 0; i < params.length; i++) {
            String token = params[i];
            if (token == null) {
                continue;
            }
            while (token.startsWith("+")) {
                token = token.substring(1);
            }
            if (!token.isEmpty()) {
                out[n++] = token;
            }
        }
        return Collections.unmodifiableList(Arrays.asList(Arrays.copyOf(out, n)));
    }
}
