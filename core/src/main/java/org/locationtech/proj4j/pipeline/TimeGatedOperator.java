/*
 * Copyright 2026 The Proj4J Contributors.
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
package org.locationtech.proj4j.pipeline;

import java.util.Calendar;
import java.util.GregorianCalendar;

import org.locationtech.proj4j.gie.GieIoUnits;

/**
 * The {@code +t_epoch} / {@code +t_final} time bracket shared by
 * {@code +proj=hgridshift} and {@code +proj=vgridshift}
 * ({@code 9.8.1:src/transformations/hgridshift.cpp:74-125},
 * {@code vgridshift.cpp} — upstream's own {@code TODO} says "Refactor into shared
 * function that can be used by both vgridshift and hgridshift", and this is that
 * function).
 *
 * <h2>The condition, which is not a range test</h2>
 *
 * <pre>
 * if (t_final == 0 || t_epoch == 0)          -&gt; always apply
 * else if (t &lt; t_epoch &amp;&amp; t_final &gt; t_epoch) -&gt; apply
 * else                                        -&gt; pass the coordinate through unchanged
 * </pre>
 *
 * <p>Three things about that are easy to get wrong and all three are exercised by
 * {@code deformation.gie}:
 *
 * <ul>
 * <li><b>It is one-sided.</b> The coordinate's epoch must be <em>before</em>
 *     {@code t_epoch}; there is no upper bound on how far before. {@code t_final} only
 *     appears in the sanity check {@code t_final &gt; t_epoch}, never compared against
 *     {@code t}. So with {@code +t_epoch=2010 +t_final=2018}, an observation at 2000
 *     is transformed and one at 2011 <em>and</em> one at 2019 are not.</li>
 * <li><b>Zero is the sentinel for "unset", not a year.</b> {@code Q-&gt;t_final} and
 *     {@code Q-&gt;t_epoch} are default-initialised to {@code 0}, and either being zero
 *     disables the gate entirely rather than making it never fire. Using {@code NaN}
 *     as the sentinel instead would silently turn every gated operation into an
 *     ungated one.</li>
 * <li><b>An unspecified {@code t} is 0, not "missing".</b> gie's {@code parse_coord}
 *     zero-fills, so {@code accept 12 56 0.0} arrives with {@code t = 0}, which is
 *     less than any real {@code t_epoch} and therefore <em>does</em> get the shift.
 *     That is why {@code deformation.gie}'s fourth {@code vgridshift} row expects the
 *     full -36.9960 m rather than an unchanged height.</li>
 * </ul>
 *
 * <h2>{@code +t_final=now}</h2>
 *
 * <p>{@code hgridshift.cpp:168-177}: {@code +t_final} is first read as a double, and
 * <b>only if that yields exactly 0</b> is the string compared against {@code "now"}.
 * The value is then {@code 1900.0 + tm_year + tm_yday / 365.0} from {@code localtime}
 * — note {@code 365.0} unconditionally, so a leap year's 31 December reads as
 * {@code year + 1.0008}, and note {@code localtime} rather than {@code gmtime}, so the
 * value depends on the host time zone. Both are reproduced: the corpus's
 * {@code +t_final=now} rows assert only that the bracket is open for an observation at
 * 2000 and closed for one at 2011, which any plausible "now" satisfies, so fidelity
 * here costs nothing and a divergence would be a latent trap.
 *
 * <p>The wrapper delegates its unit sides and its invertibility to the operator it
 * wraps, so it is invisible to {@link PipelineFactory}'s continuity check.
 *
 * @since 1.5
 */
final class TimeGatedOperator implements PipelineOperator {

    private final PipelineOperator delegate;
    private final double tEpoch;
    private final double tFinal;

    private TimeGatedOperator(final PipelineOperator delegate, final double tEpoch,
                              final double tFinal) {
        this.delegate = delegate;
        this.tEpoch = tEpoch;
        this.tFinal = tFinal;
    }

    /**
     * Wrap {@code delegate} iff the step actually carries a bracket.
     *
     * @param delegate the operator to gate
     * @param params   the step's fully expanded parameter list
     * @return {@code delegate} itself when neither {@code +t_epoch} nor {@code +t_final}
     *         is present or either resolves to 0, otherwise the gated wrapper
     */
    static PipelineOperator wrap(final PipelineOperator delegate, final ProjParams params) {
        final double tFinal = readTFinal(params);
        final double tEpoch = params.has("t_epoch")
                ? params.doubleValue("t_epoch", 0.0)
                : 0.0;
        if (tFinal == 0.0 || tEpoch == 0.0) {
            // The gate would be a no-op; do not pay for it, and do not pretend the
            // operation is time-dependent when upstream's own test says it is not.
            return delegate;
        }
        return new TimeGatedOperator(delegate, tEpoch, tFinal);
    }

    /** {@code hgridshift.cpp:164-178}: a number first, then the literal {@code now}. */
    private static double readTFinal(final ProjParams params) {
        if (!params.has("t_final")) {
            return 0.0;
        }
        final String raw = params.value("t_final");
        if (raw != null && !raw.isEmpty()) {
            double asNumber;
            try {
                asNumber = Double.parseDouble(raw.trim());
            } catch (final NumberFormatException e) {
                // pj_param type 'd' is pj_atof, which returns 0 rather than failing.
                asNumber = 0.0;
            }
            if (asNumber != 0.0) {
                return asNumber;
            }
            if ("now".equals(raw)) {
                return decimalYearNow();
            }
        }
        return 0.0;
    }

    /** {@code 1900.0 + date->tm_year + date->tm_yday / 365.0}, from {@code localtime}. */
    private static double decimalYearNow() {
        final Calendar now = new GregorianCalendar();
        // tm_year is years since 1900; tm_yday is 0-based, where DAY_OF_YEAR is 1-based.
        return now.get(Calendar.YEAR) + (now.get(Calendar.DAY_OF_YEAR) - 1) / 365.0;
    }

    /** @return whether the bracket is open for an observation at {@code t}. */
    private boolean applies(final double t) {
        return t < tEpoch && tFinal > tEpoch;
    }

    @Override
    public GieIoUnits declaredLeft() {
        return delegate.declaredLeft();
    }

    @Override
    public GieIoUnits declaredRight() {
        return delegate.declaredRight();
    }

    @Override
    public void overrideUnits(final GieIoUnits left, final GieIoUnits right) {
        delegate.overrideUnits(left, right);
    }

    @Override
    public void forward(final double[] coord) {
        if (applies(coord[3])) {
            delegate.forward(coord);
        }
    }

    @Override
    public void inverse(final double[] coord) {
        if (applies(coord[3])) {
            delegate.inverse(coord);
        }
    }

    @Override
    public boolean hasInverse() {
        return delegate.hasInverse();
    }

    @Override
    public String description() {
        return delegate.description() + " t_epoch=" + tEpoch + " t_final=" + tFinal;
    }

    @Override
    public String toString() {
        return "TimeGatedOperator[" + description() + "]";
    }
}
