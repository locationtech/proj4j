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

/**
 * Vertical CRSs, compound CRSs and the vertical grid shift — the third dimension.
 *
 * <h2>What is here and what is not</h2>
 *
 * <ul>
 * <li>{@link org.locationtech.proj4j.vertical.VGridShiftOperator} — {@code +proj=vgridshift},
 *     ported from {@code 9.8.1:src/transformations/vgridshift.cpp}. This is the step PROJ
 *     hides behind {@code +geoidgrids}, and {@code pipeline.Cs2csOperator} now builds it
 *     automatically exactly where {@code create.cpp} does. It reads through
 *     {@link org.locationtech.proj4j.datum.VerticalGrid}, and therefore through the
 *     deterministic resolver chain in {@code org.locationtech.proj4j.resource}, never the
 *     working directory.</li>
 * <li>{@link org.locationtech.proj4j.vertical.VerticalCrs} and
 *     {@link org.locationtech.proj4j.vertical.CompoundCrs} — the types behind
 *     {@code EPSG:4326+5773}, reached from
 *     {@link org.locationtech.proj4j.CRSFactory#createCompound(java.lang.String)}.</li>
 * <li>{@link org.locationtech.proj4j.vertical.VerticalCrsRegistry} — a small,
 *     provenance-documented table plus a registration seam. It is small because it has to
 *     be; see below.</li>
 * </ul>
 *
 * <h2>The data gap, stated plainly</h2>
 *
 * <p>{@code proj4j-epsg}'s {@code proj4/nad/epsg} holds 5,755 entries and <b>not one vertical
 * CRS</b>. {@code EPSG:5773}, {@code 3855}, {@code 5798}, {@code 5714}, {@code 5715},
 * {@code 5703} and {@code 4937} are all absent, and so is {@code EPSG:4979} — WGS 84
 * geographic 3D. That is not an oversight in the file: it is a PROJ.4-era {@code +init=}
 * dictionary, and a proj-string has no way to denote a standalone vertical CRS.
 * {@code EPSG:4979} is absent for a different reason — its proj-string,
 * {@code +proj=longlat +datum=WGS84 +no_defs}, is byte-identical to {@code EPSG:4326}'s, so
 * the dictionary format cannot distinguish 2D from 3D either.
 *
 * <p>The real supply is the {@code proj4j-db} artifact, which will carry all of EPSG's
 * vertical CRSs. Until then the built-in table covers the nine codes whose values could be
 * read directly out of PROJ 9.8.1 on the build machine, and every other code is an
 * {@link org.locationtech.proj4j.vertical.UnknownVerticalCrsException} that names itself.
 *
 * <h2>Height semantics</h2>
 *
 * <p>{@code NaN} is this library's "no height" sentinel — see
 * {@link org.locationtech.proj4j.ProjCoordinate}, whose two-argument constructor,
 * {@code setValue(x, y)} and {@code clearZ()} all set it. A transform must never turn that
 * sentinel into a number. PROJ achieves the same thing for a two-dimensional operation with
 * {@code +proj=push +v_3} / {@code +proj=pop +v_3} around the geocentric leg, which restores
 * the caller's third ordinate byte for byte.
 *
 * @since 1.5
 */
package org.locationtech.proj4j.vertical;
