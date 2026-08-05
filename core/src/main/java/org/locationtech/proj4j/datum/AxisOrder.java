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
package org.locationtech.proj4j.datum;

import java.io.Serializable;

import org.locationtech.proj4j.InvalidValueException;
import org.locationtech.proj4j.ProjCoordinate;

public final class AxisOrder implements Serializable {

    private static final long serialVersionUID = -725125594686344921L;

    public static enum Axis {
        Easting {
            public double fromENU(ProjCoordinate c) {
                return c.x;
            }
            public void toENU(double x, ProjCoordinate c) {
                c.x = x;
            }
        },
        Westing {
            public double fromENU(ProjCoordinate c) {
                return -c.x;
            }
            public void toENU(double x, ProjCoordinate c) {
                c.x = -x;
            }
        },
        Northing {
            public double fromENU(ProjCoordinate c) {
                return c.y;
            }
            public void toENU(double y, ProjCoordinate c) {
                c.y = y;
            }
        },
        Southing {
            public double fromENU(ProjCoordinate c) {
                return -c.y;
            }
            public void toENU(double y, ProjCoordinate c) {
                c.y = -y;
            }
        },
        Up {
            public double fromENU(ProjCoordinate c) {
                return c.z;
            }
            public void toENU(double z, ProjCoordinate c) {
                c.z = z;
            }
        },
        Down {
            /**
             * Negates, symmetrically with {@link #toENU}. Until 1.5.0 this returned
             * {@code c.z} unnegated while {@code toENU} negated, so {@code +axis=…d} was not
             * an involution: {@code fromENU(toENU(z))} came back as {@code -z}. Every other
             * reversed axis ({@code Westing}, {@code Southing}) negates in both directions,
             * and the axis-order round trip in {@code BasicCoordinateTransform} — {@code toENU}
             * on the source, {@code fromENU} on the target — depends on it.
             */
            public double fromENU(ProjCoordinate c) {
                return -c.z;
            }
            public void toENU(double z, ProjCoordinate c) {
                c.z = -z;
            }
        };

        static Axis fromChar(char c) {
            switch(c) {
                case 'e': return Easting;
                case 'n': return Northing;
                case 'u': return Up;
                case 'w': return Westing;
                case 's': return Southing;
                case 'd': return Down;
            }
            throw new InvalidValueException(
                    "Invalid +axis direction '" + c + "': expected one of e, w, n, s, u, d");
        }

        public abstract double fromENU(ProjCoordinate c);
        public abstract void toENU(double x, ProjCoordinate c);
    }

    public final static AxisOrder ENU =
        new AxisOrder(Axis.Easting, Axis.Northing, Axis.Up);

    private final Axis x, y, z;

    private AxisOrder(Axis x, Axis y, Axis z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    /**
     * Parses PROJ's three-letter {@code +axis=} encoding.
     * <p>
     * Until 1.5.0 a spec of the wrong length threw a bare {@code new Error()} — with no
     * message, and, being an {@link Error} rather than an exception, outside
     * {@code catch (Proj4jException)} and outside most callers' {@code catch (Exception)} as
     * well. In a Spark executor that is a killed task rather than a rejected row.
     *
     * @param spec exactly three characters from {@code e w n s u d}, one per axis
     * @return the axis order
     * @throws InvalidValueException if {@code spec} is null, not exactly three characters, or
     *                               contains a character that is not an axis direction
     */
    public static AxisOrder fromString(String spec) {
        if (spec == null) {
            throw new InvalidValueException("Invalid +axis: value is missing");
        }
        if (spec.length() != 3) {
            throw new InvalidValueException("Invalid +axis=" + spec
                    + ": expected exactly 3 direction characters from \"ewnsud\", one per axis, but got "
                    + spec.length());
        }

        Axis x = Axis.fromChar(spec.charAt(0));
        Axis y = Axis.fromChar(spec.charAt(1));
        Axis z = Axis.fromChar(spec.charAt(2));

        return new AxisOrder(x, y, z);
    }

    public void fromENU(ProjCoordinate coord) {
        double x = this.x.fromENU(coord);
        double y = this.y.fromENU(coord);
        double z = this.z.fromENU(coord);
        coord.x = x;
        coord.y = y;
        coord.z = z;
    }

    public void toENU(ProjCoordinate coord) {
        double x = coord.x;
        double y = coord.y;
        double z = coord.z;
        this.x.toENU(x, coord);
        this.y.toENU(y, coord);
        this.z.toENU(z, coord);
    }

    @Override
    public int hashCode() {
        return x.hashCode() | (17 * y.hashCode()) | (37 * z.hashCode());
    }

    @Override
    public boolean equals(Object that) {
        if (that instanceof AxisOrder) {
            AxisOrder a = (AxisOrder) that;
            return x == a.x && y == a.y && z == a.z;
        } else {
            return false;
        }
    }

    /**
     * Re-canonicalises {@link #ENU} on deserialisation, so that
     * {@code deserialize(serialize(ENU)) == ENU} rather than merely {@code .equals(ENU)}.
     *
     * <p>This class is a value type with a private constructor and exactly one published
     * constant, which is the shape that leads callers to compare with {@code ==}. Without a
     * {@code readResolve}, deserialisation manufactures a fresh instance and every such
     * comparison silently becomes false — the failure mode being a coordinate that is *not*
     * axis-swapped when it should be, i.e. a wrong answer rather than an error.
     *
     * <p><strong>This is a latent hazard for callers, not a live bug in proj4j.</strong>
     * {@code BasicCoordinateTransform:324-325} uses {@code AxisOrder.ENU.equals(srcAxes)}, and
     * a scan of {@code core/src/main} finds no {@code == AxisOrder.ENU} site at all (0 hits;
     * the same scan finds the 2 {@code .equals} sites, so it is not vacuous). The method is
     * added because the type is public API and a Spark executor deserialising a shipped
     * {@code CoordinateTransform} is exactly where the identity would be lost.
     *
     * <p>Private, so it does not participate in the default {@code serialVersionUID}
     * computation and therefore cannot perturb the value pinned above.
     *
     * @return {@link #ENU} when the deserialised value is the ENU order, otherwise {@code this}
     */
    private Object readResolve() {
        return equals(ENU) ? ENU : this;
    }
}
