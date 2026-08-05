/*******************************************************************************
 * Copyright 2026
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

package org.locationtech.proj4j.numerics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.datum.Datum;

/**
 * The {@code TYPE_UNKNOWN} early return in {@code BasicCoordinateTransform.datumTransform} must
 * be unconditional and must precede the identical-datums short cut.
 *
 * <p>The file already cites {@code PROJ 5.2.0:src/pj_transform.c}. That source reads:
 * <pre>
 * // We cannot do any meaningful datum transformation if either the source or destination
 * // are of an unknown datum type (ie. only a +ellps declaration, no +datum).
 * if (src-&gt;datum_type == PJD_UNKNOWN || dst-&gt;datum_type == PJD_UNKNOWN) return 0;   // :835-843
 *
 * // Short cut if the datums are identical.
 * if (pj_compare_datums(src, dst)) return 0;                                        // :845-848
 * </pre>
 * proj4j nested the first test inside an ellipsoid-equality guard <em>and</em> ran it after the
 * short cut, so a bare-{@code +ellps} pair with <b>differing</b> ellipsoids fell through to a
 * geocentric round trip that PROJ skips entirely.
 *
 * <p>Measured for {@code +ellps=clrk66} to {@code +ellps=bessel} at (17, 45, 0), which is
 * {@code Proj4VariousTest.testRawEllipse}:
 * <table>
 * <caption>before and after</caption>
 * <tr><th></th><th>longitude</th><th>latitude</th><th>z</th></tr>
 * <tr><td>before</td><td>17.000000000000004</td><td>44.99726082903537</td><td>657.304056584835</td></tr>
 * <tr><td>after</td><td>17.0</td><td>45.0</td><td>0.0</td></tr>
 * </table>
 * The latitude shift was 0.00273917 degrees, about <b>304 m</b>, and the {@code z} was invented
 * out of nothing. That test passed anyway on a 0.01 degree tolerance, which is what hid it.
 */
public class UnknownDatumShortCircuitTest {

    private static final CRSFactory CRS = new CRSFactory();
    private static final CoordinateTransformFactory TRANSFORMS = new CoordinateTransformFactory();

    private static ProjCoordinate transform(String src, String dst, ProjCoordinate in) {
        ProjCoordinate out = new ProjCoordinate();
        TRANSFORMS.createTransform(CRS.createFromParameters("s", src),
                CRS.createFromParameters("d", dst)).transform(in, out);
        return out;
    }

    /** Both sides {@code TYPE_UNKNOWN}, ellipsoids differing: PROJ does nothing at all. */
    @Test
    public void bareEllipsoidPairIsAnIdentityOnLongitudeLatitudeAndHeight() {
        ProjCoordinate got = transform("+proj=latlong +ellps=clrk66",
                "+proj=latlong +ellps=bessel", new ProjCoordinate(17.0, 45.0, 0.0));
        assertEquals("longitude", 17.0, got.x, 1e-13);
        assertEquals("latitude moved 304 m before the hoist", 45.0, got.y, 1e-13);
        assertEquals("z was 657.3 m before the hoist", 0.0, got.z, 1e-13);
    }

    /** The datums really are {@code TYPE_UNKNOWN} and the ellipsoids really do differ. */
    @Test
    public void thePreconditionsHold() {
        CoordinateReferenceSystem clrk66 = CRS.createFromParameters("a",
                "+proj=latlong +ellps=clrk66");
        CoordinateReferenceSystem bessel = CRS.createFromParameters("b",
                "+proj=latlong +ellps=bessel");
        assertEquals(Datum.TYPE_UNKNOWN, clrk66.getDatum().getTransformType());
        assertEquals(Datum.TYPE_UNKNOWN, bessel.getDatum().getTransformType());
        assertTrue("the ellipsoids must differ, or the old nested guard would have caught it",
                !clrk66.getDatum().getEllipsoid()
                        .isEqual(bessel.getDatum().getEllipsoid()));
    }

    /** A {@code TYPE_UNKNOWN} target short-circuits even when the source has a full datum. */
    @Test
    public void unknownOnEitherSideIsEnough() {
        ProjCoordinate got = transform("+proj=latlong +datum=potsdam",
                "+proj=latlong +a=6378137.0 +rf=298.257222", new ProjCoordinate(9.0, 50.0, 0.0));
        assertEquals("longitude must pass through", 9.0, got.x, 1e-13);
        assertEquals("latitude must pass through", 50.0, got.y, 1e-13);

        ProjCoordinate reversed = transform("+proj=latlong +a=6378137.0 +rf=298.257222",
                "+proj=latlong +datum=potsdam", new ProjCoordinate(9.0, 50.0, 0.0));
        assertEquals(9.0, reversed.x, 1e-13);
        assertEquals(50.0, reversed.y, 1e-13);
    }

    /** Two fully-specified datums must still be shifted — the hoist must not disable that. */
    @Test
    public void realDatumShiftsStillHappen() {
        ProjCoordinate got = transform("+proj=latlong +datum=potsdam",
                "+proj=latlong +datum=WGS84", new ProjCoordinate(9.0, 50.0, 0.0));
        double shift = Math.hypot((got.x - 9.0) * 111320.0 * Math.cos(Math.toRadians(50.0)),
                (got.y - 50.0) * 111320.0);
        assertTrue("potsdam to WGS84 must move the point by hundreds of metres, moved "
                + shift + " m", shift > 100.0 && shift < 2000.0);
    }
}
