/*******************************************************************************
 * Copyright 2023 FPS BOSA
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

import org.junit.Assert;
import org.junit.Test;

import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ProjCoordinate;

import java.net.URISyntaxException;

/**
 * Using grid shifts for Catalonia
 * @see https://geoinquiets.cat/
 * 
 * @author Bart Hanssens
 */
public class NTV2Test {

    private static CRSFactory CRS = new CRSFactory();
    private static CoordinateTransformFactory CT = new CoordinateTransformFactory();
    
    private static CoordinateReferenceSystem cs1 = CRS.createFromParameters("23031",
            "+proj=utm +zone=31 +ellps=intl +nadgrids=100800401.gsb +units=m +no_defs");
    private static CoordinateReferenceSystem cs2 = CRS.createFromName("EPSG:25831");


    @Test
    public void gridShiftNTV2() {
        CoordinateTransform ct = CT.createTransform(cs1, cs2);

        ProjCoordinate expected1 = new ProjCoordinate(299905.060, 4499796.515);
        ProjCoordinate result1 = new ProjCoordinate();
        ct.transform(new ProjCoordinate(300000.0, 4500000.0), result1);

        Assert.assertTrue(expected1.areXOrdinatesEqual(result1, 0.001) &&
                          expected1.areYOrdinatesEqual(result1, 0.001));

        ProjCoordinate expected2 = new ProjCoordinate(519906.767, 4679795.125);
        ProjCoordinate result2 = new ProjCoordinate();
        ct.transform(new ProjCoordinate(520000.0, 4680000.0), result2);

        Assert.assertTrue(expected2.areXOrdinatesEqual(result2, 0.001) &&
                          expected2.areYOrdinatesEqual(result2, 0.001));
    }

    @Test
    public void gridShiftNTV2Inverse() {
        CoordinateTransform ct = CT.createTransform(cs2, cs1);

        ProjCoordinate expected1 = new ProjCoordinate(315093.094, 4740203.227);
        ProjCoordinate result1 = new ProjCoordinate();
        ct.transform(new ProjCoordinate(315000.0, 4740000.0), result1);

        Assert.assertTrue(expected1.areXOrdinatesEqual(result1, 0.001) &&
                          expected1.areYOrdinatesEqual(result1, 0.001));

        ProjCoordinate expected2 = new ProjCoordinate(420093.993, 4600204.241);
        ProjCoordinate result2 = new ProjCoordinate();
        ct.transform(new ProjCoordinate(420000.0, 4600000.0), result2);

        Assert.assertTrue(expected2.areXOrdinatesEqual(result2, 0.001) &&
                          expected2.areYOrdinatesEqual(result2, 0.001));
    }

    @Test
    public void nadGridExternalTest() throws URISyntaxException {
        String path = this.getClass().getResource("/proj4/nad/100800401.gsb").toURI().getPath();
        CRSFactory crsFactory = new CRSFactory();

        CoordinateReferenceSystem tmercWithNadGridV2 =
                crsFactory.createFromParameters("EPSG:2100",
                        "+proj=tmerc +lat_0=0 +lon_0=24 +k=0.9996 +x_0=500000 +y_0=0 +ellps=GRS80 +towgs84=-199.87,74.79,246.62,0,0,0,0 +units=m +nadgrids="
                                + path + " +no_defs"
                );

        Assert.assertEquals(Datum.TYPE_GRIDSHIFT, tmercWithNadGridV2.getDatum().getTransformType());
    }
}
