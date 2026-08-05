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

import org.locationtech.proj4j.BulkCoordinateTransform;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.CrsCreationException;
import org.locationtech.proj4j.ErrorCause;

/**
 * The opt-in bridge between the frozen 1.x API and this package.
 *
 * <h2>Why a bridge and not a re-route</h2>
 *
 * <p>{@link CoordinateTransformFactory} could have been changed to delegate here. It was not, and it
 * will not be. Doing so would make {@code EPSG:4267 -> EPSG:4269} start <b>throwing</b>
 * {@link ErrorCause#BALLPARK_REJECTED} for GeoTools, GeoServer, geomesa and every other caller, on
 * code that has worked for fifteen years. The new default is the <em>right</em> default for new
 * code and an unacceptable surprise for existing code, and there is no version of "we changed it
 * for your own good" that is acceptable in a library reached transitively.
 *
 * <p>So the legacy classes are bit-for-bit unchanged, and this class is how a caller opts in. One
 * line changes:
 *
 * <pre>{@code
 * // before
 * CoordinateTransformFactory factory = new CoordinateTransformFactory();
 *
 * // after: the strict engine, behind the interface the calling code already implements
 * CoordinateTransformFactory factory = LegacyAdapters.transformFactory(ProjContext.DEFAULT);
 * }</pre>
 *
 * <p>Everything downstream of that line keeps compiling and keeps its types. What changes is that
 * {@link CoordinateTransformFactory#createTransform(CoordinateReferenceSystem,
 * CoordinateReferenceSystem)} now applies {@link BallparkPolicy} and the rest of the context &mdash;
 * so it can throw where it used to return an unshifted coordinate. That is the point of asking for
 * it.
 *
 * <p>There is deliberately <b>no global switch and no system property</b>. Opting in is a visible
 * change in the application's own source, at the site that constructs the factory, so that
 * {@code git blame} can answer why the behaviour changed.
 *
 * @see Proj
 * @see CoordinateTransformFactory
 * @since 1.5.0
 */
public final class LegacyAdapters {

    private LegacyAdapters() {
    }

    /**
     * A {@link CoordinateTransformFactory} whose transforms go through this package's checks.
     *
     * <p>Substitutable for {@code new CoordinateTransformFactory()} at any call site: it
     * <em>is</em> a {@code CoordinateTransformFactory}, so it can be assigned to that type, passed
     * to code expecting it, and stored in a field of that type.
     *
     * <p>What differs from the plain factory, and only this:
     * <ul>
     * <li>{@link BallparkPolicy} is applied, so a pair whose datum change would not actually be
     * performed throws {@link CrsCreationException} from {@code createTransform} instead of
     * returning a transform that silently applies no shift;</li>
     * <li>the context's {@link org.locationtech.proj4j.DomainErrorPolicy} is used;</li>
     * <li>the returned transform is the same engine, so its numbers are identical wherever it does
     * not throw.</li>
     * </ul>
     *
     * <p>{@link org.locationtech.proj4j.io.wkt.AxisOrderPolicy} is <b>not</b> applied here, because
     * this method is handed {@link CoordinateReferenceSystem} objects that are already built: axis
     * order is a property of the CRS, and applying a policy to somebody else's CRS behind their back
     * is precisely the silent transposition this design exists to prevent. Build the CRSs with
     * {@link Proj#createCrs(String, ProjContext)} if you want the policy honoured.
     *
     * @param context the policies to apply; null means {@link Proj#defaultContext()}
     * @return a factory; never null
     */
    public static CoordinateTransformFactory transformFactory(ProjContext context) {
        return new StrictFactory(context == null ? Proj.defaultContext() : context);
    }

    /**
     * Wraps an already-built legacy CRS so that this package's introspection can be used on it.
     *
     * <p>The CRS is wrapped, not re-parsed, so nothing about it changes &mdash; including its axis
     * order, whatever {@code context} says. {@link Crs#definitionText()} is its parameter string
     * where it has one.
     *
     * @param crs     the legacy CRS
     * @param context the context to record against it; null means {@link Proj#defaultContext()}
     * @return the wrapper; never null
     * @throws CrsCreationException with {@link ErrorCause#API_MISUSE} if {@code crs} is null
     */
    public static Crs fromLegacy(CoordinateReferenceSystem crs, ProjContext context) {
        if (crs == null) {
            throw new CrsCreationException(ErrorCause.API_MISUSE,
                    "cannot adapt a null CoordinateReferenceSystem");
        }
        ProjContext ctx = context == null ? Proj.defaultContext() : context;
        String[] params = crs.getParameters();
        String definition = params == null ? crs.getName() : crs.getParameterString().trim();
        return new Crs(definition, Crs.Source.LEGACY_OBJECT, ctx, crs, null,
                params != null && hasAxis(params),
                "adapted from an existing CoordinateReferenceSystem: its axis order is whatever it "
                        + "was built with and was deliberately not re-derived, because silently "
                        + "transposing a CRS somebody else built is the failure this API exists to "
                        + "prevent.");
    }

    private static boolean hasAxis(String[] params) {
        for (int i = 0; i < params.length; i++) {
            String p = params[i];
            if (p == null) {
                continue;
            }
            int start = p.startsWith("+") ? 1 : 0;
            if (p.regionMatches(start, "axis", 0, 4) && p.length() > start + 4
                    && p.charAt(start + 4) == '=') {
                return true;
            }
        }
        return false;
    }

    /**
     * The factory {@link #transformFactory(ProjContext)} returns. Package-private and final: it is
     * an implementation detail, and a caller should hold the {@link CoordinateTransformFactory}
     * type.
     */
    private static final class StrictFactory extends CoordinateTransformFactory {

        private final ProjContext context;

        StrictFactory(ProjContext context) {
            super(context.domainErrorPolicy());
            this.context = context;
        }

        @Override
        public CoordinateTransform createTransform(CoordinateReferenceSystem sourceCRS,
                                                   CoordinateReferenceSystem targetCRS) {
            return operation(sourceCRS, targetCRS).asLegacy();
        }

        @Override
        public BulkCoordinateTransform createBulkTransform(CoordinateReferenceSystem sourceCRS,
                                                           CoordinateReferenceSystem targetCRS) {
            return operation(sourceCRS, targetCRS).bulk();
        }

        private CrsOperation operation(CoordinateReferenceSystem sourceCRS,
                                       CoordinateReferenceSystem targetCRS) {
            return CrsOperation.create(fromLegacy(sourceCRS, context),
                    fromLegacy(targetCRS, context), context);
        }

        @Override
        public String toString() {
            return "LegacyAdapters.transformFactory(" + context + ")";
        }
    }
}
