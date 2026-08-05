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
 * Reading and writing coordinate reference systems as PROJJSON.
 * <p>
 * PROJJSON carries the same content as WKT2 in JSON syntax, so this package shares the
 * {@link org.locationtech.proj4j.io.wkt.CrsDefinition} model with
 * {@link org.locationtech.proj4j.io.wkt}: read PROJJSON and write WKT2, or the reverse, without
 * going through a coordinate.
 * <pre>
 * CoordinateReferenceSystem crs = new ProjJsonReader().read(json);
 * String json = new ProjJsonWriter().write(definition);
 * </pre>
 * The JSON reader and writer are hand-written and about three hundred lines. <strong>There is no
 * JSON library dependency and there will not be one</strong>: the reason this code exists is that a
 * consumer can delete Apache SIS and the {@code catch (LinkageError)} that its duplicated GeoAPI
 * classes force on them, and adding a Jackson or Gson dependency to achieve that would move the
 * hazard rather than remove it.
 * <p>
 * Member order and two-space indentation match PROJ's {@code exportToJSON}, so a document read from
 * PROJJSON is written back byte-for-byte. Follows {@code data/projjson.schema.json} and the
 * {@code json_import} tests of PROJ 9.8.1.
 */
package org.locationtech.proj4j.io.projjson;
