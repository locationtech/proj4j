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

import org.locationtech.proj4j.datum.Datum;

/**
 * Creates {@link CoordinateTransform}s
 * from source and target {@link CoordinateReferenceSystem}s.
 *
 * @author mbdavis
 */
public class CoordinateTransformFactory {

    /**
     * Creates a transformation from a source CRS to a target CRS,
     * following the logic in PROJ.4.
     * The transformation may include any or all of inverse projection, datum transformation,
     * and reprojection, depending on the nature of the coordinate reference systems
     * provided.
     *
     * @param sourceCRS the source CoordinateReferenceSystem
     * @param targetCRS the target CoordinateReferenceSystem
     * @return a transformation from the source CRS to the target CRS
     */
    public CoordinateTransform createTransform(CoordinateReferenceSystem sourceCRS, CoordinateReferenceSystem targetCRS) {
    	CoordinateTransform transform = null;
    	if(checkNotWGS(sourceCRS, targetCRS)) {
    		CoordinateReferenceSystem wgs84 = new CRSFactory().createFromName("EPSG:4326");
    		transform = createTransform(sourceCRS, wgs84, targetCRS);
    	}else {
    		transform = new BasicCoordinateTransform(sourceCRS, targetCRS);
    	}
    	return transform;
    }
    
    /**
     * Creates a transformation from a source CRS, through an intermediary, to a target CRS
     *
     * @param sourceCRS the source CoordinateReferenceSystem
     * @param intmCRS the intermediary CoordinateReferenceSystem
     * @param targetCRS the target CoordinateReferenceSystem
     * @return a transformation from the source CRS to the target CRS
     */
    public CoordinateTransform createTransform(CoordinateReferenceSystem sourceCRS, 
    		CoordinateReferenceSystem intmCRS, CoordinateReferenceSystem targetCRS) {
    	return new CompoundCoordinateTransform(sourceCRS, intmCRS, targetCRS);
    }
    
    /**
     * Check if CRS datum WGS84 transformations exist between non WGS84 datums
     * @param sourceCRS the source CoordinateReferenceSystem
     * @param targetCRS the target CoordinateReferenceSystem
     * @return true it not WGS84 transformations when required
     */
    private boolean checkNotWGS(CoordinateReferenceSystem sourceCRS, CoordinateReferenceSystem targetCRS) {
    	boolean notWGS = false;
    	Datum sourceDatum = sourceCRS.getDatum();
    	Datum targetDatum = targetCRS.getDatum();
    	if(sourceDatum != null && targetDatum != null && !sourceDatum.equals(targetDatum)) {
    		notWGS = (sourceDatum.hasTransformToWGS84() && !targetCRS.isGeographic())
    				|| (targetDatum.hasTransformToWGS84() && !sourceCRS.isGeographic());
    	}
    	return notWGS;
    }
    
}
