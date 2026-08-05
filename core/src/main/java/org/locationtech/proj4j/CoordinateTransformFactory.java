/*******************************************************************************
 * Copyright 2009, 2017 Martin Davis
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
package org.locationtech.proj4j;

/**
 * Creates {@link CoordinateTransform}s
 * from source and target {@link CoordinateReferenceSystem}s.
 * <p>
 * This is also where the {@link DomainErrorPolicy} hangs. Every transform this factory creates
 * inherits the factory's policy, so an application that has one factory has one switch:
 *
 * <pre>{@code
 * // fail closed -- the default, and the only mode under which the CrsTransformException
 * // contract holds
 * CoordinateTransformFactory strict = new CoordinateTransformFactory();
 *
 * // documented escape for a caller that was relying on 1.4.3's silence
 * CoordinateTransformFactory lenient =
 *         new CoordinateTransformFactory(DomainErrorPolicy.RETURN_NAN);
 * }</pre>
 *
 * <h2>This class is frozen. It is not, and will not be, re-routed through the new facade.</h2>
 *
 * <p>{@link org.locationtech.proj4j.api.Proj} is the 1.5.0 entry point and it makes different
 * choices &mdash; most consequentially, it <em>refuses</em> to build an operation whose datum change
 * would not actually be performed, throwing
 * {@link ErrorCause#BALLPARK_REJECTED} at creation time. That is the right default for new code.
 *
 * <p>It would be the wrong behaviour to impose here. Re-routing this class would make
 * {@code EPSG:4267 -> EPSG:4269} start <b>throwing</b> for GeoTools, GeoServer, geomesa and every
 * other caller, on code that has worked for fifteen years, in a library most of them reach
 * transitively and did not choose. So:
 *
 * <ul>
 * <li>This class's selection behaviour is <b>unchanged</b>. The transform it returns is the same
 * {@link BasicCoordinateTransform} it has always returned, and an unreachable
 * {@code @}-optional grid is still skipped silently, exactly as in 1.4.3.</li>
 * <li>It is <b>not deprecated</b>, in 1.5.0 or later. Java 8 has no
 * {@code @Deprecated(forRemoval=)}, so the promise is stated in prose instead: <b>this class will
 * not be removed</b>. Nobody should plan a migration they do not need.</li>
 * <li>A caller who <em>wants</em> the strict engine behind this interface changes one line:
 * {@code LegacyAdapters.transformFactory(context)} returns a {@code CoordinateTransformFactory}
 * that applies the new policies. Opting in is visible in the application's own source, which is
 * where a change of behaviour belongs.</li>
 * </ul>
 *
 * <p>What <em>did</em> change in 1.5.0, and applies here too, is per-coordinate honesty: a
 * computation failure is reported as an exception rather than as a plausible coordinate. That is a
 * bug fix, it is governed by {@link DomainErrorPolicy}, and the escape hatch is
 * {@link DomainErrorPolicy#RETURN_NAN}.
 *
 * @author mbdavis
 * @see DomainErrorPolicy
 * @see org.locationtech.proj4j.api.LegacyAdapters#transformFactory(org.locationtech.proj4j.api.ProjContext)
 * @see org.locationtech.proj4j.api.Proj#createCrsToCrs(String, String)
 */
public class CoordinateTransformFactory {

    private final DomainErrorPolicy domainErrorPolicy;

    /**
     * A factory whose transforms fail closed, throwing {@link CrsTransformException} on a
     * per-coordinate error.
     */
    public CoordinateTransformFactory() {
        this(DomainErrorPolicy.THROW);
    }

    /**
     * A factory whose transforms all apply the given policy to a per-coordinate error.
     *
     * @param domainErrorPolicy the policy; null is treated as {@link DomainErrorPolicy#THROW}
     * @since 1.5.0
     */
    public CoordinateTransformFactory(DomainErrorPolicy domainErrorPolicy) {
        this.domainErrorPolicy =
                domainErrorPolicy == null ? DomainErrorPolicy.THROW : domainErrorPolicy;
    }

    /**
     * The policy every transform from this factory applies to a per-coordinate error.
     *
     * @return the policy; never null
     * @since 1.5.0
     */
    public DomainErrorPolicy getDomainErrorPolicy() {
        return domainErrorPolicy;
    }

    /**
     * Creates a transformation from a source CRS to a target CRS,
     * following the logic in PROJ.4.
     * The transformation may include any or all of inverse projection, datum transformation,
     * and reprojection, depending on the nature of the coordinate reference systems
     * provided.
     *
     * @param sourceCRS the source CoordinateReferenceSystem
     * @param targetCRS the target CoordinateReferenceSystem
     * @return a tranformation from the source CRS to the target CRS
     */
    public CoordinateTransform createTransform(CoordinateReferenceSystem sourceCRS, CoordinateReferenceSystem targetCRS) {
        return new BasicCoordinateTransform(sourceCRS, targetCRS, domainErrorPolicy);
    }

    /**
     * Creates a transformation typed as the allocation-free bulk API.
     *
     * <p>The same object {@link #createTransform(CoordinateReferenceSystem,
     * CoordinateReferenceSystem)} returns — {@link BasicCoordinateTransform} implements both
     * interfaces — so a caller who already holds a {@link CoordinateTransform} can simply cast, and
     * one who wants many points per call can ask for the bulk type directly and never see the
     * single-point signature:
     *
     * <pre>{@code
     * BulkCoordinateTransform op = factory.createBulkTransform(wgs84, utm33n);
     * byte[] status = new byte[maxVertices];          // caller-owned, reused per geometry
     * if (op.transform2D(xy, 0, numVertices, 2, status) != 0) {
     *     return emptyGeometry();
     * }
     * }</pre>
     *
     * <p>The declared return type is the point: it is a compile-time statement that the caller has
     * opted into the batch shape, and it means a future engine can return a different
     * implementation for the bulk path without changing this signature.
     *
     * <p>Results are bit-for-bit identical to the same points through
     * {@link CoordinateTransform#transform(ProjCoordinate, ProjCoordinate)}, and the factory's
     * {@link DomainErrorPolicy} applies to both — see {@link BulkCoordinateTransform} for how the
     * policy composes with the status array.
     *
     * @param sourceCRS the source CoordinateReferenceSystem
     * @param targetCRS the target CoordinateReferenceSystem
     * @return a bulk transformation from the source CRS to the target CRS
     * @since 1.5.0
     */
    public BulkCoordinateTransform createBulkTransform(CoordinateReferenceSystem sourceCRS,
                                                       CoordinateReferenceSystem targetCRS) {
        return new BasicCoordinateTransform(sourceCRS, targetCRS, domainErrorPolicy);
    }
}
