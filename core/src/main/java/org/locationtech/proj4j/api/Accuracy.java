/*
 * Copyright 2026, PROJ4J contributors
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
package org.locationtech.proj4j.api;

/**
 * The stated horizontal accuracy of a coordinate operation, in metres.
 *
 * <h2>What "no accuracy" means, and why it is not zero</h2>
 *
 * <p>An accuracy is <b>authority metadata</b>, from EPSG by way of the {@code coordinate_operation}
 * tables. It is read when a {@link ProjContext#database()} is configured &mdash; 0.15&nbsp;m for
 * {@code EPSG:1241}, 1.0&nbsp;m for {@code EPSG:7710}, 2.0&nbsp;m for the WGS&nbsp;84 ensemble
 * offsets &mdash; and {@link CrsOperation#accuracy()} is {@link java.util.Optional#empty()} without
 * one, because there is then nothing to read. Absence is reported as absence, never as {@code 0.0}
 * and never as an estimate: "we do not know" and "sub-metre" are different claims, and conflating
 * them is how an unshifted datum comes to look like an exact one.
 *
 * <p>The absence is also structural for one class of operation. A <em>ballpark</em> transformation
 * &mdash; see {@link BallparkPolicy} &mdash; never has an accuracy, in PROJ either: PROJ does not
 * assign one, because there is no operation whose accuracy could be quoted. So
 * {@code isBallparkTransformation() == true} implies {@code accuracy().isEmpty()}, and that
 * implication is a permanent property rather than a temporary gap.
 *
 * <p>Immutable and safe to share between threads.
 *
 * @see CrsOperation#accuracy()
 * @since 1.5.0
 */
public final class Accuracy {

    private final double metres;
    private final String source;

    /**
     * Creates a stated accuracy.
     *
     * @param metres the accuracy in metres; must be finite and non-negative
     * @param source where the figure came from, for example {@code "EPSG:1188"}; may be null
     * @throws IllegalArgumentException if {@code metres} is not a finite, non-negative number
     */
    public Accuracy(double metres, String source) {
        if (Double.isNaN(metres) || Double.isInfinite(metres) || metres < 0.0) {
            throw new IllegalArgumentException(
                    "accuracy must be a finite, non-negative number of metres, not " + metres);
        }
        this.metres = metres;
        this.source = source;
    }

    /**
     * The accuracy in metres.
     *
     * @return a finite, non-negative number of metres
     */
    public double metres() {
        return metres;
    }

    /**
     * Where this figure came from &mdash; an EPSG operation code, a grid's own metadata, or null if
     * unattributed.
     *
     * @return the source, or null
     */
    public String source() {
        return source;
    }

    @Override
    public String toString() {
        return source == null ? metres + " m" : metres + " m (" + source + ")";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Accuracy)) {
            return false;
        }
        Accuracy that = (Accuracy) o;
        return Double.compare(metres, that.metres) == 0
                && (source == null ? that.source == null : source.equals(that.source));
    }

    @Override
    public int hashCode() {
        return 31 * Double.valueOf(metres).hashCode() + (source == null ? 0 : source.hashCode());
    }
}
