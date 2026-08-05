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
 * A minimal {@code +proj=pipeline} engine.
 *
 * <h2>What this is for</h2>
 *
 * <p>PROJ 6 replaced "source CRS, target CRS, hope" with an explicit ordered
 * pipeline of operations, and the IOGP GIGS conformance suite is written entirely in
 * that form: each of its twenty test files is three {@code +proj=pipeline} strings
 * of two or three steps. proj4j had no pipeline at all, so all 1,170 GIGS assertions
 * were unreachable — not failing, just unrunnable.
 *
 * <p>This package is the smallest thing that runs them:
 *
 * <ul>
 * <li>{@link org.locationtech.proj4j.pipeline.ProjParams} — PROJ's
 *     {@code paralist}, with the two lookup rules everything else depends on:
 *     <b>first match wins</b>, and <b>lookup stops at the next {@code +step}</b>;</li>
 * <li>{@link org.locationtech.proj4j.pipeline.PipelineFactory} — parses the string,
 *     expands {@code +init=} out of the legacy init files, and assembles the
 *     steps;</li>
 * <li>{@link org.locationtech.proj4j.pipeline.Cs2csOperator} — a classic projection
 *     <em>plus</em> the hidden helper operations PROJ builds behind
 *     {@code +towgs84}, {@code +pm} and {@code +axis}. This is the crux: those
 *     helpers are what make a two-step pipeline of legacy {@code +init=} strings
 *     mean "local CRS, to the WGS84 hub, to another local CRS";</li>
 * <li>{@link org.locationtech.proj4j.pipeline.Pipeline} — the assembled operation,
 *     reporting its i/o unit domains the way {@code pipeline.cpp} computes them,
 *     because a conformance comparator picks its distance metric from exactly
 *     that.</li>
 * </ul>
 *
 * <h2>What this deliberately is not</h2>
 *
 * <p>Not PROJ's 4D model. There is no {@code PJ_COORD} union, no
 * {@code push}/{@code pop}/{@code set} stack, the time ordinate is carried rather
 * than transformed, and {@code helmert} is not a user-facing operator — it exists
 * only as the hidden helper described above. {@code +geoc}, time-unit conversion,
 * {@code +proj=gridshift} and {@code +proj=defmodel} are <b>refused rather than
 * ignored</b>: each of them changes the answer, and a pipeline that quietly dropped
 * one would emit a plausible coordinate that is wrong by the size of the omitted
 * step.
 *
 * <p>The shape has since been grown into, which is the evidence that it was the
 * right shape: {@code cart}, {@code hgridshift}, {@code deformation} and
 * {@code tinshift} were each added as a class implementing
 * {@link org.locationtech.proj4j.pipeline.PipelineOperator} over a
 * {@code double[4]}, with no change to that interface, to
 * {@link org.locationtech.proj4j.pipeline.Pipeline} or to
 * {@link org.locationtech.proj4j.pipeline.PipelineStep}. The
 * {@code +t_epoch}/{@code +t_final} bracket that {@code hgridshift} and
 * {@code vgridshift} share arrived the same way, as
 * {@link org.locationtech.proj4j.pipeline.PipelineOperator} wrapping another —
 * upstream's own {@code TODO} in {@code hgridshift.cpp} asks for exactly that
 * factoring.
 *
 * <p>What remains refused, and why, is enumerated on
 * {@link org.locationtech.proj4j.pipeline.PipelineFactory}, whose
 * {@code handlesOperator} is also the routing contract a caller uses to decide that
 * a bare {@code +proj=axisswap order=2,1} belongs here rather than on the
 * {@code CRSFactory} path.
 *
 * <h2>Reading the source</h2>
 *
 * <p>Every non-obvious line cites PROJ 9.8.1 by file and line. The upstream
 * behaviours most easily "fixed" by accident, and which must not be:
 *
 * <ul>
 * <li>a pipeline's global tokens are <b>appended</b> to each step's, not prepended,
 *     so a global shadows nothing and is shadowed by everything — which is how
 *     {@code +proj=pipeline +towgs84=0,0,0} switches off a double datum shift;</li>
 * <li>{@code grad} normalises to neither {@code "Radian"} nor {@code "Degree"}, so a
 *     pipeline ending in {@code +xy_out=grad} reports {@code WHATEVER} units and its
 *     residuals are measured with a Euclidean metric on grad values against a
 *     tolerance written in metres;</li>
 * <li>a pipeline with no global {@code +ellps} defaults to <b>GRS80</b> where a bare
 *     operation defaults to <b>WGS84</b>.</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 *
 * <p>{@link org.locationtech.proj4j.pipeline.ProjParams} and the two numeric
 * helpers are immutable. {@link org.locationtech.proj4j.pipeline.Pipeline} and its
 * operators are <b>not</b> thread-safe, because they wrap mutable proj4j
 * {@code Projection} instances. Build one pipeline per thread.
 *
 * @since 1.5
 */
package org.locationtech.proj4j.pipeline;
