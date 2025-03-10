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
package org.locationtech.proj4j.geoapi;

import javax.measure.Unit;
import javax.measure.quantity.Angle;
import org.junit.Test;
import org.locationtech.proj4j.CRSFactory;
import org.opengis.referencing.crs.GeographicCRS;
import org.opengis.referencing.datum.GeodeticDatum;
import org.opengis.referencing.datum.PrimeMeridian;
import org.opengis.referencing.datum.Ellipsoid;
import org.opengis.referencing.cs.EllipsoidalCS;
import org.opengis.referencing.cs.AxisDirection;
import org.opengis.referencing.cs.CoordinateSystemAxis;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;


/**
 * Tests a few wrapper methods.
 *
 * @author Martin Desruisseaux (Geomatys)
 */
public final class WrappersTest {
    /**
     * Creates a new test case.
     */
    public WrappersTest() {
    }

    /**
     * Tests the wrapping of a datum.
     */
    @Test
    public void testDatum() {
        GeodeticDatum datum = Wrappers.geoapi(org.locationtech.proj4j.datum.Datum.NZGD49);
        assertEquals("New Zealand Geodetic Datum 1949", datum.getName().getCode());
        assertEquals("nzgd49", datum.getAlias().iterator().next().toString());

        Ellipsoid ellipsoid = datum.getEllipsoid();
        assertEquals("International 1909 (Hayford)", ellipsoid.getName().getCode());
        assertEquals("intl",     ellipsoid.getAlias().iterator().next().toString());
        assertEquals(6378388,    ellipsoid.getSemiMajorAxis(), 0);
        assertEquals(6356911.95, ellipsoid.getSemiMinorAxis(), 0.005);
        assertEquals(297,        ellipsoid.getInverseFlattening(), 5E-10);
    }

    /**
     * Tests the creation of a geographic CRS.
     */
    @Test
    public void testGeographicCRS() {
        final Unit<Angle> degree = Units.getInstance().degree;

        final CRSFactory crsFactory = new CRSFactory();
        GeographicCRS crs = (GeographicCRS) Wrappers.geoapi(crsFactory.createFromName("EPSG:4326"));
        assertEquals("EPSG:4326", crs.getName().getCode());

        GeodeticDatum datum = crs.getDatum();
        assertEquals("WGS84", datum.getName().getCode());

        PrimeMeridian pm = datum.getPrimeMeridian();
        assertEquals("greenwich", pm.getName().getCode());
        assertEquals(0, pm.getGreenwichLongitude(), 0);
        assertEquals(degree, pm.getAngularUnit());

        Ellipsoid ellipsoid = datum.getEllipsoid();
        assertEquals("WGS 84", ellipsoid.getName().getCode());
        assertEquals(6378137,       ellipsoid.getSemiMajorAxis(), 0);
        assertEquals(6356752.31,    ellipsoid.getSemiMinorAxis(), 0.005);
        assertEquals(298.257223563, ellipsoid.getInverseFlattening(), 5E-10);

        EllipsoidalCS cs = crs.getCoordinateSystem();
        assertEquals(2, cs.getDimension());

        CoordinateSystemAxis axis = cs.getAxis(0);
        assertEquals("Geodetic longitude", axis.getName().getCode());
        assertEquals("lon", axis.getAbbreviation());
        assertEquals(AxisDirection.EAST, axis.getDirection());
        assertEquals(degree, axis.getUnit());
        assertSame(axis, cs.getAxis(0));

        axis = cs.getAxis(1);
        assertEquals("Geodetic latitude", axis.getName().getCode());
        assertEquals("lat", axis.getAbbreviation());
        assertEquals(AxisDirection.NORTH, axis.getDirection());
        assertEquals(degree, axis.getUnit());
        assertSame(axis, cs.getAxis(1));
    }
}
