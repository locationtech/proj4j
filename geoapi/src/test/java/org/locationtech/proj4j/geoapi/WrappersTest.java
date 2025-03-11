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
import javax.measure.quantity.Length;
import org.junit.Test;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.datum.AxisOrder;
import org.opengis.geometry.DirectPosition;
import org.opengis.parameter.ParameterValue;
import org.opengis.parameter.ParameterValueGroup;
import org.opengis.referencing.crs.CRSAuthorityFactory;
import org.opengis.referencing.crs.GeographicCRS;
import org.opengis.referencing.crs.ProjectedCRS;
import org.opengis.referencing.datum.GeodeticDatum;
import org.opengis.referencing.datum.PrimeMeridian;
import org.opengis.referencing.datum.Ellipsoid;
import org.opengis.referencing.cs.CartesianCS;
import org.opengis.referencing.cs.EllipsoidalCS;
import org.opengis.referencing.cs.AxisDirection;
import org.opengis.referencing.cs.CoordinateSystemAxis;
import org.opengis.referencing.operation.MathTransform;
import org.opengis.referencing.operation.OperationMethod;
import org.opengis.referencing.operation.Projection;
import org.opengis.referencing.operation.TransformException;
import org.opengis.util.FactoryException;
import org.opengis.test.Validators;

import static org.junit.Assert.*;


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
     * {@return a factory to use for testing CRS creation}.
     */
    private static CRSAuthorityFactory crsFactory() {
        return Wrappers.geoapi(new CRSFactory());
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

        // Verification by GeoAPI
        Validators.validate(datum);
    }

    /**
     * Tests the creation of a geographic CRS.
     * This method verifies the datum (including its dependencies) and the coordinate system.
     *
     * @throws FactoryException if the CRS cannot be created
     */
    @Test
    public void testGeographicCRS() throws FactoryException {
        final Unit<Angle> degree = Units.getInstance().degree;
        final GeographicCRS crs = crsFactory().createGeographicCRS("EPSG:4326");
        assertEquals("EPSG:4326", crs.getName().getCode());

        /*
         * First property of a CRS: the datum, which includes the ellipsoid and the prime meridian.
         * Verify the name, Greenwich longitude, ellipsoid axis lengths and units of measurement.
         */
        final GeodeticDatum datum = crs.getDatum();
        assertEquals("WGS84", datum.getName().getCode());

        final PrimeMeridian pm = datum.getPrimeMeridian();
        assertEquals("greenwich", pm.getName().getCode());
        assertEquals(0, pm.getGreenwichLongitude(), 0);
        assertEquals(degree, pm.getAngularUnit());

        final Ellipsoid ellipsoid = datum.getEllipsoid();
        assertEquals("WGS 84", ellipsoid.getName().getCode());
        assertEquals(6378137,       ellipsoid.getSemiMajorAxis(), 0);
        assertEquals(6356752.31,    ellipsoid.getSemiMinorAxis(), 0.005);
        assertEquals(298.257223563, ellipsoid.getInverseFlattening(), 5E-10);

        /*
         * Second property of a CRS: its coordinate system.
         * Verify axis name, abbreviation, direction and unit.
         */
        final EllipsoidalCS cs = crs.getCoordinateSystem();
        assertEquals(AxisOrder.ENU, Wrappers.axisOrder(cs));
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

        // Verification by GeoAPI
        Validators.validate(crs);
    }

    /**
     * Tests the creation of a projected CRS.
     * This method verifies the datum, the coordinate system and the projection parameters.
     * Opportunistically tests the transformation of a point.
     *
     * @throws FactoryException if the CRS cannot be created
     * @throws TransformException if an error occurred while testing the projection of a point
     */
    @Test
    public void testProjectedCRS() throws FactoryException, TransformException {
        final Unit<Angle> degree = Units.getInstance().degree;
        final Unit<Length> metre = Units.getInstance().metre;
        final ProjectedCRS crs = crsFactory().createProjectedCRS("EPSG:2154");
        assertEquals("EPSG:2154", crs.getName().getCode());

        /*
         * First property of a CRS: the datum, which includes the ellipsoid and the prime meridian.
         * Verify the name, Greenwich longitude, ellipsoid axis lengths and units of measurement.
         */
        final GeodeticDatum datum = crs.getDatum();
        final PrimeMeridian pm = datum.getPrimeMeridian();
        assertEquals("greenwich", pm.getName().getCode());
        assertEquals(0, pm.getGreenwichLongitude(), 0);
        assertEquals(degree, pm.getAngularUnit());

        final Ellipsoid ellipsoid = datum.getEllipsoid();
        assertTrue(ellipsoid.getName().getCode().startsWith("GRS 1980"));
        assertEquals(6378137,       ellipsoid.getSemiMajorAxis(), 0);
        assertEquals(6356752.31,    ellipsoid.getSemiMinorAxis(), 0.005);
        assertEquals(298.257222101, ellipsoid.getInverseFlattening(), 5E-10);

        /*
         * Second property of a CRS: its coordinate system.
         * Verify axis name, abbreviation, direction and unit.
         */
        final CartesianCS cs = crs.getCoordinateSystem();
        assertEquals(AxisOrder.ENU, Wrappers.axisOrder(cs));
        assertEquals(2, cs.getDimension());

        CoordinateSystemAxis axis = cs.getAxis(0);
        assertEquals("Easting", axis.getName().getCode());
        assertEquals("E", axis.getAbbreviation());
        assertEquals(AxisDirection.EAST, axis.getDirection());
        assertEquals(metre, axis.getUnit());
        assertSame(axis, cs.getAxis(0));

        axis = cs.getAxis(1);
        assertEquals("Northing", axis.getName().getCode());
        assertEquals("N", axis.getAbbreviation());
        assertEquals(AxisDirection.NORTH, axis.getDirection());
        assertEquals(metre, axis.getUnit());
        assertSame(axis, cs.getAxis(1));

        /*
         * Property specific to a projected CRS: conversion from the base CRS.
         * Verify parameters having a value different than their default value.
         */
        final GeographicCRS baseCRS = crs.getBaseCRS();
        assertEquals(datum, baseCRS.getDatum());

        final Projection conversionFromBase = crs.getConversionFromBase();
        final OperationMethod method = conversionFromBase.getMethod();
        assertArrayEquals(new String[] {
            "central_meridian",
            "latitude_of_origin",
            "standard_parallel_1",
            "standard_parallel_2",
            "false_easting",
            "false_northing"
        }, method.getParameters().descriptors().stream().map((d) -> d.getName().getCode()).toArray());

        final ParameterValueGroup pv = conversionFromBase.getParameterValues();
        assertEquals(     46.5, pv.parameter("latitude_of_origin") .doubleValue(), 1E-12);
        assertEquals(      3.0, pv.parameter("central_meridian")   .doubleValue(), 1E-12);
        assertEquals(     49.0, pv.parameter("standard_parallel_1").doubleValue(), 1E-12);
        assertEquals(     44.0, pv.parameter("standard_parallel_2").doubleValue(), 1E-12);
        assertEquals( 700000.0, pv.parameter("false_easting")      .doubleValue(), 0);
        assertEquals(6600000.0, pv.parameter("false_northing")     .doubleValue(), 0);

        // Test unit conversion.
        final ParameterValue<?> origin = pv.parameter("latitude_of_origin");
        assertEquals(46.5, origin.doubleValue(degree), 1E-12);
        assertEquals(Math.toRadians(46.5), origin.doubleValue(Units.getInstance().radian), 1E-12);

        /*
         * Test the transform of a point, then test the inverse operation.
         */
        final MathTransform tr = conversionFromBase.getMathTransform();
        DirectPosition pt = Wrappers.geoapi(new ProjCoordinate(3, 46.5));
        assertEquals(2, pt.getDimension());
        pt = tr.transform(pt, pt);
        assertEquals(2, pt.getDimension());
        assertEquals( 700000, pt.getOrdinate(0), 1E-3);
        assertEquals(6600000, pt.getOrdinate(1), 1E-3);
        pt = tr.inverse().transform(pt, pt);
        assertEquals(2, pt.getDimension());
        assertEquals(46.5, pt.getOrdinate(1), 1E-9);
        assertEquals( 3.0, pt.getOrdinate(0), 1E-9);

        // Verification by GeoAPI
        // Disabled because one of the test is a bit too strict. This is fixed in GeoAPI 3.1.
        // Validators.validate(crs);
    }
}
