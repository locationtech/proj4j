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
package org.locationtech.proj4j.benchmark;

import java.util.concurrent.TimeUnit;

import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * CRS creation cost, <b>parameterised by position in the init file</b>.
 *
 * <p>This is not an arbitrary parameterisation. {@code Proj4FileReader.readParametersFromFile}
 * ({@code :35-56}) opens the classpath resource and linearly tokenises the <b>888 KB</b>
 * {@code proj4/nad/epsg} file with a {@code StreamTokenizer} <b>on every single call</b>
 * ({@code :76-90}), allocating per entry scanned. So the cost of creating a CRS is a function of how
 * far down the file its code happens to sit, and a benchmark that only measured
 * {@code EPSG:4326} - which sits at line 498 of 11,731, in the first 4% - would report roughly a
 * twentieth of the worst case and would be blind to the entire problem.
 *
 * <p>The three parameter values are the file's actual first, middle and last entries, measured on
 * the checked-in {@code epsg} file (5,755 entries, 11,731 lines):
 * <ul>
 *   <li>{@code EARLY} - {@code EPSG:3819}, line 2, the very first entry.</li>
 *   <li>{@code MIDDLE} - {@code EPSG:5937}, line 5,855, entry 2,878 of 5,755.</li>
 *   <li>{@code LATE} - {@code EPSG:9054}, line 11,732, the very last entry.</li>
 * </ul>
 * The expected shape is a near-linear ramp from EARLY to LATE. <b>If the ramp flattens, the fix
 * landed</b> - parsing each init file once into a {@code Map<String,String[]>} (~5,755 entries at
 * ~120 B, about 1 MB) is what {@code reference/performance.md} prescribes. Keep these three
 * parameters afterwards: the flat ramp is the evidence.
 *
 * <p>If the {@code epsg} file is regenerated the line numbers above move. The codes are still valid
 * subjects, but re-derive the positions before quoting them, and update this comment - a stale
 * position claim here is worse than none.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Fork(value = 3, jvmArgsAppend = {"-XX:+UseSerialGC"})
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
public class CrsParseBenchmark {

    /** Where in the 888 KB init file the code sits. */
    public enum FilePosition {
        /** First entry, line 2. */
        EARLY("EPSG:3819"),
        /** Entry 2,878 of 5,755, line 5,855. */
        MIDDLE("EPSG:5937"),
        /** Last entry, line 11,732. */
        LATE("EPSG:9054");

        private final String code;

        FilePosition(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    @Param
    public FilePosition position;

    private CRSFactory crsFactory;
    private String code;
    private String paramString;

    @Setup(Level.Trial)
    public void setUp() {
        crsFactory = new CRSFactory();
        code = position.code();
        // Fail fast if a regenerated epsg file dropped one of these codes, rather than reporting an
        // exception-throwing benchmark as a timing.
        CoordinateReferenceSystem crs = crsFactory.createFromName(code);
        if (crs == null) {
            throw new IllegalStateException("No such CRS in the init file: " + code
                    + " - the epsg resource was regenerated; re-pick the EARLY/MIDDLE/LATE codes.");
        }
        paramString = crs.getParameterString();
    }

    /**
     * The full cost a consumer pays on a cache miss: linear scan of the init file, tokenise, parse,
     * instantiate the projection, resolve the datum.
     *
     * <p>Note that {@code CRSFactory}'s {@code Registry} and {@code Proj4FileReader} are static, so
     * sharing one factory instance across invocations is not a caching artefact - there is no cache
     * at this layer at all. That is the finding.
     */
    @Benchmark
    public CoordinateReferenceSystem createFromName() {
        return crsFactory.createFromName(code);
    }

    /**
     * Parse only, no file scan: the same {@code Proj4Parser} work with the init-file lookup already
     * done. {@code createFromName} minus this is the file-scan cost, which is the part the
     * {@code Map<String,String[]>} fix removes.
     */
    @Benchmark
    public CoordinateReferenceSystem createFromParameters() {
        return crsFactory.createFromParameters(code, paramString);
    }

    /**
     * The reverse lookup, {@code readEpsgCodeFromFile} ({@code :123-141}), which <b>always scans the
     * whole file</b> regardless of where the answer is - so unlike {@code createFromName} this one
     * should be flat across the three parameters, and its cost is the ceiling the forward lookup
     * approaches as codes move later in the file.
     */
    @Benchmark
    public String readEpsgFromParameters() throws java.io.IOException {
        return crsFactory.readEpsgFromParameters(paramString);
    }
}
