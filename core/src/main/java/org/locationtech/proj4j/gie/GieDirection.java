/*******************************************************************************
 * Copyright 2026 Proj4J contributors
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
package org.locationtech.proj4j.gie;

/**
 * The direction an operation is run in, mirroring PROJ's {@code PJ_DIRECTION}
 * ({@code PJ_FWD = 1}, {@code PJ_INV = -1}).
 *
 * <p>PROJ's {@code pj_opposite_direction()} ({@code src/coordinates.cpp:48-50}) is literally
 * a negation of the code, which is why the codes are {@code +1}/{@code -1} and not an
 * ordinal. {@link #opposite()} reproduces that.
 *
 * <p>{@code PJ_IDENT} (0) is deliberately absent: the gie comparator never sees it.
 */
public enum GieDirection {

    /** {@code PJ_FWD} — forward. */
    FORWARD(1),

    /** {@code PJ_INV} — inverse. */
    INVERSE(-1);

    private final int code;

    GieDirection(final int code) {
        this.code = code;
    }

    /**
     * @return {@code +1} for {@link #FORWARD}, {@code -1} for {@link #INVERSE}.
     */
    public int code() {
        return code;
    }

    /**
     * {@code pj_opposite_direction(dir)}, i.e. {@code (PJ_DIRECTION) -dir}.
     *
     * @return the other direction.
     */
    public GieDirection opposite() {
        return this == FORWARD ? INVERSE : FORWARD;
    }

    /**
     * @param code {@code 1} or {@code -1}.
     * @return the matching constant.
     * @throws IllegalArgumentException if {@code code} is neither {@code 1} nor {@code -1}.
     */
    public static GieDirection fromCode(final int code) {
        if (code == 1) {
            return FORWARD;
        }
        if (code == -1) {
            return INVERSE;
        }
        throw new IllegalArgumentException("Not a PJ_DIRECTION value handled by gie: " + code);
    }
}
