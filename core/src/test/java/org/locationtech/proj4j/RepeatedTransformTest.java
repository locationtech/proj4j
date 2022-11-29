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
 *******************************************************************************/
package org.locationtech.proj4j;

import org.junit.Test;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ProjCoordinate;

import static org.junit.Assert.assertTrue;

public class RepeatedTransformTest {

    @Test
    public void testRepeatedTransform() {
        CRSFactory crsFactory = new CRSFactory();

        CoordinateReferenceSystem src = crsFactory.createFromName("epsg:4326");
        CoordinateReferenceSystem dest = crsFactory.createFromName("epsg:27700");

        CoordinateTransformFactory ctf = new CoordinateTransformFactory();
        CoordinateTransform transform = ctf.createTransform(src, dest);

        ProjCoordinate srcPt = new ProjCoordinate(0.899167, 51.357216);
        ProjCoordinate destPt = new ProjCoordinate();

        transform.transform(srcPt, destPt);
        System.out.println(srcPt + " ==> " + destPt);

        // do it again
        ProjCoordinate destPt2 = new ProjCoordinate();
        transform.transform(srcPt, destPt2);
        System.out.println(srcPt + " ==> " + destPt2);

        assertTrue(destPt.equals(destPt2));
    }
}
