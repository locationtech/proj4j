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

/**
 * Signals that a situation or data state has been encountered
 * which prevents computation from proceeding,
 * or which would lead to erroneous results.
 * <p>
 * This is the base class for all exceptions
 * thrown in the Proj4J API.
 * <p>
 * Every Proj4J exception carries a machine-readable {@link #cause()}. The subtype tells a
 * caller what kind of thing went wrong at the level of the Java type system; {@code cause()}
 * tells it <em>why</em>, on a stable enum that can be counted, logged, and switched on.
 * Exceptions thrown from code paths that predate the taxonomy report
 * {@link ErrorCause#INTERNAL_ERROR}, which is the honest answer: not yet attributed.
 * <p>
 * The hierarchy below this class is:
 * <pre>
 * Proj4jException                              cause() defaults to INTERNAL_ERROR
 * └── {@link CrsTransformException}             cause() always set
 *     ├── {@link CrsCreationException}
 *     │   ├── {@link UnknownAuthorityCodeException}   UNKNOWN_CRS
 *     │   ├── {@link InvalidValueException}           INVALID_PARAM_VALUE
 *     │   │   └── {@link ContradictoryParameterException}  CONTRADICTORY_PARAMS
 *     │   └── {@link UnsupportedParameterException}   PROJECTION_NOT_IMPLEMENTED
 *     ├── {@link ProjectionException}                 COORDINATE_OUT_OF_DOMAIN
 *     └── {@link ConvergenceFailureException}         NUMERICAL_FAILURE
 * </pre>
 * This re-parenting is source- and binary-compatible: the old supertype remains in every
 * chain, so {@code catch (Proj4jException)} is unchanged and every pre-existing
 * {@code catch} clause still fires on exactly the same throws it always did.
 *
 * @author mbdavis
 *
 */
public class Proj4jException extends RuntimeException
{

	private static final long serialVersionUID = 6314691485557593883L;

	/**
	 * System property that turns stack-trace capture back on:
	 * {@code -Dproj4j.exceptions.stackTraces=true}.
	 */
	public static final String STACK_TRACES_PROPERTY = "proj4j.exceptions.stackTraces";

	/**
	 * Read once at class initialisation, then mutable through
	 * {@link #setStackTraceCaptureEnabled(boolean)}. {@code volatile} rather than {@code final}
	 * because a flag that can only be set on the command line cannot be shown working: a test has
	 * to be able to run the same throw site under both settings in one JVM and compare, and
	 * {@code Proj4jExceptionStackTraceTest} does exactly that. The read costs a plain load and is
	 * paid once per exception construction, against the roughly 600 ns it saves.
	 */
	private static volatile boolean captureStackTraces = Boolean.getBoolean(STACK_TRACES_PROPERTY);

	/**
	 * Never null. Assigned in every constructor; {@link ErrorCause#INTERNAL_ERROR} where the
	 * throw site has not been attributed to a specific cause yet.
	 */
	private final ErrorCause errorCause;

	/**
	 * Creates an exception with no message. {@link #cause()} reports
	 * {@link ErrorCause#INTERNAL_ERROR}, since this form records no reason.
	 */
	public Proj4jException() {
		this.errorCause = ErrorCause.INTERNAL_ERROR;
	}

	/**
	 * Creates an exception with a message. {@link #cause()} reports
	 * {@link ErrorCause#INTERNAL_ERROR}; prefer
	 * {@link #Proj4jException(ErrorCause, String, Throwable)} where the reason is known.
	 *
	 * @param message the human-readable detail message
	 */
	public Proj4jException(String message) {
		super(message);
		this.errorCause = ErrorCause.INTERNAL_ERROR;
	}

	/**
	 * Creates an exception wrapping another. {@link #cause()} reports
	 * {@link ErrorCause#INTERNAL_ERROR}; prefer
	 * {@link #Proj4jException(ErrorCause, String, Throwable)} where the reason is known.
	 *
	 * @param message the human-readable detail message
	 * @param cause   the underlying exception, retrievable with {@link #getCause()}
	 */
	public Proj4jException(String message, Exception cause) {
		super(message, cause);
		this.errorCause = ErrorCause.INTERNAL_ERROR;
	}

	/**
	 * The constructor every attributed throw site — and every subclass — goes through.
	 *
	 * @param errorCause the machine-readable reason; null is normalised to
	 *                   {@link ErrorCause#INTERNAL_ERROR} so {@link #cause()} is never null
	 * @param message    the human-readable detail message
	 * @param cause      the underlying throwable, or null
	 */
	protected Proj4jException(ErrorCause errorCause, String message, Throwable cause) {
		super(message, cause);
		this.errorCause = errorCause == null ? ErrorCause.INTERNAL_ERROR : errorCause;
	}

	/**
	 * The machine-readable reason this exception was thrown.
	 * <p>
	 * Note this is <em>not</em> {@link Throwable#getCause()}, which returns the underlying
	 * throwable. The two are independent and both may be interrogated.
	 *
	 * @return the error cause; never null
	 * @see ErrorCause
	 * @since 1.5.0
	 */
	public ErrorCause cause() {
		return errorCause;
	}

	/**
	 * Whether Proj4J exceptions capture a Java stack trace. Off unless
	 * {@code -Dproj4j.exceptions.stackTraces=true} was set at startup or
	 * {@link #setStackTraceCaptureEnabled(boolean)} has been called.
	 *
	 * @return true if {@link #fillInStackTrace()} walks the stack
	 * @since 1.5.0
	 */
	public static boolean isStackTraceCaptureEnabled() {
		return captureStackTraces;
	}

	/**
	 * Turns stack-trace capture on or off for every Proj4J exception constructed afterwards.
	 * Process-wide, and intended for a debugging session or a test; production should set
	 * {@code -D}{@value #STACK_TRACES_PROPERTY}{@code =true} instead so the setting is visible in
	 * the process's own command line.
	 *
	 * @param enabled whether to capture stack traces
	 * @since 1.5.0
	 */
	public static void setStackTraceCaptureEnabled(boolean enabled) {
		captureStackTraces = enabled;
	}

	/**
	 * Suppresses the stack-trace walk unless it has been asked for.
	 *
	 * <h4>Why</h4>
	 * <p>In this library an exception is not, in the common case, a bug report — it is the
	 * <em>answer</em>. {@code COORDINATE_OUTSIDE_GRID} fires for every point outside the declared
	 * {@code +nadgrids=} coverage; {@code COORDINATE_OUT_OF_DOMAIN} fires for every point outside a
	 * projection's domain. The consumer calls this library per row inside a Spark executor, so a
	 * dataset that straddles a grid edge produces one of these per row, and
	 * {@code Throwable.fillInStackTrace} is a native stack walk: measured at
	 * <b>1,440 B/op and 585 ns</b> per refusal by {@code GridShiftBenchmark.noGridHit}, against a
	 * dispatch path the same benchmark prices in tens of nanoseconds. Two to three orders of
	 * magnitude of the cost of saying "no" was frame capture that nothing read.
	 *
	 * <h4>What is not lost</h4>
	 * <p>Everything a caller can act on programmatically survives, because none of it lives in the
	 * frames: the exception <em>type</em>, {@link #cause()}'s machine-readable
	 * {@link ErrorCause}, the {@link Throwable#getMessage() message} — which for the grid and
	 * domain refusals names the grids, the coordinate in degrees and the failing predicate — and
	 * {@link Throwable#getCause()}. What is lost is the Java call site, which is why the flag
	 * exists and why it is a one-line change to get it back.
	 *
	 * <h4>Not a shared instance</h4>
	 * <p>This is <em>not</em> the usual "static preallocated exception" trick. Every throw still
	 * constructs a fresh object carrying its own message, so two threads refusing two different
	 * coordinates get two different, accurate messages. Only the frame walk is skipped.
	 *
	 * @return {@code this}, unfilled, unless capture is enabled
	 */
	@Override
	public synchronized Throwable fillInStackTrace() {
		if (captureStackTraces) {
			return super.fillInStackTrace();
		}
		return this;
	}
}
