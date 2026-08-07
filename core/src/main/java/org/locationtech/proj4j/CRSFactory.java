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

import org.locationtech.proj4j.io.Proj4FileReader;
import org.locationtech.proj4j.parser.Proj4Parser;
import org.locationtech.proj4j.vertical.CompoundCrs;
import org.locationtech.proj4j.vertical.CompoundCrsName;
import org.locationtech.proj4j.vertical.VerticalCrs;
import org.locationtech.proj4j.vertical.VerticalCrsRegistry;

import java.io.IOException;

/**
 * A factory which can create {@link CoordinateReferenceSystem}s
 * from a variety of ways
 * of specifying them.
 * This is the primary way of creating coordinate systems
 * for carrying out projections transformations.
 * <p>
 * <code>CoordinateReferenceSystem</code>s can be used to
 * define {@link CoordinateTransform}s to perform transformations
 * on {@link ProjCoordinate}s.
 *
 * @author Martin Davis
 */
public class CRSFactory {

    /**
     * Creates a factory. The reader and {@link Registry} it uses are static, so all instances share
     * them and constructing a second factory costs nothing.
     */
    public CRSFactory() {
    }

    private static Proj4FileReader csReader = new Proj4FileReader();

    private static Registry registry = new Registry();

    // TODO: add method to allow reading from arbitrary PROJ4 CS file

    /**
     * Gets the {@link Registry} used by this factory.
     *
     * @return the Registry
     */
    public Registry getRegistry() {
        return registry;
    }

    /**
     * Creates a {@link CoordinateReferenceSystem} (CRS) from a well-known name.
     * CRS names are of the form: "<code>authority:code</code>",
     * with the components being:
     * <ul>
     * <li><b><code>authority</code></b> is a code for a namespace supported by
     * PROJ.4.
     * Currently supported values are
     * <code>EPSG</code>,
     * <code>ESRI</code>,
     * <code>WORLD</code>,
     * <code>NA83</code>,
     * <code>NAD27</code>.
     * If no authority is provided, the <code>EPSG</code> namespace is assumed.
     * <li><b><code>code</code></b> is the id of a coordinate system in the authority namespace.
     * For example, in the <code>EPSG</code> namespace a code is an integer value
     * which identifies a CRS definition in the EPSG database.
     * (Codes are read and handled as strings).
     * </ul>
     * An example of a valid CRS name is <code>EPSG:3005</code>.
     *
     * @param name the name of a coordinate system, with optional authority prefix
     * @return the {@link CoordinateReferenceSystem} corresponding to the given name
     * @throws UnsupportedParameterException if a PROJ.4 parameter is not supported
     * @throws InvalidValueException         if a parameter value is invalid
     * @throws UnknownAuthorityCodeException if the authority code cannot be found
     */
    public CoordinateReferenceSystem createFromName(String name)
            throws UnsupportedParameterException, InvalidValueException, UnknownAuthorityCodeException {
        String[] params = csReader.getParameters(name);
        if (params == null) {
            // A compound name would otherwise fail as a plain unknown code, with nothing to
            // tell the caller that the name is understood but needs a different method. It
            // must NOT resolve here: createFromName returns a two-dimensional
            // CoordinateReferenceSystem, and silently dropping the vertical half of
            // "EPSG:4326+5773" would answer a 3D question with a 2D CRS.
            if (CompoundCrsName.looksLikeCompound(name)) {
                throw new UnknownAuthorityCodeException(ErrorCause.CRS_TYPE_NOT_SUPPORTED,
                        name + " is a compound (horizontal + vertical) CRS, which cannot be "
                                + "represented by a two-dimensional CoordinateReferenceSystem. "
                                + "Use CRSFactory.createCompound(\"" + name + "\") instead.");
            }
            throw new UnknownAuthorityCodeException(name);
        }
        return createFromParameters(name, params);
    }

    /**
     * Creates a {@link CompoundCrs} from a compound CRS name such as
     * <code>EPSG:4326+5773</code> — WGS 84 with EGM96 geoid height.
     * <p>
     * Both spellings PROJ accepts work: <code>EPSG:4326+5773</code>, where the vertical code
     * inherits the horizontal authority, and <code>EPSG:4326+EPSG:5773</code>. The horizontal
     * half is resolved by {@link #createFromName(String)}, so it must be a code the shipped
     * dictionary knows; the vertical half is resolved by
     * {@link org.locationtech.proj4j.vertical.VerticalCrsRegistry}.
     * <p>
     * <b>The shipped dictionary contains no vertical CRS at all</b>, and no
     * <code>EPSG:4979</code> either — a PROJ.4 <code>+init=</code> dictionary cannot express
     * a standalone vertical CRS. The vertical half therefore comes from a small built-in
     * table transcribed from PROJ 9.8.1's own database, and anything outside it is reported
     * by code rather than guessed at. See {@code VerticalCrsRegistry} for what is present and
     * how to add more.
     *
     * @param name a compound CRS name
     * @return the compound CRS; never null
     * @throws InvalidValueException                                     if the name is not a
     *                                                                   compound reference
     * @throws UnknownAuthorityCodeException                             if the horizontal half
     *                                                                   is unknown
     * @throws org.locationtech.proj4j.vertical.UnknownVerticalCrsException if the vertical half
     *                                                                   is unknown
     * @since 1.5.0
     */
    public CompoundCrs createCompound(String name) {
        CompoundCrsName parts = CompoundCrsName.parse(name);
        CoordinateReferenceSystem horizontal = createFromName(parts.horizontal());
        VerticalCrs vertical =
                VerticalCrsRegistry.require(parts.verticalAuthority(), parts.verticalCode());
        return new CompoundCrs(name, horizontal, vertical);
    }

    /**
     * Whether {@link #createCompound(String)} rather than {@link #createFromName(String)} is
     * the method for this name.
     *
     * @param name any CRS name or PROJ.4 parameter string
     * @return true for a compound CRS reference such as <code>EPSG:4326+5773</code>; false for
     *         a plain <code>authority:code</code> and false for every PROJ.4 parameter string,
     *         including the many that contain a <code>'+'</code>
     * @since 1.5.0
     */
    public static boolean isCompoundName(String name) {
        return CompoundCrsName.looksLikeCompound(name);
    }

    /**
     * Creates a {@link CoordinateReferenceSystem}
     * from a PROJ.4 projection parameter string.
     * <p>
     * An example of a valid PROJ.4 projection parameter string is:
     * <pre>
     * +proj=aea +lat_1=50 +lat_2=58.5 +lat_0=45 +lon_0=-126 +x_0=1000000 +y_0=0 +ellps=GRS80 +units=m
     * </pre>
     *
     * @param name     a name for this coordinate system (may be <code>null</code> for an anonymous coordinate system)
     * @param paramStr a PROJ.4 projection parameter string
     * @return the specified {@link CoordinateReferenceSystem}
     * @throws UnsupportedParameterException if a given PROJ.4 parameter is not supported
     * @throws InvalidValueException         if a supplied parameter value is invalid
     */
    public CoordinateReferenceSystem createFromParameters(String name, String paramStr)
            throws UnsupportedParameterException, InvalidValueException {
        return createFromParameters(name, splitParameters(paramStr));
    }

    /**
     * Creates a {@link CoordinateReferenceSystem}
     * defined by an array of PROJ.4 projection parameters.
     * PROJ.4 parameters are generally of the form
     * "<code>+name=value</code>".
     *
     * @param name   a name for this coordinate system (may be null)
     * @param params an array of PROJ.4 projection parameters
     * @return a {@link CoordinateReferenceSystem}
     * @throws UnsupportedParameterException if a PROJ.4 parameter is not supported
     * @throws InvalidValueException         if a parameter value is invalid
     */
    public CoordinateReferenceSystem createFromParameters(String name, String[] params)
            throws UnsupportedParameterException, InvalidValueException {
        if (params == null)
            return null;

        Proj4Parser parser = new Proj4Parser(registry);
        return parser.parse(name, params);
    }

    /**
     * Finds a EPSG Code
     * from a PROJ.4 projection parameter string.
     * <p>
     * An example of a valid PROJ.4 projection parameter string is:
     * <pre>
     * +proj=aea +lat_1=50 +lat_2=58.5 +lat_0=45 +lon_0=-126 +x_0=1000000 +y_0=0 +ellps=GRS80 +units=m
     * </pre>
     *
     * @param paramStr a PROJ.4 projection parameter string
     * @return the specified {@link CoordinateReferenceSystem}
     * @throws IOException if there was an issue in reading EPSG file
     */
    public String readEpsgFromParameters(String paramStr) throws IOException {
        return readEpsgFromParameters(splitParameters(paramStr));
    }

    /**
     * Finds a EPSG Code
     * defined by an array of PROJ.4 projection parameters.
     * PROJ.4 parameters are generally of the form
     * "<code>+name=value</code>".
     *
     * @param params an array of PROJ.4 projection parameters
     * @return s String EPSG code
     * @throws IOException if there was an issue in reading EPSG file
     */
    public String readEpsgFromParameters(String[] params) throws IOException {
        return csReader.readEpsgCodeFromFile(params);
    }

    private static String[] splitParameters(String paramStr) {
        String[] params = paramStr.split("\\s+");
        return params;
    }
}
