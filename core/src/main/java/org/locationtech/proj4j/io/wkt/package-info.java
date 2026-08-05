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

/**
 * Reading and writing coordinate reference systems as WKT.
 *
 * <h2>What this is for</h2>
 * A library that can parse a PROJ string but not a WKT string forces its users to find a second
 * one, and the second one is usually Apache SIS. That pulls in GeoAPI, and a classpath with two
 * incompatible copies of {@code org.opengis.util.CodeList} throws {@code NoSuchMethodError} — an
 * {@code Error}, not an {@code Exception} — from inside SIS's WKT parser. It passes local tests and
 * kills Spark executors.
 * <p>
 * So this package is hand-written, lives in {@code core}, and has <strong>no dependencies</strong>:
 * no JSON library, no GeoAPI, nothing but the JDK. There is a test,
 * {@code NoGeoApiInCoreTest}, which scans the compiled classes for the string
 * {@code org/opengis/} and fails the build if it appears. {@code proj4j-geoapi} remains a separate,
 * optional module and is itself a participant in that hazard; core never references it.
 *
 * <h2>Using it</h2>
 * <pre>
 * CoordinateReferenceSystem crs = new WktReader().read(wkt);            // any dialect, auto-detected
 * CrsDefinition def = new WktReader().readDefinition(wkt);              // to inspect what was said
 * String projString = CrsDefinitions.toProjParameterString(def, AxisOrderPolicy.LEGACY);
 * String wkt2 = new WktWriter().multiline().write(def);
 * </pre>
 * {@link org.locationtech.proj4j.io.wkt.WktReader} accepts all four dialects of
 * {@link org.locationtech.proj4j.io.wkt.WktDialect} — WKT2:2019, WKT2:2015, WKT1 as GDAL writes it
 * and WKT1 as ESRI writes it — detecting which by the same rule PROJ uses.
 * {@link org.locationtech.proj4j.io.wkt.WktWriter} produces WKT2.
 *
 * <h2>Axis order</h2>
 * The default is {@link org.locationtech.proj4j.io.wkt.AxisOrderPolicy#LEGACY}: longitude-first,
 * exactly as proj4j has always been, and as GeoJSON is. The {@code AXIS[]} clauses a document
 * declares are parsed faithfully and <em>retained</em> on the
 * {@link org.locationtech.proj4j.io.wkt.CrsDefinition} regardless of policy; the policy is applied
 * once, where a {@link org.locationtech.proj4j.CoordinateReferenceSystem} is built. There is no
 * system property and no environment variable, deliberately: a coordinate transposition that
 * depends on who launched the JVM is not a behaviour any caller can test against.
 *
 * <h2>What it refuses</h2>
 * Where a document describes something proj4j's engine would <em>silently ignore</em>, this package
 * throws {@link org.locationtech.proj4j.io.wkt.WktParseException} rather than returning a plausible
 * coordinate. Mercator variant B's standard parallel is converted to the equivalent scale factor
 * because proj4j's Mercator never reads {@code +lat_ts}; Equidistant Cylindrical with a real
 * standard parallel has no such equivalent and is refused. A vertical-only CRS is refused rather
 * than becoming a horizontal one, and a compound CRS yields its horizontal component with the
 * height left untransformed.
 *
 * <h2>Provenance</h2>
 * The grammar, the dialect detection, the method and parameter tables, the unit handling and the
 * derived-parameter rules are ported from PROJ at tag <strong>9.8.1</strong>:
 * {@code src/iso19111/io.cpp}, {@code src/iso19111/operation/parammappings.cpp},
 * {@code esriparammappings.cpp} and {@code conversion.cpp}. The tests are ported from
 * {@code test/unit/test_io.cpp} at the same tag, and say which case each came from.
 */
package org.locationtech.proj4j.io.wkt;
