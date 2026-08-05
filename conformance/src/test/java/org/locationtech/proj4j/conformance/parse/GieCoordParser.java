/*
 * Copyright 2026 The Proj4J Contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.locationtech.proj4j.conformance.parse;

import static org.locationtech.proj4j.conformance.parse.PjText.charAt;
import static org.locationtech.proj4j.conformance.parse.PjText.isSpace;

/**
 * Port of {@code parse_coord} ({@code 9.8.1:src/apps/gie.cpp:811-870}): read up
 * to four doubles from an {@code accept}/{@code expect} argument string.
 *
 * <p>Three behaviours here are easy to miss and each one silently changes
 * results if dropped:
 *
 * <ol>
 *   <li>The literal token {@code HUGE_VAL} is a valid component, used by
 *       {@code deformation.gie} and {@code defmodel.gie} to mean "no time
 *       given".</li>
 *   <li>The <strong>DMS fallback</strong>. {@link ProjStrtod} reads
 *       {@code 83d10'W} as plain {@code 83} and stops on the {@code d}. When
 *       the plain parse halts on a non-space character, gie re-parses with
 *       {@code proj_dmstor} and prefers the DMS reading only when
 *       {@code d != dms && |d| < |dms| < |d| + 1} — i.e. when the extra
 *       minutes and seconds add up to less than a whole degree, which is
 *       exactly the signature of a DMS literal. The separate
 *       {@code d == dms && endp != dmsendp} clause exists because
 *       {@code -81d00'00.000} parses to the same number under both but only
 *       {@code proj_dmstor} reports the right end position.</li>
 *   <li>Fewer than two numbers is an <em>error coordinate</em>, not a partial
 *       one. Two or three is fine and {@code dimensionsGiven} records it.</li>
 * </ol>
 */
public final class GieCoordParser {

    private static final String HUGE_VAL_TOKEN = "HUGE_VAL";

    private GieCoordParser() {
    }

    /** Parse the argument text of an {@code accept} or {@code expect} command. */
    public static GieCoord parseCoord(String args) {
        if (args == null) {
            return GieCoord.error(0);
        }
        double[] v = new double[] {0, 0, 0, 0};
        int dimensionsGiven = 0;
        int prev = 0;

        for (int i = 0; i < 4; i++) {
            double d;
            int endp;

            while (charAt(args, prev) != '\0' && isSpace(charAt(args, prev))) {
                ++prev;
            }

            if (args.startsWith(HUGE_VAL_TOKEN, prev)) {
                d = GieCoord.HUGE_VAL;
                endp = prev + HUGE_VAL_TOKEN.length();
            } else {
                ProjStrtod.Result r = ProjStrtod.strtod(args, prev);
                d = r.value;
                endp = r.end;
            }

            if (!Double.isNaN(d) && charAt(args, endp) != '\0' && !isSpace(charAt(args, endp))) {
                ProjDmsToR.Result dr = ProjDmsToR.dmstor(args, prev);
                double dms = Math.toDegrees(dr.radians);
                int dmsendp = dr.end;
                if (d != dms && Math.abs(d) < Math.abs(dms) && Math.abs(dms) < Math.abs(d) + 1) {
                    d = dms;
                    endp = dmsendp;
                }
                /* A number like -81d00'00.000 will be parsed correctly by both
                 * proj_strtod and proj_dmstor but only the latter will return
                 * the correct end-pointer. */
                if (d == dms && endp != dmsendp) {
                    endp = dmsendp;
                }
            }

            /* Break out if there were no more numerals */
            if (prev == endp) {
                return i > 1 ? new GieCoord(v, dimensionsGiven, false)
                             : GieCoord.error(dimensionsGiven);
            }

            v[i] = d;
            prev = endp;
            dimensionsGiven++;
        }

        return new GieCoord(v, dimensionsGiven, false);
    }
}
