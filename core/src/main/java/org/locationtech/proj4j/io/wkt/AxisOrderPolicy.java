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
package org.locationtech.proj4j.io.wkt;

/**
 * What to do with the axis order a WKT or PROJJSON document declares.
 * <p>
 * This is the single most dangerous compatibility switch in this package, so it is explicit and
 * it is set in code. PROJ 6 and later honour authority axis order, which makes {@code EPSG:4326}
 * latitude-first; proj4j is longitude-first throughout, as is GeoJSON. A reader which faithfully
 * honoured every {@code AXIS[]} clause would therefore produce latitude-first CRSs and silently
 * break every existing proj4j caller — invisibly near the equator and the prime meridian, which
 * is exactly where test fixtures tend to live.
 * <p>
 * So the default is {@link #LEGACY} and the declared axes are <em>retained</em> on the
 * {@link CrsDefinition} regardless: nothing is discarded, and nothing is silently reordered. The
 * policy is applied once, at the boundary where a
 * {@link org.locationtech.proj4j.CoordinateReferenceSystem} is built.
 * <p>
 * There is deliberately <strong>no system property and no environment variable</strong>. Axis
 * order has to be a property of the code, not of whoever launched the JVM; a cluster-wide
 * environment variable that silently transposes coordinates is precisely the failure mode this
 * design exists to prevent.
 *
 * <h2>Where the policy comes from</h2>
 *
 * <p>{@link org.locationtech.proj4j.api.ProjContext} is now the authoritative holder, and it is the
 * only place the policy can be set. {@link org.locationtech.proj4j.api.Proj#createCrs(String,
 * org.locationtech.proj4j.api.ProjContext)} reads it from there, and
 * {@link org.locationtech.proj4j.api.Crs#withAxisOrderPolicy} re-derives a CRS under a different
 * one. Three levels of precedence, all introspectable, are described on {@code ProjContext}; there
 * is no fourth.
 *
 * <p>The constructors {@link WktReader#WktReader(AxisOrderPolicy)} and
 * {@link org.locationtech.proj4j.io.projjson.ProjJsonReader#ProjJsonReader(AxisOrderPolicy)} remain,
 * because a caller using the readers directly &mdash; to obtain a {@link CrsDefinition} and inspect
 * what a document declared, rather than to build a CRS &mdash; needs to say what the policy is
 * without constructing a context for it. They are the low-level seam;
 * {@code ProjContext} is the one an application configures.
 *
 * <p><b>This enum's type stays in this package.</b> It is referenced by {@link WktReader},
 * {@code ProjJsonReader} and {@link CrsDefinitions}, whose signatures are the boundary at which a
 * document's declared axes are turned into a CRS &mdash; which is where this decision belongs and
 * where its javadoc can be read next to the {@code AXIS[]} handling it governs.
 * {@code org.locationtech.proj4j.api} re-exports it through
 * {@link org.locationtech.proj4j.api.ProjContext#axisOrderPolicy()} rather than defining a second
 * enum of the same name, because two same-named policy enums that must be converted between each
 * other is exactly how a transposition bug gets introduced by a conversion function.
 *
 * @see org.locationtech.proj4j.api.ProjContext#axisOrderPolicy()
 * @see org.locationtech.proj4j.api.Crs#axisOrder()
 * @see org.locationtech.proj4j.api.Crs#isAxisOrderAuthoritative()
 */
public enum AxisOrderPolicy {

    /**
     * The default, and proj4j 1.4.3's behaviour exactly: the declared axis order is retained on
     * the {@link CrsDefinition} but is not applied, so {@code EPSG:4326} read from WKT takes
     * {@code (longitude, latitude)}. An explicit {@code +axis=} in a PROJ string is still
     * honoured by the transform engine, as it always was; this policy is only about what a
     * document's {@code AXIS[]} clauses do.
     */
    LEGACY,

    /**
     * PROJ 6+ and {@code cs2cs} semantics: the declared axis order is applied, so a WKT
     * definition of {@code EPSG:4326} produces a latitude-first CRS. Adopting this is a silent
     * behavioural change for existing callers; do it deliberately and re-baseline fixtures.
     *
     * <p><b>One limitation, reported rather than hidden.</b> "Authority axis order" is database
     * metadata, and Proj4J ships no CRS database, so for a CRS created from an
     * {@code authority:code} name there is nothing to read. Proj4J then applies the rule that EPSG
     * gives every geographic 2D CRS the latitude-then-longitude ellipsoidal coordinate system
     * EPSG:6422 &mdash; and marks the result as inferred:
     * {@link org.locationtech.proj4j.api.Crs#isAxisOrderAuthoritative()} returns {@code false} and
     * {@link org.locationtech.proj4j.api.Crs#axisOrderNote()} says which rule was used. A projected
     * CRS from a bare code is left east-north-up, likewise marked inferred. A CRS from WKT or
     * PROJJSON with {@code AXIS[]} clauses is <em>declared</em>, and is honoured exactly.
     */
    AUTHORITY,

    /**
     * Unconditionally normalised for visualisation, the equivalent of PROJ's
     * {@code proj_normalize_for_visualization}: easting/northing, longitude/latitude,
     * up-positive, overriding whatever the document declared.
     */
    VISUALISATION
}
