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
package org.locationtech.proj4j.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;

import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CrsCreationException;
import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.Proj4jException;
import org.locationtech.proj4j.Registry;
import org.locationtech.proj4j.UnknownAuthorityCodeException;
import org.locationtech.proj4j.UnsupportedParameterException;
import org.locationtech.proj4j.io.projjson.ProjJsonReader;
import org.locationtech.proj4j.io.wkt.AxisOrderPolicy;
import org.locationtech.proj4j.io.wkt.CrsDefinition;
import org.locationtech.proj4j.io.wkt.CrsDefinitions;
import org.locationtech.proj4j.io.wkt.WktDialect;
import org.locationtech.proj4j.io.wkt.WktParseException;
import org.locationtech.proj4j.io.wkt.WktReader;
import org.locationtech.proj4j.parser.Proj4Parser;
import org.locationtech.proj4j.parser.Proj4Parser.ParseMode;
import org.locationtech.proj4j.resource.ResourceResolver;
import org.locationtech.proj4j.resource.ResourceResolvers;

/**
 * The entry point: create a {@link Crs}, create a {@link CrsOperation}, or ask this library what it
 * can and cannot do.
 *
 * <p>Everything here is static and stateless (the one exception being the process default context,
 * which can be set once and only before anything is built). No instance to construct, nothing to
 * close, no dependency to add: {@code org.locationtech.proj4j.api} lives in the {@code proj4j}
 * artifact and that artifact has <b>zero runtime dependencies</b>.
 *
 * <pre>{@code
 * // Planning -- throws here if the answer would not be trustworthy.
 * CrsOperation op = Proj.createCrsToCrs("EPSG:4326", "EPSG:32633");
 *
 * // Per row -- thread-safe, allocation-free.
 * op.bulk().transform2D(xy, 0, numVertices, 2, status);
 * }</pre>
 *
 * <h2>Why this package is inside {@code proj4j} and not beside it</h2>
 *
 * <p>Because a consumer must be able to delete Apache SIS <em>and</em> the
 * {@code catch (LinkageError)} they wrapped it in. Two incompatible copies of
 * {@code org.opengis.util.CodeList} on one classpath &mdash; {@code geoapi-3.0.2} has
 * {@code names()}, {@code gt-opengis-29.6} does not &mdash; make SIS's WKT parser throw
 * {@code NoSuchMethodError}, which is an {@code Error}, not an {@code Exception}: it passes locally
 * and kills Spark executors. The only cure is to stop needing SIS, which means WKT2, WKT1 in both
 * dialects, and PROJJSON have to be readable and writable <em>here</em>. They are, and core contains
 * zero references to {@code org.opengis.*}, enforced by a test that scans the compiled classes.
 *
 * <h2>Introspection</h2>
 *
 * <p>{@link #version()}, {@link #databaseVersion()}, {@link #availableGrids()},
 * {@link #describe()}; plus, per CRS, {@link Crs#isGeocentric()}, {@link Crs#isAngular()},
 * {@link Crs#axisOrder()} and {@link Crs#describe()}. Both of these refuse to <em>guess</em>, on
 * purpose:
 *
 * <ul>
 * <li>{@link #databaseVersion()} answers only when a database is actually configured, and is
 * {@link Optional#empty()} otherwise &mdash; it never guesses. <b>An authority database now
 * exists</b> ({@code proj4j-db}, PROJ 9.8.1's {@code proj.db} transcoded to a deterministic
 * read-only index), and it is <b>opt-in</b>: attach one with
 * {@link ProjContext.Builder#database(org.locationtech.proj4j.spi.ProjDatabase)} and this reports
 * its {@code metadata} table verbatim. Without one, authority codes resolve against a PROJ.4
 * {@code +init=} dictionary generated from EPSG <b>v9.2-era</b> data, and those files carry no
 * version stamp at all, so any string here would be a guess or a lie. {@link DatabaseInfo} reports
 * the vintage gap in prose either way, plus a classpath probe that <em>is</em> a checkable fact.</li>
 * <li>{@link #availableGrids()} distinguishes <b>reachable</b> from <b>declared</b> and states,
 * per resolver, whether it can even be enumerated &mdash; so an empty list is never mistaken for
 * "nothing installed". See {@link GridInfo}.</li>
 * </ul>
 *
 * <p><b>Whether a database is attached changes answers, not just detail.</b> The clearest case is
 * {@code EPSG:4267}&nbsp;&rarr;&nbsp;{@code EPSG:4269} (NAD27&nbsp;&rarr;&nbsp;NAD83): with no
 * database there is nothing to select from, so {@link BallparkPolicy#REJECT} throws. With one, the
 * authority offers <b>nine</b> published transformations from 0.15&nbsp;m to 2.0&nbsp;m and
 * <em>none</em> of them is ballpark, so the honest answer is a real operation &mdash; or
 * {@code BEST_OPERATION_UNAVAILABLE} naming the grid files you are missing. Neither answer is
 * wrong; they are answers to different questions, and {@link CrsOperation#describe()} says which
 * one you asked.
 *
 * <h2>The 1.x API is frozen, not re-routed</h2>
 *
 * <p>{@link org.locationtech.proj4j.CRSFactory} and
 * {@link org.locationtech.proj4j.CoordinateTransformFactory} behave exactly as they did, and
 * nothing on this page changes them. That is not an oversight: re-routing them would make
 * {@code EPSG:4267 -> EPSG:4269} start <em>throwing</em> for GeoTools, GeoServer and geomesa on
 * code that has worked for fifteen years. {@link LegacyAdapters} is the opt-in bridge for callers
 * who want the new behaviour behind the old interface.
 *
 * @see Crs
 * @see CrsOperation
 * @see ProjContext
 * @see LegacyAdapters
 * @since 1.5.0
 */
public final class Proj {

    /**
     * The PROJ release whose algorithms, parameter semantics and error taxonomy this library
     * targets. A constant, not a probe: it describes this source tree, and there is no PROJ
     * installation involved at runtime.
     */
    private static final String PROJ_SEMANTICS_VERSION = "9.8.1";

    /** The registry is stateless after construction and its lookups are read-only. */
    private static final Registry REGISTRY = new Registry();

    private static volatile ProjContext defaultContext = ProjContext.DEFAULT;

    /**
     * Set the first time anything is built. {@link #setDefaultContext(ProjContext)} refuses once
     * this is true, so one library on the classpath cannot change the semantics under another that
     * has already resolved its CRSs.
     */
    private static final AtomicBoolean somethingBuilt = new AtomicBoolean(false);

    private Proj() {
    }

    // ------------------------------------------------------------------------------ the context

    /**
     * The context used by every method here that does not take one explicitly.
     *
     * @return the process default context; never null
     */
    public static ProjContext defaultContext() {
        return defaultContext;
    }

    /**
     * Replaces the process default context.
     *
     * <p><b>Refuses once any {@link Crs} or {@link CrsOperation} has been created.</b> That is the
     * whole point of the method's existing at all: it lets an application state its policy once at
     * start-up, and prevents a library buried three levels down the dependency tree from
     * transposing every coordinate in the process after the fact. There is no way to force it and
     * no way to read the policy from the environment.
     *
     * <p>Prefer passing a {@link ProjContext} per call. A test should never call this method: a
     * conformance suite that silently changed the process default would be validating semantics no
     * shipped default produces.
     *
     * @param context the new default; null restores {@link ProjContext#DEFAULT}
     * @throws IllegalStateException if anything has already been built under the current default
     */
    public static void setDefaultContext(ProjContext context) {
        if (somethingBuilt.get()) {
            throw new IllegalStateException("the default ProjContext cannot be replaced: "
                    + "a Crs or CrsOperation has already been created under the current default ("
                    + defaultContext + "), and changing it now would silently change the meaning "
                    + "of objects that already exist. Pass a ProjContext per call instead.");
        }
        defaultContext = context == null ? ProjContext.DEFAULT : context;
    }

    /**
     * Whether anything has been built yet, i.e. whether {@link #setDefaultContext(ProjContext)}
     * would now refuse.
     *
     * @return true once a {@link Crs} or {@link CrsOperation} exists
     */
    public static boolean isDefaultContextLocked() {
        return somethingBuilt.get();
    }

    // ------------------------------------------------------------------------- creating CRSs

    /**
     * Creates a CRS from any of the four notations this library reads, under
     * {@link #defaultContext()}.
     *
     * @param definition an {@code authority:code} name, a PROJ.4 parameter string, WKT1 (OGC or
     *                   ESRI), WKT2 (2015 or 2019), or PROJJSON
     * @return the CRS; never null
     * @throws CrsCreationException with a {@link Proj4jException#cause()} explaining which of
     *                              unknown, malformed, unsupported or unavailable applies
     */
    public static Crs createCrs(String definition) {
        return createCrs(definition, defaultContext);
    }

    /**
     * Creates a CRS from any of the four notations this library reads, under an explicit context.
     *
     * <p>The notation is detected, not declared: a leading open brace is PROJJSON, a recognised WKT
     * keyword followed by a bracket is WKT (dialect auto-detected by PROJ's own rule), a string
     * containing {@code +} or {@code =} is a PROJ.4 parameter string, and anything else is an
     * {@code authority:code} name.
     *
     * <p>For a PROJ.4 parameter string &mdash; and only for one &mdash; the context's
     * {@link ProjContext#parseMode()} applies. Under the default {@link ParseMode#PROJ_COMPATIBLE}
     * an unrecognised {@code +key} is retained and ignored and an unknown {@code +units} falls back
     * to metres, exactly as PROJ does; under {@link ParseMode#STRICT} both are refused, the key by
     * name. Read {@link ProjContext#parseMode()} for what {@code STRICT} does not cover.
     *
     * @param definition the CRS definition
     * @param context    the policies to build under; null means {@link #defaultContext()}
     * @return the CRS; never null
     * @throws CrsCreationException if the definition cannot be turned into a usable CRS
     */
    public static Crs createCrs(String definition, ProjContext context) {
        ProjContext ctx = context == null ? defaultContext : context;
        if (definition == null) {
            throw new CrsCreationException(ErrorCause.API_MISUSE, "the CRS definition is null");
        }
        String text = definition.trim();
        if (text.isEmpty()) {
            throw new CrsCreationException(ErrorCause.INVALID_CRS_SYNTAX,
                    "the CRS definition is empty");
        }
        somethingBuilt.set(true);

        if (text.charAt(0) == '{') {
            return fromProjJson(definition, text, ctx);
        }
        if (isWkt(text)) {
            return fromWkt(definition, text, ctx);
        }
        if (text.charAt(0) == '+' || text.indexOf('=') >= 0) {
            return fromProjString(definition, text, ctx);
        }
        if (CRSFactory.isCompoundName(text)) {
            throw new CrsCreationException(ErrorCause.CRS_TYPE_NOT_SUPPORTED, text
                    + " is a compound (horizontal + vertical) CRS. The new facade's Crs is "
                    + "two-dimensional, and answering a 3D question with a 2D CRS by dropping the "
                    + "vertical half would be worse than refusing. Use "
                    + "CRSFactory.createCompound(\"" + text + "\").");
        }
        return fromName(definition, text, ctx);
    }

    /**
     * Creates a CRS from WKT, in any of the four dialects, under {@link #defaultContext()}.
     *
     * <p>Equivalent to {@link #createCrs(String)} for WKT input; provided so that a caller handling
     * untrusted per-row input can state what it expects and get
     * {@link ErrorCause#INVALID_CRS_SYNTAX} rather than a misdetection.
     *
     * @param wkt the WKT text
     * @return the CRS; never null
     * @throws CrsCreationException if the text is not WKT this library can turn into a CRS
     */
    public static Crs createCrsFromWkt(String wkt) {
        return createCrsFromWkt(wkt, defaultContext);
    }

    /**
     * Creates a CRS from WKT under an explicit context.
     *
     * @param wkt     the WKT text
     * @param context the policies to build under; null means {@link #defaultContext()}
     * @return the CRS; never null
     * @throws CrsCreationException if the text is not WKT this library can turn into a CRS
     */
    public static Crs createCrsFromWkt(String wkt, ProjContext context) {
        ProjContext ctx = context == null ? defaultContext : context;
        if (wkt == null) {
            throw new CrsCreationException(ErrorCause.API_MISUSE, "the WKT is null");
        }
        somethingBuilt.set(true);
        return fromWkt(wkt, wkt.trim(), ctx);
    }

    /**
     * Creates a CRS from a PROJJSON document under {@link #defaultContext()}.
     *
     * @param json the PROJJSON text
     * @return the CRS; never null
     * @throws CrsCreationException if the text is not PROJJSON this library can turn into a CRS
     */
    public static Crs createCrsFromProjJson(String json) {
        return createCrsFromProjJson(json, defaultContext);
    }

    /**
     * Creates a CRS from a PROJJSON document under an explicit context.
     *
     * @param json    the PROJJSON text
     * @param context the policies to build under; null means {@link #defaultContext()}
     * @return the CRS; never null
     * @throws CrsCreationException if the text is not PROJJSON this library can turn into a CRS
     */
    public static Crs createCrsFromProjJson(String json, ProjContext context) {
        ProjContext ctx = context == null ? defaultContext : context;
        if (json == null) {
            throw new CrsCreationException(ErrorCause.API_MISUSE, "the PROJJSON is null");
        }
        somethingBuilt.set(true);
        return fromProjJson(json, json.trim(), ctx);
    }

    // ------------------------------------------------------------------- creating operations

    /**
     * Creates an operation between two CRS definitions, under {@link #defaultContext()}.
     *
     * <p><b>Throws rather than returning something untrustworthy.</b> Under the default
     * {@link BallparkPolicy#REJECT} a pair whose only available datum change would not actually be
     * applied raises {@link CrsCreationException} with {@link ErrorCause#BALLPARK_REJECTED} here,
     * on this line &mdash; not on row 4,000,000, and not as a plausible coordinate that is out by
     * the size of the shift.
     *
     * @param source the source CRS definition, in any notation {@link #createCrs(String)} accepts
     * @param target the target CRS definition
     * @return the operation; never null
     * @throws CrsCreationException if either CRS cannot be built, or if no operation between them
     *                              is one this context will vouch for
     */
    public static CrsOperation createCrsToCrs(String source, String target) {
        return createCrsToCrs(source, target, defaultContext);
    }

    /**
     * Creates an operation between two CRS definitions, under an explicit context.
     *
     * @param source  the source CRS definition
     * @param target  the target CRS definition
     * @param context the policies to build under; null means {@link #defaultContext()}
     * @return the operation; never null
     * @throws CrsCreationException if either CRS cannot be built, or if no operation between them
     *                              is one this context will vouch for
     */
    public static CrsOperation createCrsToCrs(String source, String target, ProjContext context) {
        ProjContext ctx = context == null ? defaultContext : context;
        return createCrsToCrs(createCrs(source, ctx), createCrs(target, ctx), ctx);
    }

    /**
     * Creates an operation between two CRSs already built.
     *
     * @param source the source CRS
     * @param target the target CRS
     * @return the operation; never null
     * @throws CrsCreationException if no operation between them is one the source CRS's context
     *                              will vouch for
     */
    public static CrsOperation createCrsToCrs(Crs source, Crs target) {
        return createCrsToCrs(source, target, source == null ? defaultContext : source.context());
    }

    /**
     * Creates an operation between two CRSs already built, under an explicit context.
     *
     * @param source  the source CRS
     * @param target  the target CRS
     * @param context the policies to build under; null means {@link #defaultContext()}
     * @return the operation; never null
     * @throws CrsCreationException if no operation between them is one this context will vouch for
     */
    public static CrsOperation createCrsToCrs(Crs source, Crs target, ProjContext context) {
        ProjContext ctx = context == null ? defaultContext : context;
        if (source == null || target == null) {
            throw new CrsCreationException(ErrorCause.API_MISUSE,
                    "createCrsToCrs needs two non-null CRSs");
        }
        somethingBuilt.set(true);
        return CrsOperation.create(source.withContext(ctx), target.withContext(ctx), ctx);
    }

    // --------------------------------------------------------------- inspecting the candidates

    /**
     * Every coordinate operation the authority publishes between two CRSs, ranked best first, with the
     * rejected ones included and the reason each was rejected.
     *
     * <p>This is the method that makes the historic defect impossible to repeat. {@code EPSG:4267} to
     * {@code EPSG:4269} has <b>nine</b> published grid transformations with accuracies from
     * 0.15&nbsp;m to 2.0&nbsp;m, and <b>not one of them is ballpark</b>. Proj4J's old answer &mdash;
     * the input unchanged, 95.573&nbsp;m out at San Francisco, finite, plausible, unwarned &mdash; was
     * never "the authority offers nothing". It was that the offer could not be seen. Here it is.
     *
     * <p>Returns an empty list when {@link ProjContext#database()} is null, because the legacy datum
     * model synthesises exactly one operation per CRS pair and there is nothing to enumerate. It is
     * empty rather than a one-element list containing a fabricated candidate.
     *
     * <h4>The ranking, in full</h4>
     *
     * <p>The database returns rows in {@code (kind, authority, code)} order and <b>never</b> by
     * accuracy: it has no policy. This is the policy, and it is a <b>total order</b>, so the result
     * cannot depend on which index a row was found through or on classpath ordering. Applied in
     * sequence, first difference wins:
     *
     * <ol>
     * <li><b>Not deprecated</b> before deprecated. The authority has said not to use it.</li>
     * <li><b>Not superseded</b> before superseded &mdash; but only by a replacement that connects the
     *     <em>same</em> CRS pair and is itself a candidate here. A replacement for a different pair is
     *     not a substitute, and treating it as one silently discards a usable operation.</li>
     * <li><b>Executable method</b> before one this library cannot run. An operation whose method maps
     *     to an operator Proj4J does not implement is not a candidate in any useful sense, whereas a
     *     missing grid is a deployment fact a caller can fix by adding a file. This tier is also what
     *     makes the {@code EPSG:4267} answer stable: {@code EPSG:8555} is tied with {@code EPSG:1241}
     *     at 0.15&nbsp;m and is what PROJ 9.8.1 itself selects, but it is NADCON 5, whose grid feeds
     *     the unified {@code +proj=gridshift} operator that Proj4J does not implement.</li>
     * <li><b>Non-ballpark</b> before ballpark.</li>
     * <li><b>All grids reachable</b> before any grid missing.</li>
     * <li><b>Smaller accuracy</b> first. An <em>absent</em> accuracy sorts after every present one and
     *     is never treated as zero: an invented accuracy is precisely what would let a ballpark
     *     candidate win.</li>
     * <li><b>Smaller area of use</b> first, so a continental grid does not outrank a national one.
     *     Antimeridian wrap is handled rather than normalised, and an extent with no bounding box
     *     sorts last rather than being read as the whole world.</li>
     * <li><b>Authority reference</b> {@code (kind, authority, code)}, then forward before inverted.
     *     Nothing is left tied.</li>
     * </ol>
     *
     * <p>Both directions are enumerated, from two calls to
     * {@link org.locationtech.proj4j.spi.ProjDatabase#operationsBetween} with the arguments swapped.
     * The authority publishes an operation one way round only, and
     * {@link CrsOperationCandidate#isInverted()} keeps that fact explicit &mdash; losing it is how a
     * shift gets applied with the wrong sign.
     *
     * @param source the source CRS
     * @param target the target CRS
     * @return an unmodifiable list in ranking order; never null, and empty without a database
     * @see CrsOperationCandidate
     * @see CrsOperation#selectedOperation()
     */
    public static List<CrsOperationCandidate> candidateOperations(Crs source, Crs target) {
        ProjContext ctx = source == null ? defaultContext : source.context();
        return candidateOperations(source, target, ctx);
    }

    /**
     * Every coordinate operation the authority publishes between two CRSs, under an explicit context.
     *
     * @param source  the source CRS
     * @param target  the target CRS
     * @param context the context whose database and policies apply; null means
     *                {@link #defaultContext()}
     * @return an unmodifiable list in ranking order; never null
     * @see #candidateOperations(Crs, Crs)
     */
    public static List<CrsOperationCandidate> candidateOperations(Crs source, Crs target,
                                                                 ProjContext context) {
        ProjContext ctx = context == null ? defaultContext : context;
        if (source == null || target == null) {
            throw new CrsCreationException(ErrorCause.API_MISUSE,
                    "candidateOperations needs two non-null CRSs");
        }
        if (!ctx.hasDatabase()) {
            return Collections.emptyList();
        }
        somethingBuilt.set(true);
        return OperationSelector.select(ctx.database(), source.withContext(ctx),
                target.withContext(ctx), ctx).candidates();
    }

    /**
     * Every coordinate operation the authority publishes between two CRS definitions.
     *
     * @param source  the source CRS definition, in any notation {@link #createCrs(String)} accepts
     * @param target  the target CRS definition
     * @param context the context whose database and policies apply; null means
     *                {@link #defaultContext()}
     * @return an unmodifiable list in ranking order; never null
     * @see #candidateOperations(Crs, Crs)
     */
    public static List<CrsOperationCandidate> candidateOperations(String source, String target,
                                                                 ProjContext context) {
        ProjContext ctx = context == null ? defaultContext : context;
        return candidateOperations(createCrs(source, ctx), createCrs(target, ctx), ctx);
    }

    // -------------------------------------------------------------------------- introspection

    /**
     * This library's version and the PROJ release it targets, as one line for a log.
     *
     * <p>The library version is read from <b>this class's own jar manifest</b>, which is the only
     * place it exists at runtime, and never from a hard-coded constant that would drift from the
     * build. Running from an exploded class directory &mdash; an IDE, a surefire run &mdash; there
     * is no manifest and this reports {@code "unknown"}, which is the true answer.
     *
     * <p>The trailing note states whether the default context has an authority database, because that
     * is the single fact that most changes what this library will and will not answer.
     *
     * @return for example
     *         {@code "proj4j 2.0.0 (PROJ 9.8.1 algorithms, EPSG v12.029)"}; never null
     */
    public static String version() {
        String v = manifestVersion();
        Optional<String> db = databaseVersion();
        return "proj4j " + (v == null ? "unknown (no jar manifest on this classpath)" : v)
                + " (PROJ " + PROJ_SEMANTICS_VERSION + " algorithms, "
                + (db.isPresent() ? db.get() : "no authority database configured") + ")";
    }

    /**
     * The version stamped into the jar this class was loaded from, or null.
     *
     * <p>Two sources, in order: {@code Implementation-Version} via {@link Package}, then the OSGi
     * {@code Bundle-Version}, which is what this artifact's bundle plugin actually writes.
     *
     * <p>The manifest is read from the jar <em>this class came from</em>, located through
     * {@code getResource} on its own class file, not from the first {@code META-INF/MANIFEST.MF} on
     * the classpath &mdash; which would report some other library's version. The URL is only ever
     * opened when its protocol is {@code jar}, so this performs no network I/O; core contains no
     * network code at all and this is not an exception to that.
     */
    private static String manifestVersion() {
        Package pkg = Proj.class.getPackage();
        if (pkg != null && pkg.getImplementationVersion() != null) {
            return pkg.getImplementationVersion();
        }
        try {
            java.net.URL self = Proj.class.getResource("Proj.class");
            if (self == null || !"jar".equals(self.getProtocol())) {
                return null;
            }
            String s = self.toString();
            int bang = s.indexOf("!/");
            if (bang < 0) {
                return null;
            }
            java.io.InputStream in =
                    new java.net.URL(s.substring(0, bang + 2) + "META-INF/MANIFEST.MF").openStream();
            try {
                return new java.util.jar.Manifest(in).getMainAttributes()
                        .getValue("Bundle-Version");
            } finally {
                in.close();
            }
        } catch (java.io.IOException unreadable) {
            return null;
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    /**
     * The PROJ release whose algorithms, parameter semantics and error codes this library targets.
     *
     * <p>Not a claim that PROJ is installed &mdash; nothing here shells out or links to it. It is
     * the version this source tree was written and conformance-tested against.
     *
     * @return {@code "9.8.1"}
     */
    public static String projSemanticsVersion() {
        return PROJ_SEMANTICS_VERSION;
    }

    /**
     * The CRS database version, read from the database's own {@code metadata} table.
     *
     * <p>Present when {@link #defaultContext()} carries an authority database:
     * {@code "PROJ 9.8.1, EPSG v12.029"}. A fact about the shipped bytes, not a constant that could
     * drift from them.
     *
     * <p><b>Empty when no database is configured</b>, and that refusal is deliberate. What stands in
     * for one, in the separate {@code proj4j-epsg} artifact, is a PROJ.4 {@code +init=} dictionary
     * generated from <b>EPSG v9.2-era</b> data, while PROJ 9.8.1 ships <b>EPSG v12.029</b>. The
     * dictionary files carry no version stamp anywhere, so:
     *
     * <ul>
     * <li>reporting {@code "EPSG v9.2"} would be an unverifiable claim about bytes with no version
     * in them;</li>
     * <li>reporting {@code "EPSG v12.029"} because that is what PROJ 9.8.1 has would be a lie;</li>
     * <li>reporting nothing, and explaining the gap in prose, is the only honest option.</li>
     * </ul>
     *
     * <p>{@link #databaseInfo()} carries that prose, along with the classpath probe that <em>is</em>
     * a checkable fact.
     *
     * @return the version, or {@link Optional#empty()} when no database is configured
     * @see DatabaseInfo
     * @see ProjContext.Builder#database(org.locationtech.proj4j.spi.ProjDatabase)
     */
    public static Optional<String> databaseVersion() {
        return databaseInfo().version();
    }

    /**
     * What CRS metadata this deployment actually has: the authority database
     * {@link #defaultContext()} carries if any, its {@code metadata} table verbatim, whether the
     * legacy dictionary is on the classpath, and the vintage gap between them.
     *
     * @return the info; never null
     */
    public static DatabaseInfo databaseInfo() {
        return databaseInfo(defaultContext);
    }

    /**
     * What CRS metadata a given context has.
     *
     * @param context the context; null means {@link #defaultContext()}
     * @return the info; never null
     */
    public static DatabaseInfo databaseInfo(ProjContext context) {
        ProjContext ctx = context == null ? defaultContext : context;
        return DatabaseInfo.probe(ctx.database());
    }

    /**
     * Every grid file that is <b>reachable</b> right now: the union of what the enumerable
     * resolvers list and what a direct probe finds for each grid any shipped definition declares.
     *
     * <p><b>Reachable is not the same as declared, and this method reports the difference.</b>
     * <p>Two things make a naive answer here dangerous. First, <b>classpath resources are not
     * enumerable in general</b>: a resolver without a build-time {@code INDEX} manifest cannot list
     * anything, so a short list may mean "cannot enumerate" rather than "nothing installed". That
     * is why {@link #describeResolution()} states enumerability per resolver, and why
     * {@link #grid(String)} can answer for a grid this method cannot list. Second, a grid that is
     * <em>declared and unreachable</em> is the dangerous case &mdash; it is what turns
     * {@code EPSG:4267} into a 95&nbsp;m error with no warning &mdash; so it is reported by
     * {@link #declaredGrids()}, with {@link GridInfo#isAvailable()} {@code false} and a reason, and
     * never simply omitted.
     *
     * @return an unmodifiable, name-sorted list of reachable grids; never null
     * @see #declaredGrids()
     * @see #describeResolution()
     */
    public static List<GridInfo> availableGrids() {
        TreeMap<String, GridInfo> byName = new TreeMap<String, GridInfo>();
        for (ResourceResolver r : ResourceResolvers.resolver().delegates()) {
            if (!r.isEnumerable()) {
                continue;
            }
            for (String name : r.listAvailable()) {
                if (!byName.containsKey(name)) {
                    GridInfo info = GridInfo.probe(name, false, null);
                    if (info.isAvailable()) {
                        byName.put(name, info);
                    }
                }
            }
        }
        for (GridInfo declared : declaredGrids()) {
            if (declared.isAvailable() && !byName.containsKey(declared.name())) {
                byName.put(declared.name(), declared);
            }
        }
        return Collections.unmodifiableList(new ArrayList<GridInfo>(byName.values()));
    }

    /**
     * Every grid file that some definition on this classpath asks for, whether or not it can be
     * found &mdash; each one probed, so {@link GridInfo#isAvailable()} is measured rather than
     * assumed.
     *
     * <p>These are the grids PROJ's built-in datum table declares
     * ({@code 9.8.1:src/datums.cpp}); every token in it is {@code @}-optional, which is exactly why
     * an unreachable one has historically gone unremarked.
     *
     * @return an unmodifiable, name-sorted list; never null
     */
    public static List<GridInfo> declaredGrids() {
        TreeMap<String, GridInfo> byName = new TreeMap<String, GridInfo>();
        for (Map.Entry<String, String> e : Crs.datumGridDeclarations().entrySet()) {
            for (String token : e.getValue().split(",")) {
                boolean optional = token.startsWith("@");
                String name = optional ? token.substring(1) : token;
                if (!byName.containsKey(name)) {
                    byName.put(name, GridInfo.probe(name, optional, "+datum=" + e.getKey()));
                }
            }
        }
        return Collections.unmodifiableList(new ArrayList<GridInfo>(byName.values()));
    }

    /**
     * Probes the resolver chain for one grid by name.
     *
     * <p>Works for a non-enumerable resolver, so this answers for grids {@link #availableGrids()}
     * cannot list. The file is <b>not read</b>, so {@link GridInfo#format()} is empty: a format
     * inferred from a file name would be a guess.
     *
     * @param name the grid file name, with or without a leading {@code @}
     * @return the grid info, or empty if {@code name} is null or blank
     */
    public static Optional<GridInfo> grid(String name) {
        if (name == null || name.trim().isEmpty()) {
            return Optional.empty();
        }
        String n = name.trim();
        boolean optional = n.startsWith("@");
        return Optional.of(GridInfo.probe(optional ? n.substring(1) : n, optional, null));
    }

    /**
     * Whether any network-backed resolver is both present and enabled.
     *
     * <p><b>Always false in a stock deployment</b>, and false as a matter of what is on the
     * classpath rather than of a flag: proj4j core ships no network code at all. Enabling it takes
     * two independent steps &mdash; adding the optional artifact <em>and</em> enabling it in code
     * &mdash; which is auditable by {@code mvn dependency:tree} and by {@code jar tf}, unlike a
     * default-off flag that three separate channels can turn on. PROJ has exactly that problem: one
     * of its three channels is a {@code proj.ini} found anywhere on a twelve-step search path.
     *
     * @return true only if a resolver reporting {@code isNetworkBacked()} was registered and
     *         allowed
     */
    public static boolean isNetworkEnabled() {
        return ResourceResolvers.isNetworkEnabled();
    }

    /**
     * Exactly how data is being located: every resolver in chain order, whether each can be
     * enumerated, and the three ambient inputs that are <em>not</em> consulted.
     *
     * <p>Log this once per JVM. Between this and {@link ProjContext#describe()} it states
     * everything that could make two executors disagree about a coordinate.
     *
     * @return the description, newline-terminated; never null
     */
    public static String describeResolution() {
        return ResourceResolvers.describeResolution();
    }

    /**
     * Every {@code +proj=} name this library will accept.
     *
     * @return an unmodifiable sorted set; never null
     */
    public static SortedSet<String> supportedProjections() {
        TreeSet<String> names = new TreeSet<String>();
        for (ProjectionInfo p : projections()) {
            if (p.isImplemented()) {
                names.add(p.name());
            }
        }
        return Collections.unmodifiableSortedSet(names);
    }

    /**
     * Every {@code +proj=} name the registry knows, with its human-readable description and whether
     * it can actually be instantiated.
     *
     * <p>Includes the handful that are registered but not implemented, because "Proj4J has not
     * implemented this" and "you typed it wrong" are different facts that lead to different
     * actions.
     *
     * @return an unmodifiable, name-sorted list; never null
     */
    public static List<ProjectionInfo> projections() {
        Map<String, String> descriptions = REGISTRY.getProjectionDescriptions();
        List<ProjectionInfo> out = new ArrayList<ProjectionInfo>(descriptions.size());
        for (Map.Entry<String, String> e : descriptions.entrySet()) {
            boolean implemented;
            try {
                implemented = REGISTRY.getProjection(e.getKey()) != null;
            } catch (UnsupportedParameterException notImplemented) {
                implemented = false;
            }
            out.add(new ProjectionInfo(e.getKey(), e.getValue(), implemented));
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * The human-readable description registered for a {@code +proj=} name &mdash;
     * {@code "Lambert Conformal Conic"} for {@code "lcc"}.
     *
     * @param projName the {@code +proj=} value
     * @return the description, or empty if the name is not registered
     */
    public static Optional<String> projectionDescription(String projName) {
        return Optional.ofNullable(REGISTRY.getProjectionDescription(projName));
    }

    /**
     * Everything this deployment can and cannot do, in one multi-line block: version, the metadata
     * situation including the vintage gap, the resolution chain, the grid inventory split into
     * reachable and declared-but-unreachable, and the default context.
     *
     * <p>The single call to log at start-up. If a coordinate later turns out to be wrong, this is
     * the text that says why it could have been.
     *
     * @return the description, newline-terminated; never null
     */
    public static String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(version()).append('\n');
        sb.append("PROJ semantics target: ").append(PROJ_SEMANTICS_VERSION).append('\n');
        sb.append('\n').append(databaseInfo().describe());
        sb.append('\n').append(describeResolution());

        List<GridInfo> available = availableGrids();
        sb.append('\n').append("grid inventory:\n");
        sb.append("  reachable now = ").append(available.size()).append('\n');
        for (int i = 0; i < available.size(); i++) {
            sb.append("      ").append(available.get(i).describe()).append('\n');
        }
        List<GridInfo> declared = declaredGrids();
        int unreachable = 0;
        for (int i = 0; i < declared.size(); i++) {
            if (!declared.get(i).isAvailable()) {
                unreachable++;
            }
        }
        sb.append("  declared by the built-in datum table = ").append(declared.size())
                .append(", of which UNREACHABLE = ").append(unreachable).append('\n');
        for (int i = 0; i < declared.size(); i++) {
            if (!declared.get(i).isAvailable()) {
                sb.append("      ").append(declared.get(i).describe()).append('\n');
            }
        }
        if (unreachable > 0) {
            sb.append("  NOTE: every token in the built-in datum table is @-optional, so PROJ and "
                    + "proj4j 1.4.3 skip an unreachable grid silently and apply no shift. The "
                    + "coordinate is then wrong by the size of the shift and entirely plausible "
                    + "(95.573 m at San Francisco, measured). The new facade reports it; see "
                    + "GridPolicy and BallparkPolicy.\n");
            sb.append("  NOTE: of the seven US grids, only conus and alaska exist upstream in the "
                    + "CTABLE V2 form this library reads (9.8.1:data/tests/). hawaii, prvi, "
                    + "stgeorge, stlrnc and stpaul are GeoTIFF-only in PROJ-data and arrive with "
                    + "the GeoTIFF reader, not before it.\n");
        }
        sb.append('\n').append("projections: ").append(supportedProjections().size())
                .append(" usable of ").append(projections().size()).append(" registered\n");
        sb.append('\n').append(defaultContext.describe());
        return sb.toString();
    }

    // --------------------------------------------------------------------------------- internals

    private static boolean isWkt(String text) {
        try {
            WktDialect.guess(text);
            return true;
        } catch (WktParseException notWkt) {
            return false;
        }
    }

    /**
     * Resolves an {@code authority:code} name.
     *
     * <p>The legacy PROJ.4 dictionary is tried first and stays authoritative, so a code it knows
     * resolves to byte-identical parameters whether or not a database is configured &mdash; adding
     * {@code proj4j-db} must not move a coordinate that already worked. The database is consulted only
     * for a code the dictionary cannot produce, which is the useful half of the vintage gap: the
     * dictionary is EPSG v9.2-era and the shipped database is v12.029.
     */
    private static Crs fromName(String definition, String text, ProjContext ctx) {
        try {
            CoordinateReferenceSystem crs = new CRSFactory().createFromName(text);
            return applyAxisPolicy(definition, Crs.Source.AUTHORITY_CODE, ctx, crs, null, false);
        } catch (IllegalStateException noDictionary) {
            Crs fromDb = DatabaseCrsFactory.create(definition, text, ctx);
            if (fromDb != null) {
                return fromDb;
            }
            throw new CrsCreationException(ErrorCause.DATABASE_UNAVAILABLE,
                    "cannot resolve \"" + text + "\": the legacy PROJ.4 dictionary is not on the "
                            + "classpath" + databaseNote(ctx) + ". Add the proj4j-epsg artifact, or "
                            + "supply the CRS as a PROJ.4 parameter string, WKT or PROJJSON. "
                            + databaseInfo().vintageNote(), noDictionary);
        } catch (UnknownAuthorityCodeException notInDictionary) {
            // Deliberately only this exception. An UnsupportedParameterException means the code WAS
            // found and names a projection Proj4J has not implemented, which is a different fact and
            // keeps its own ErrorCause. And when there is no database the original exception is
            // rethrown unchanged, so an unknown code stays UNKNOWN_CRS exactly as before.
            Crs fromDb = DatabaseCrsFactory.create(definition, text, ctx);
            if (fromDb != null) {
                return fromDb;
            }
            throw notInDictionary;
        }
    }

    private static String databaseNote(ProjContext ctx) {
        return ctx.hasDatabase()
                ? ", and the configured authority database (" + ctx.database().name() + ") either "
                        + "does not have this code or has it as a CRS type that only the legacy "
                        + "dictionary can build"
                : ", and no authority database is configured (ProjContext.Builder.database(..))";
    }

    /**
     * Parses a PROJ.4 parameter string under this context's {@link ProjContext#parseMode()}.
     *
     * <p>This is the one place in the facade where the caller's own PROJ.4 text is parsed, and
     * therefore the one place {@link ParseMode#STRICT} can act. It goes to {@link Proj4Parser}
     * directly rather than through {@link CRSFactory} because {@code CRSFactory} is frozen at
     * {@link ParseMode#PROJ_COMPATIBLE} &mdash; re-routing it would make working GeoTools and
     * GeoServer code start throwing. Under the default mode this is the same two statements
     * {@code CRSFactory.createFromParameters} runs, against an equivalent stateless
     * {@link Registry}, so nothing moves.
     *
     * <p>{@code applyAxisPolicy} may re-parse the resulting parameter list (to append
     * {@code +axis=neu} or to drop a declared {@code +axis=}) and deliberately does so through
     * {@code CRSFactory} in the default mode: those tokens have already passed this check, and
     * the two the policy adds or removes are in the allow-list, so a second STRICT pass could
     * only ever agree.
     */
    private static Crs fromProjString(String definition, String text, ProjContext ctx) {
        CoordinateReferenceSystem crs =
                new Proj4Parser(REGISTRY, ctx.parseMode()).parse((String) null, text);
        if (crs == null) {
            throw new CrsCreationException(ErrorCause.INVALID_CRS_SYNTAX,
                    "cannot parse as a PROJ.4 parameter string: " + text);
        }
        return applyAxisPolicy(definition, Crs.Source.PROJ_STRING, ctx, crs, null, false);
    }

    private static Crs fromWkt(String definition, String text, ProjContext ctx) {
        CrsDefinition def;
        try {
            def = new WktReader(ctx.axisOrderPolicy()).readDefinition(text);
        } catch (WktParseException e) {
            throw new CrsCreationException(ErrorCause.INVALID_CRS_SYNTAX,
                    "cannot read as WKT: " + e.getMessage(), e);
        }
        CoordinateReferenceSystem crs = CrsDefinitions.toCrs(def, ctx.axisOrderPolicy());
        // PROJ's own axis test is textual -- it asks whether "AXIS[" appears at all -- and it has to
        // be, because the reader synthesises the standard axes when a document omits them. Using the
        // parsed axes here would report every WKT CRS as authoritative, including the ones where
        // this library supplied the answer.
        boolean declared = containsIgnoreCase(text, "AXIS[");
        return new Crs(definition, Crs.Source.WKT, ctx, crs, def, declared,
                axisNote(ctx.axisOrderPolicy(), declared, true, crs));
    }

    private static Crs fromProjJson(String definition, String text, ProjContext ctx) {
        CrsDefinition def;
        try {
            def = new ProjJsonReader(ctx.axisOrderPolicy()).readDefinition(text);
        } catch (RuntimeException e) {
            throw new CrsCreationException(ErrorCause.INVALID_CRS_SYNTAX,
                    "cannot read as PROJJSON: " + e.getMessage(), e);
        }
        CoordinateReferenceSystem crs = CrsDefinitions.toCrs(def, ctx.axisOrderPolicy());
        boolean declared = containsIgnoreCase(text, "\"axis\"");
        return new Crs(definition, Crs.Source.PROJJSON, ctx, crs, def, declared,
                axisNote(ctx.axisOrderPolicy(), declared, true, crs));
    }

    /**
     * Applies {@link AxisOrderPolicy} to a CRS built from a name or a PROJ string, where there are
     * no declared axes to honour and therefore a judgement to be made and disclosed.
     */
    private static Crs applyAxisPolicy(String definition, Crs.Source source, ProjContext ctx,
                                       CoordinateReferenceSystem crs, CrsDefinition def,
                                       boolean declared) {
        String[] params = crs.getParameters();
        boolean explicitAxis = params != null && hasKey(params, "axis");
        AxisOrderPolicy policy = ctx.axisOrderPolicy();
        boolean geographic = crs.getProjection() != null
                && Boolean.TRUE.equals(crs.getProjection().isGeographic());

        if (policy == AxisOrderPolicy.AUTHORITY && !explicitAxis && geographic && params != null) {
            // Every geographic 2D CRS in EPSG uses ellipsoidal CS EPSG:6422, latitude then
            // longitude. That is a rule, not a lookup: without proj.db it cannot be *read*, so it
            // is applied and then reported as inferred by Crs.isAxisOrderAuthoritative().
            CoordinateReferenceSystem flipped = new CRSFactory()
                    .createFromParameters(crs.getName(), append(params, "+axis=neu"));
            return new Crs(definition, source, ctx, flipped, def, false,
                    axisNote(policy, declared, false, flipped));
        }
        if (policy == AxisOrderPolicy.VISUALISATION && explicitAxis && params != null) {
            // Unconditional normalisation, PROJ's proj_normalize_for_visualization: strip the
            // declared order rather than compose with it.
            CoordinateReferenceSystem normalised = new CRSFactory()
                    .createFromParameters(crs.getName(), without(params, "axis"));
            return new Crs(definition, source, ctx, normalised, def, true,
                    axisNote(policy, declared, false, normalised));
        }
        return new Crs(definition, source, ctx, crs, def, explicitAxis,
                axisNote(policy, declared || explicitAxis, false, crs));
    }

    /**
     * One sentence saying which rule produced the axis order, so that an inferred answer is never
     * mistaken for an authority's.
     */
    private static String axisNote(AxisOrderPolicy policy, boolean declared, boolean fromDocument,
                                   CoordinateReferenceSystem crs) {
        boolean geographic = crs.getProjection() != null
                && Boolean.TRUE.equals(crs.getProjection().isGeographic());
        if (policy == AxisOrderPolicy.VISUALISATION) {
            return "AxisOrderPolicy.VISUALISATION: normalised unconditionally to east-north-up "
                    + "(longitude first), overriding anything the definition declared.";
        }
        if (policy == AxisOrderPolicy.LEGACY) {
            if (declared && fromDocument) {
                return "AxisOrderPolicy.LEGACY: the document's AXIS clauses were parsed and "
                        + "retained but deliberately not applied, so this CRS is longitude-first, "
                        + "exactly as in proj4j 1.4.3.";
            }
            if (declared) {
                return "AxisOrderPolicy.LEGACY, with an explicit +axis= in the definition, which is "
                        + "honoured as 1.4.3 honoured it.";
            }
            return "AxisOrderPolicy.LEGACY: authority axis order is ignored, so this CRS is "
                    + "east-north-up -- longitude first -- exactly as in proj4j 1.4.3 and as "
                    + "GeoJSON expects.";
        }
        // AUTHORITY
        if (declared) {
            return fromDocument
                    ? "AxisOrderPolicy.AUTHORITY: taken from the AXIS clauses the document declared."
                    : "AxisOrderPolicy.AUTHORITY, with an explicit +axis= in the definition, which "
                            + "wins over any inference.";
        }
        if (geographic) {
            return "AxisOrderPolicy.AUTHORITY with no CRS database: INFERRED latitude-first from "
                    + "the rule that EPSG gives every geographic 2D CRS the latitude-then-longitude "
                    + "ellipsoidal coordinate system EPSG:6422. This is a rule applied, not a "
                    + "value read from an authority -- isAxisOrderAuthoritative() is false. Supply "
                    + "WKT with AXIS clauses if you need it declared.";
        }
        return "AxisOrderPolicy.AUTHORITY with no CRS database: a projected CRS's authority axis "
                + "order cannot be determined without proj.db, so east-north-up was ASSUMED. That "
                + "is right for the great majority of EPSG projected CRSs and wrong for the "
                + "north-east ones; it is not an authority statement. Supply WKT with AXIS clauses "
                + "if you need it honoured.";
    }

    private static boolean hasKey(String[] params, String key) {
        for (int i = 0; i < params.length; i++) {
            String p = params[i];
            if (p == null) {
                continue;
            }
            int start = p.startsWith("+") ? 1 : 0;
            if (p.regionMatches(start, key, 0, key.length())
                    && p.length() > start + key.length()
                    && p.charAt(start + key.length()) == '=') {
                return true;
            }
        }
        return false;
    }

    private static String[] append(String[] params, String extra) {
        String[] out = new String[params.length + 1];
        System.arraycopy(params, 0, out, 0, params.length);
        out[params.length] = extra;
        return out;
    }

    private static String[] without(String[] params, String key) {
        List<String> kept = new ArrayList<String>(params.length);
        for (int i = 0; i < params.length; i++) {
            String p = params[i];
            if (p == null) {
                continue;
            }
            int start = p.startsWith("+") ? 1 : 0;
            boolean isKey = p.regionMatches(start, key, 0, key.length())
                    && p.length() > start + key.length()
                    && p.charAt(start + key.length()) == '=';
            if (!isKey) {
                kept.add(p);
            }
        }
        return kept.toArray(new String[kept.size()]);
    }

    private static boolean containsIgnoreCase(String s, String needle) {
        int last = s.length() - needle.length();
        for (int i = 0; i <= last; i++) {
            if (s.regionMatches(true, i, needle, 0, needle.length())) {
                return true;
            }
        }
        return false;
    }
}
