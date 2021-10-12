/*******************************************************************************
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

/**
 * Represents the operation of transforming a {@link ProjCoordinate} from one
 * {@link CoordinateReferenceSystem}, through an intermediary, into a different
 * one.
 *
 * @author Brian Osborn
 * @see CoordinateTransformFactory
 */
public class CompoundCoordinateTransform implements CoordinateTransform {

	/**
	 * Serial Version
	 */
	private static final long serialVersionUID = 1L;

	/**
	 * transform from the source CRS to an intermediary CRS
	 */
	private final CoordinateTransform transform1;

	/**
	 * the transform from an intermediary CRS to the target CRS
	 */
	private final CoordinateTransform transform2;

	/**
	 * Creates a transformation from a source {@link CoordinateReferenceSystem},
	 * through an intermediary, into a different one.
	 * 
	 * 
	 * @param srcCRS
	 *            the source CRS to transform from
	 * @param intmCRS
	 *            the intermediary CRS to transform through
	 * @param tgtCRS
	 *            the target CRS to transform to
	 */
	public CompoundCoordinateTransform(CoordinateReferenceSystem srcCRS,
			CoordinateReferenceSystem intmCRS,
			CoordinateReferenceSystem tgtCRS) {
		this(new BasicCoordinateTransform(srcCRS, intmCRS),
				new BasicCoordinateTransform(intmCRS, tgtCRS));
	}

	/**
	 * Creates a transformation between two {@link CoordinateTransform}
	 * transformations
	 * 
	 * @param transform1
	 *            the transform from the source CRS to an intermediary CRS
	 * @param transform2
	 *            the transform from an intermediary CRS to the target CRS
	 */
	public CompoundCoordinateTransform(CoordinateTransform transform1,
			CoordinateTransform transform2) {
		this.transform1 = transform1;
		this.transform2 = transform2;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public CoordinateReferenceSystem getSourceCRS() {
		return transform1.getSourceCRS();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public CoordinateReferenceSystem getTargetCRS() {
		return transform2.getTargetCRS();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public ProjCoordinate transform(ProjCoordinate src, ProjCoordinate tgt)
			throws Proj4jException {
		tgt = transform1.transform(src, tgt);
		return transform2.transform(tgt, tgt);
	}

}
