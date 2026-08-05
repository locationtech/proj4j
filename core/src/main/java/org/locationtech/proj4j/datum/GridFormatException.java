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
 */
package org.locationtech.proj4j.datum;

import java.io.IOException;

/**
 * Thrown when a binary grid or database file declares a structure the bytes cannot support: an extent
 * whose node count does not fit the file, a non-positive or non-finite dimension, or a count whose
 * product would overflow the {@code int} that sizes the array.
 *
 * <h2>Why a named type, and why an {@code IOException}</h2>
 *
 * <p>The failures this replaces were <em>unnamed and unbounded</em>. Measured against the readers as
 * they stood, a hostile header produced one of four things, none of them an in-family exception:
 *
 * <table><caption>what the five sites did before this type existed</caption>
 * <tr><th>input</th><th>outcome</th></tr>
 * <tr><td>160-byte {@code CTABLE V2} declaring 100000&times;100000</td>
 *     <td>{@code OutOfMemoryError} — 1,410,065,408 references, 5.25 GiB</td></tr>
 * <tr><td>{@code NTv1} with a zero latitude increment</td>
 *     <td>no exception; {@code lim.phi} = {@code Integer.MIN_VALUE}</td></tr>
 * <tr><td>{@code NTv1} with a zero span <em>and</em> a zero increment</td>
 *     <td>no exception; {@code NaN} truncated to a plausible 1&times;1 grid</td></tr>
 * <tr><td>634-byte GeoTIFF declaring 40000&times;40000</td>
 *     <td>{@code OutOfMemoryError} — 6.4 GB, a 10,000,000:1 amplification</td></tr>
 * <tr><td>1,290-byte GeoTIFF declaring 65536&times;65536</td>
 *     <td>{@code ArrayIndexOutOfBoundsException: Index -65536 out of bounds for length 0}</td></tr>
 * <tr><td>358-byte GeoTIFF with {@code PlanarConfig=3} and 2<sup>28</sup> samples</td>
 *     <td>{@code OutOfMemoryError} — two {@code double[]}, 4 GB</td></tr>
 * </table>
 *
 * <p>{@code OutOfMemoryError} is an {@link Error}: it escapes {@code catch (Proj4jException)}
 * <em>and</em> {@code catch (Exception)}, and it is not attributable to the grid that caused it.
 * {@code ArrayIndexOutOfBoundsException} is unchecked, so it escapes the {@code catch (IOException)} in
 * {@link Grid#fromNadGrids} while being caught — anonymously — by the {@code catch (RuntimeException)}
 * in {@link Grid#describeNadGrids}. Neither says which grid, which field, or what the file actually
 * contained.
 *
 * <p>Extending {@link IOException} rather than inventing a new root is deliberate: every existing
 * handler on the grid path already catches {@code IOException} — {@code Grid.fromNadGrids},
 * {@code Grid.describeNadGrids}, {@code GridCache} — so the conversion is caught rather than newly
 * escaping. The named subtype exists so a test can assert the <em>type</em> and not just a substring of
 * a message.
 *
 * @since 1.5
 */
public class GridFormatException extends IOException {

    private static final long serialVersionUID = 1L;

    /**
     * @param message must name the file, the field, the declared value and the bound it violated
     */
    public GridFormatException(String message) {
        super(message);
    }
}
