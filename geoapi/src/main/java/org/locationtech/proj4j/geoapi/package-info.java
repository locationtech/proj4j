/*
 * Copyright 2025, PROJ4J contributors
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
 * Wraps the PROJ4J classes behind the equivalent GeoAPI interfaces.
 * This module provides a public class, {@link org.locationtech.proj4j.geoapi.Wrappers},
 * with overloaded {@code geoapi(…)} methods. Those methods expected a PROJ4J object in
 * argument and returns a view of that object as a GeoAPI type.
 *
 * <h2>Dependency to a Unit of Measurement library</h2>
 * This module requires a JSR-363 (Units of Measurement) implementation
 * to be present on the class-path or module-path.
 * The choice of an implementation is left to the user. Some implementations are
 * <a href="https://github.com/unitsofmeasurement/indriya">Indriya</a>,
 * <a href="https://unitsofmeasurement.github.io/seshat/">Seshat</a> and
 * <a href="https://sis.apache.org/">Apache SIS</a>.
 * The two latter support <abbr>EPSG</abbr> codes for units of measurement.
 *
 * <h2>Mutability</h2>
 * No information is copied. All methods of the views delegate their work to the PROJ4J implementation.
 * Consequently, since PROJ4J objects are mutable, changes to the wrapped PROJ4J object are immediately
 * reflected in the view. However, it is not recommended to change a wrapped PROJ4J object as CRS should
 * be immutable.
 *
 * <p>There is one exception to the above paragraph: whether an object is a geographic or projected <abbr>CRS</abbr>.
 * Because the type of a Java object cannot change dynamically, whether a <abbr>CRS</abbr> is geographic or projected
 * is determined at {@code geoapi(CoordinateReferenceSystem)} invocation time.</p>
 *
 * <h2>Serialization</h2>
 * The serialization details are not committed <abbr>API</abbr>.
 * Serialization is okay for exchanging objects between <abbr>JVM</abbr> running the same version of PROJ4J,
 * but is not guaranteed to be compatible between different versions of PROJ4J. This module does not define
 * {@code serialVersionUID} because the backing PROJ4J objects do not define those <abbr>UID</abbr> anyway.
 *
 * @author Martin Desruisseaux (Geomatys)
 */
package org.locationtech.proj4j.geoapi;
