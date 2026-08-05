package org.locationtech.proj4j.datum;

import org.junit.Test;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;

import static org.junit.Assert.assertTrue;

public class TransformerTypeTest {

    private final CRSFactory crsFactory = new CRSFactory();

    @Test
    public void isTransformerTypeWgs84() {

      String utm32znParameters = "+proj=tmerc +lat_0=0.0 +lon_0=9.0 +k_0=0.9996 +x_0=3.25E7 +y_0=0.0 +a=6378137.0 +rf=298.257222101 +pm=Greenwich +units=m +no_defs";
      CoordinateReferenceSystem utm32znCrs = crsFactory.createFromParameters("Anon", utm32znParameters);

      assertTrue(utm32znCrs.getDatum().getTransformType() == Datum.TYPE_WGS84);
    }
}
