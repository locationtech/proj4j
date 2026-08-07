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

import org.locationtech.proj4j.proj.Projection;

/**
 * Signals that an interative mathematical algorithm has failed to converge.
 * This is usually due to values exceeding the
 * allowable bounds for the computation in which they are being used.
 * <p>
 * {@link #cause()} is {@link ErrorCause#NUMERICAL_FAILURE}, matching PROJ's
 * {@code PROJ_ERR_COORD_TRANSFM_NO_CONVERGENCE}.
 * <p>
 * <b>This is what a non-convergent iteration must do.</b> The alternatives that Proj4J 1.4.3
 * used at fourteen sites are all worse, because they express a failure as a coordinate the caller
 * cannot distinguish from a success:
 * <ul>
 * <li>returning the last unconverged iterate — plausible, finite, and wrong by an unbounded
 *     amount;</li>
 * <li>clamping to a pole — the worst shape of all, because &plusmn;90&deg; is a <em>specific</em>
 *     plausible coordinate that a range check will accept;</li>
 * <li>returning a value derived from an accumulator that was never initialised, which for one
 *     projection yields latitude exactly 0.</li>
 * </ul>
 * A caller's {@code isFinite} guard sees none of those. It sees this.
 *
 * @author mbdavis
 */
public class ConvergenceFailureException extends CrsTransformException {

    private static final long serialVersionUID = 7204539599788654542L;

    /** The cause reported by every constructor that does not take one explicitly. */
    private static final ErrorCause DEFAULT_CAUSE = ErrorCause.NUMERICAL_FAILURE;

    /**
     * Creates an exception reporting {@link ErrorCause#NUMERICAL_FAILURE}.
     *
     * @param message the human-readable detail message; it should say what did not converge, and
     *                after how many iterations
     */
    public ConvergenceFailureException(String message) {
        super(DEFAULT_CAUSE, message);
    }

    /**
     * Creates an exception reporting {@link ErrorCause#NUMERICAL_FAILURE} and wrapping another
     * throwable.
     *
     * @param message   the human-readable detail message
     * @param throwable the underlying throwable, or null
     * @since 1.5.0
     */
    public ConvergenceFailureException(String message, Throwable throwable) {
        super(DEFAULT_CAUSE, message, throwable);
    }

    /**
     * The form the projection kernels use, so the message names the projection that failed.
     *
     * @param proj    the projection whose iteration did not converge
     * @param message what did not converge, and after how many iterations
     * @since 1.5.0
     */
    public ConvergenceFailureException(Projection proj, String message) {
        super(DEFAULT_CAUSE, proj.toString() + ": " + message);
    }

}
