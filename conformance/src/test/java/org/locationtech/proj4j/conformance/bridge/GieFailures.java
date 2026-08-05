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
package org.locationtech.proj4j.conformance.bridge;

/**
 * Factories for {@link GieFailure}. The only implementation is immutable.
 */
public final class GieFailures {

    private GieFailures() {
    }

    /** A failure with no originating exception. */
    public static GieFailure of(GieFailureKind kind, String message) {
        return new Immutable(kind, message, null);
    }

    /** A failure carrying the exception it was derived from. */
    public static GieFailure of(GieFailureKind kind, String message, Throwable cause) {
        return new Immutable(kind, message, cause);
    }

    /** Shorthand for the conservative default. */
    public static GieFailure notImplemented(String message) {
        return of(GieFailureKind.NOT_IMPLEMENTED, message);
    }

    /** Shorthand for "PROJ 9.8.1 would reject this too". */
    public static GieFailure invalidDefinition(String message) {
        return of(GieFailureKind.INVALID_DEFINITION, message);
    }

    private static final class Immutable implements GieFailure {

        private final GieFailureKind kind;
        private final String message;
        private final Throwable cause;

        Immutable(GieFailureKind kind, String message, Throwable cause) {
            if (kind == null) {
                throw new IllegalArgumentException("kind");
            }
            if (message == null || message.isEmpty()) {
                throw new IllegalArgumentException("message must be non-empty");
            }
            this.kind = kind;
            this.message = message;
            this.cause = cause;
        }

        @Override
        public GieFailureKind kind() {
            return kind;
        }

        @Override
        public String message() {
            return message;
        }

        @Override
        public Throwable cause() {
            return cause;
        }

        @Override
        public String toString() {
            return kind + ": " + message
                    + (cause == null ? "" : " [" + cause.getClass().getSimpleName() + "]");
        }
    }
}
