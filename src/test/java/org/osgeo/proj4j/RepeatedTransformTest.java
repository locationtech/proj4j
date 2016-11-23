package org.osgeo.proj4j;

import org.junit.Test;

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
