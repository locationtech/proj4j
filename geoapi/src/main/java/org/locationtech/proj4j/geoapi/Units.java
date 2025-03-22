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
import javax.measure.quantity.Dimensionless;
import javax.measure.spi.ServiceProvider;
import javax.measure.spi.SystemOfUnits;


/**
 * Predefined constants for the units of measurement.
 * The actual JSR-385 implementation is left at user's choice.
 *
 * @author  Martin Desruisseaux (Geomatys)
 */
final class Units {
    /**
     * The default instance, created when first needed.
     *
     * @see #getInstance()
     */
    private static Units instance;

    /**
     * The implementation-dependent system of units for creating base units.
     */
    public final SystemOfUnits system;

    /**
     * Linear unit.
     */
    public final Unit<Length> metre;

    /**
     * Angular unit.
     */
    public final Unit<Angle> degree, radian;

    /**
     * Scale unit.
     */
    public final Unit<Dimensionless> one, ppm;

    /**
     * Creates a new set of units which will use the given system of units.
     *
     * @param  system  the system of units to use for creating base units
     */
    private Units(final SystemOfUnits system) {
        this.system = system;
        metre       = system.getUnit(Length.class);
        radian      = system.getUnit(Angle.class);
        one         = getDimensionless(system);
        degree      = radian.multiply(StrictMath.PI / 180);
        ppm         = one   .divide(1000000);
    }

    /**
     * {@return the default units factory}. This factory uses the unit service provider which is
     * {@linkplain ServiceProvider#current() current} at the time of the first invocation of this method.
     */
    public static synchronized Units getInstance() {
        if (instance == null) {
            instance = new Units(ServiceProvider.current().getSystemOfUnitsService().getSystemOfUnits());
        }
        return instance;
    }

    /**
     * Returns the dimensionless unit. This is a workaround for what seems to be a bug
     * in the reference implementation 1.0.1 of unit API.
     *
     * @param  system  the system of units from which to get the dimensionless unit.
     * @return the dimensionless unit.
     */
    private static Unit<Dimensionless> getDimensionless(final SystemOfUnits system) {
        Unit<Dimensionless> unit = system.getUnit(Dimensionless.class);
        if (unit == null) try {
            unit = ((Unit<?>) Class.forName("tec.units.ri.AbstractUnit").getField("ONE").get(null)).asType(Dimensionless.class);
        } catch (ReflectiveOperationException | ClassCastException e) {
            throw new IllegalArgumentException("Can not create a dimensionless unit from the given provider.");
        }
        return unit;
    }

    /**
     * Returns the given JSR-385 unit of measurement as a PROJ4J instance.
     * Note that there is no method in the reverse direction (from PROJ4J to JSR-385)
     * because current PROJ4J does not tell us whether the unit is linear or angular.
     *
     * @param  unit  the unit of measurement
     * @return the PROJ4J equivalent unit
     * @throws UnconvertibleInstanceException if the unit cannot be mapped
     */
    final org.locationtech.proj4j.units.Unit proj4j(final Unit<?> unit) {
        if (unit.equals(metre))  return org.locationtech.proj4j.units.Units.METRES;
        if (unit.equals(degree)) return org.locationtech.proj4j.units.Units.DEGREES;
        if (unit.equals(one))    return null;

        String symbol = unit.getSymbol().trim();
        if ("°".equals(symbol)) symbol = "degree";
        org.locationtech.proj4j.units.Unit proj4j = org.locationtech.proj4j.units.Units.findUnits(symbol);
        if (org.locationtech.proj4j.units.Units.METRES.equals(proj4j)) {
            // PROJ4J maps every unknown unit to metres, which is unsafe from GeoAPI point of view.
            throw new UnconvertibleInstanceException("Cannot map \"" + symbol + "\" to PROJ4 unit.");
        }
        return proj4j;
    }
}
