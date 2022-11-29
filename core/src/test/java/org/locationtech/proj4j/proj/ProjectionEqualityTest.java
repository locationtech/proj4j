/*******************************************************************************
 * Copyright 2019 Azavea
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
package org.locationtech.proj4j.proj;

import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

/**
 * Tests that Projection equality is semantically correct
 */
public class ProjectionEqualityTest
{
	private static CRSFactory csFactory = new CRSFactory();

	@Test
	public void utmEquality() {

    CoordinateReferenceSystem cs1 = csFactory.createFromName("EPSG:26710");
    CoordinateReferenceSystem cs2 = csFactory.createFromParameters(null, "+proj=utm +zone=10 +datum=NAD27 +units=m +no_defs");
    assertEquals(cs1, cs2);

    CoordinateReferenceSystem cs3 = csFactory.createFromName("EPSG:26711");
    assertNotEquals(cs1, cs3);
  }
}
