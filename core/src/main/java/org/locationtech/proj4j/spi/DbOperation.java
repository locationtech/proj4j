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
 *******************************************************************************/
package org.locationtech.proj4j.spi;

import java.util.Collections;
import java.util.List;

/**
 * A CRS-to-CRS coordinate operation, flattened across the four upstream operation tables.
 * <p>
 * {@link #kind()} says which table the row came from and therefore which accessors carry the payload:
 *
 * <table>
 *   <caption>payload by operation kind</caption>
 *   <tr><th>kind</th><th>payload</th></tr>
 *   <tr><td>{@code HELMERT_TRANSFORMATION}</td>
 *       <td>{@link #parameters()} — up to 15 of {@code tx ty tz rx ry rz ds} and their rates, plus
 *           {@code epoch} and the {@code px py pz} pivot, each with its own unit</td></tr>
 *   <tr><td>{@code GRID_TRANSFORMATION}</td>
 *       <td>{@link #gridNames()} (one or two), {@link #parameters()}, {@link #interpolationCrs()}</td></tr>
 *   <tr><td>{@code OTHER_TRANSFORMATION}</td>
 *       <td>{@link #parameters()} (up to 9), optionally one grid. When
 *           {@link #methodAuthName()} is {@code "PROJ"} and {@link #methodCode()} is
 *           {@code "PROJString"} or {@code "WKT"}, {@link #methodName()} <em>is</em> the definition —
 *           a whole PROJ pipeline or a WKT CoordinateOperation, not a label</td></tr>
 *   <tr><td>{@code CONCATENATED_OPERATION}</td><td>{@link #steps()}; no method, no parameters</td></tr>
 * </table>
 *
 * The {@code PROJ:PROJString} case is not an edge case to defer: it is how all 19
 * {@code other_transformation} rows of the NKG authority are expressed, e.g.
 * {@code +proj=deformation +dt=15.829 +grids=eur_nkg_nkgrf17vel.tif}, and the 34 NKG concatenated
 * operations are built from them.
 * <p>
 * {@link #accuracy()} is the authority's published figure in metres. Its <em>absence</em> is
 * meaningful: PROJ never assigns an accuracy to a ballpark operation, so an empty accuracy on a
 * synthesised datum change is the database saying "this is a guess".
 */
public final class DbOperation {

    private final DbObjectType kind;
    private final String authName;
    private final String code;
    private final String name;
    private final String methodAuthName;
    private final String methodCode;
    private final String methodName;
    private final DbObjectRef sourceCrs;
    private final DbObjectRef targetCrs;
    private final double accuracy;
    private final List<DbParam> parameters;
    private final List<String> gridNames;
    private final DbObjectRef interpolationCrs;
    private final List<DbOperationStep> steps;
    private final String operationVersion;
    private final boolean deprecated;

    public DbOperation(DbObjectType kind, String authName, String code, String name,
                       String methodAuthName, String methodCode, String methodName,
                       DbObjectRef sourceCrs, DbObjectRef targetCrs, double accuracy,
                       List<DbParam> parameters, List<String> gridNames,
                       DbObjectRef interpolationCrs, List<DbOperationStep> steps,
                       String operationVersion, boolean deprecated) {
        this.kind = kind;
        this.authName = authName;
        this.code = code;
        this.name = name;
        this.methodAuthName = methodAuthName;
        this.methodCode = methodCode;
        this.methodName = methodName;
        this.sourceCrs = sourceCrs;
        this.targetCrs = targetCrs;
        this.accuracy = accuracy;
        this.parameters = parameters == null
                ? Collections.<DbParam>emptyList()
                : Collections.unmodifiableList(parameters);
        this.gridNames = gridNames == null
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(gridNames);
        this.interpolationCrs = interpolationCrs;
        this.steps = steps == null
                ? Collections.<DbOperationStep>emptyList()
                : Collections.unmodifiableList(steps);
        this.operationVersion = operationVersion;
        this.deprecated = deprecated;
    }

    /**
     * One of the four {@link DbObjectType#isOperation()} constants.
     */
    public DbObjectType kind() {
        return kind;
    }

    public String authName() {
        return authName;
    }

    public String code() {
        return code;
    }

    /** {@code "OSGB36 to WGS 84 (9)"}. The name a caller needs in order to know what was chosen. */
    public String name() {
        return name;
    }

    /** Null for a concatenated operation. */
    public String methodAuthName() {
        return methodAuthName;
    }

    /** Null for a concatenated operation. See the class comment for {@code PROJ:PROJString}. */
    public String methodCode() {
        return methodCode;
    }

    /**
     * The method's display name — <em>except</em> when {@link #isProjStringMethod()} or
     * {@link #isWktMethod()}, in which case this is the definition itself.
     */
    public String methodName() {
        return methodName;
    }

    /**
     * @return {@code true} iff {@link #methodName()} is a literal PROJ string, typically a pipeline.
     */
    public boolean isProjStringMethod() {
        return "PROJ".equals(methodAuthName) && "PROJString".equals(methodCode);
    }

    /**
     * @return {@code true} iff {@link #methodName()} is a WKT CoordinateOperation.
     */
    public boolean isWktMethod() {
        return "PROJ".equals(methodAuthName) && "WKT".equals(methodCode);
    }

    public DbObjectRef sourceCrs() {
        return sourceCrs;
    }

    public DbObjectRef targetCrs() {
        return targetCrs;
    }

    /**
     * Metres, or {@link Double#NaN} if the authority published none. Use {@link #hasAccuracy()} rather
     * than comparing, and never substitute a default: an invented accuracy is what lets a ballpark
     * operation win a ranking.
     */
    public double accuracy() {
        return accuracy;
    }

    public boolean hasAccuracy() {
        return !Double.isNaN(accuracy);
    }

    /**
     * Present parameters in upstream slot order, absent slots omitted. Unmodifiable.
     */
    public List<DbParam> parameters() {
        return parameters;
    }

    /**
     * Grid file names as the <em>authority</em> spells them, in slot order ({@code grid_name} then
     * {@code grid2_name}). These are original names, not PROJ names: resolve them through
     * {@link ProjDatabase#gridAlternative(String)} to get the modern
     * {@code uk_os_OSTN15_NTv2_OSGBtoETRS.tif} form and its format and method. Unmodifiable.
     */
    public List<String> gridNames() {
        return gridNames;
    }

    /** The interpolation CRS, or null. */
    public DbObjectRef interpolationCrs() {
        return interpolationCrs;
    }

    /**
     * Steps in {@code step_number} order for a concatenated operation; empty otherwise. Unmodifiable.
     */
    public List<DbOperationStep> steps() {
        return steps;
    }

    /** The authority's operation version string, or null. */
    public String operationVersion() {
        return operationVersion;
    }

    public boolean deprecated() {
        return deprecated;
    }

    public DbObjectRef ref() {
        return new DbObjectRef(kind, authName, code);
    }

    @Override
    public String toString() {
        return authName + ":" + code + " " + name + " ("
                + (sourceCrs == null ? "?" : sourceCrs.authorityCode()) + " -> "
                + (targetCrs == null ? "?" : targetCrs.authorityCode())
                + (hasAccuracy() ? ", " + accuracy + " m" : ", accuracy unknown") + ")";
    }
}
