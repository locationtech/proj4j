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
 *******************************************************************************/
package org.locationtech.proj4j.db;

import java.io.IOException;

import org.locationtech.proj4j.spi.ProjDatabase;

/**
 * Registers this artifact's index as a {@code ProjDatabase} for
 * {@link org.locationtech.proj4j.spi.ProjDatabaseProvider#discover}.
 * <p>
 * Core <strong>never</strong> scans for this implicitly. Discovery happens only when an application
 * asks — either by calling {@code discover}/{@code openFirst}, or by handing the result to
 * {@code ProjContext.Builder}. An implicit {@code ServiceLoader} walk touches a classpath proj4j does
 * not control, and that is how a library minding its own business triggers a {@code LinkageError} in
 * somebody else's jar.
 */
public final class PjdxDatabaseProvider implements org.locationtech.proj4j.spi.ProjDatabaseProvider {

    @Override
    public String name() {
        return "pjdx";
    }

    @Override
    public int priority() {
        return 100;
    }

    @Override
    public ProjDatabase open() throws IOException {
        return Proj4jDb.open();
    }

    @Override
    public String toString() {
        return "PjdxDatabaseProvider[" + name() + ", priority " + priority() + "]";
    }
}
