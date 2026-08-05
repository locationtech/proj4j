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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.locationtech.proj4j.DomainErrorPolicy;
import org.locationtech.proj4j.io.wkt.AxisOrderPolicy;

/**
 * {@link ProjContext} is the <b>only</b> way a policy can be set, and that is the property under
 * test here as much as the values themselves.
 *
 * <p>A coordinate reference system whose axis order depends on who launched the JVM is not
 * reproducible, and on a Spark or Flink executor the environment is chosen by the cluster operator
 * rather than by the pipeline author. So: no system property, no environment variable, and a process
 * default that can only be set before anything has been built.
 */
public class ProjContextTest {

    @Test
    public void theBuiltInDefaultIsCompatibleOnAxisOrderAndStrictOnEverythingElse() {
        ProjContext d = ProjContext.DEFAULT;
        assertEquals("longitude-first, because adopting authority order is a silent breaking change",
                AxisOrderPolicy.LEGACY, d.axisOrderPolicy());
        assertEquals(BallparkPolicy.REJECT, d.ballparkPolicy());
        assertEquals(GridPolicy.REQUIRE_ALL, d.gridPolicy());
        assertEquals(BestOperationPolicy.REQUIRE_BEST, d.bestOperationPolicy());
        assertEquals(DomainErrorPolicy.THROW, d.domainErrorPolicy());
    }

    @Test
    public void aBuilderRoundTripsAndTheDefaultIsCanonical() {
        assertSame("building the default values must yield the canonical instance",
                ProjContext.DEFAULT, ProjContext.builder().build());
        assertSame(ProjContext.DEFAULT, ProjContext.DEFAULT.toBuilder().build());

        ProjContext custom = ProjContext.builder()
                .axisOrderPolicy(AxisOrderPolicy.AUTHORITY)
                .ballparkPolicy(BallparkPolicy.ALLOW)
                .gridPolicy(GridPolicy.WARN)
                .bestOperationPolicy(BestOperationPolicy.ALLOW_DEGRADED)
                .domainErrorPolicy(DomainErrorPolicy.RETURN_NAN)
                .build();
        assertEquals(AxisOrderPolicy.AUTHORITY, custom.axisOrderPolicy());
        assertEquals(BallparkPolicy.ALLOW, custom.ballparkPolicy());
        assertEquals(GridPolicy.WARN, custom.gridPolicy());
        assertEquals(BestOperationPolicy.ALLOW_DEGRADED, custom.bestOperationPolicy());
        assertEquals(DomainErrorPolicy.RETURN_NAN, custom.domainErrorPolicy());
        assertEquals(custom, custom.toBuilder().build());
        assertNotEquals(ProjContext.DEFAULT, custom);
    }

    /** Null means "leave the default", not "throw": the common caller is reading configuration. */
    @Test
    public void nullMeansTheBuiltInDefault() {
        assertSame(ProjContext.DEFAULT, ProjContext.builder()
                .axisOrderPolicy(null).ballparkPolicy(null).gridPolicy(null)
                .bestOperationPolicy(null).domainErrorPolicy(null).build());
    }

    @Test
    public void withersReturnTheSameInstanceWhenNothingChanges() {
        assertSame(ProjContext.DEFAULT,
                ProjContext.DEFAULT.withAxisOrderPolicy(AxisOrderPolicy.LEGACY));
        assertSame(ProjContext.DEFAULT,
                ProjContext.DEFAULT.withBallparkPolicy(BallparkPolicy.REJECT));
        assertSame(ProjContext.DEFAULT, ProjContext.DEFAULT.withGridPolicy(GridPolicy.REQUIRE_ALL));
        assertSame(ProjContext.DEFAULT,
                ProjContext.DEFAULT.withDomainErrorPolicy(DomainErrorPolicy.THROW));

        ProjContext changed = ProjContext.DEFAULT.withBallparkPolicy(BallparkPolicy.ALLOW);
        assertNotEquals(ProjContext.DEFAULT, changed);
        assertEquals("the original must be untouched", BallparkPolicy.REJECT,
                ProjContext.DEFAULT.ballparkPolicy());
    }

    @Test
    public void describeStatesThatNoPolicyIsReachableFromTheEnvironment() {
        String d = ProjContext.DEFAULT.describe();
        assertTrue(d, d.contains("axisOrderPolicy     = LEGACY"));
        assertTrue("must spell out what LEGACY means for EPSG:4326: " + d,
                d.contains("(lon, lat)"));
        assertTrue(d, d.contains("settable from the environment: NO"));
        assertTrue(d, d.contains("no system property"));
        assertTrue("must not overstate what bestOperationPolicy does with no database to rank "
                + "against: " + d, d.contains("nothing to rank without an authority database"));
        assertTrue("must state whether an authority database is configured, because that is the "
                + "single fact that most changes what this library will answer: " + d,
                d.contains("database            = NONE"));

        String authority = ProjContext.DEFAULT
                .withAxisOrderPolicy(AxisOrderPolicy.AUTHORITY).describe();
        assertTrue(authority, authority.contains("(lat, lon)"));
    }

    /**
     * The process default is locked once anything exists, so one library cannot flip the semantics
     * under another that has already resolved its CRSs.
     *
     * <p>This test is also an assertion about itself: it must not <em>succeed</em> in replacing the
     * default, because a test run whose default context had been swapped would be validating
     * semantics no shipped default produces.
     */
    @Test
    public void theProcessDefaultCannotBeReplacedOnceAnythingHasBeenBuilt() {
        Proj.createCrs("EPSG:4326");
        assertTrue("creating a Crs must lock the default context", Proj.isDefaultContextLocked());

        ProjContext before = Proj.defaultContext();
        try {
            Proj.setDefaultContext(ProjContext.builder()
                    .axisOrderPolicy(AxisOrderPolicy.AUTHORITY).build());
            fail("setDefaultContext must refuse once a Crs exists");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains("cannot be replaced"));
        }
        assertSame("the default must be exactly as it was", before, Proj.defaultContext());
        assertEquals(AxisOrderPolicy.LEGACY, Proj.defaultContext().axisOrderPolicy());
    }

    /** A per-call context is the preferred level and does not touch the process default. */
    @Test
    public void aPerCallContextDoesNotChangeTheProcessDefault() {
        ProjContext authority = ProjContext.builder()
                .axisOrderPolicy(AxisOrderPolicy.AUTHORITY).build();
        assertEquals("neu", Proj.createCrs("EPSG:4326", authority).axisOrder());
        assertEquals(AxisOrderPolicy.LEGACY, Proj.defaultContext().axisOrderPolicy());
        assertEquals("enu", Proj.createCrs("EPSG:4326").axisOrder());
    }

    /** No policy may be reachable from a system property. */
    @Test
    public void noSystemPropertyIsConsulted() {
        String[] plausible = {
                "proj4j.axisOrder", "proj4j.axis.order", "proj4j.axisOrderPolicy",
                "proj4j.ballpark", "proj4j.grids.policy", "PROJ_DATA", "PROJ_NETWORK",
                "PROJ_ONLY_BEST_DEFAULT",
        };
        for (String key : plausible) {
            System.setProperty(key, "AUTHORITY");
        }
        try {
            assertEquals(AxisOrderPolicy.LEGACY, ProjContext.DEFAULT.axisOrderPolicy());
            assertEquals(AxisOrderPolicy.LEGACY, Proj.defaultContext().axisOrderPolicy());
            assertEquals("enu", Proj.createCrs("EPSG:4326").axisOrder());
            assertFalse(Proj.isNetworkEnabled());
        } finally {
            for (String key : plausible) {
                System.clearProperty(key);
            }
        }
    }

    /** The context is immutable, so a shared one cannot be changed by whoever holds it. */
    @Test
    public void everyFieldOfTheContextIsFinal() {
        java.lang.reflect.Field[] fields = ProjContext.class.getDeclaredFields();
        for (java.lang.reflect.Field f : fields) {
            assertTrue(f.getName() + " must be final: a shared context that can be mutated is a "
                            + "policy that can change under a running job",
                    java.lang.reflect.Modifier.isFinal(f.getModifiers()));
        }
    }
}
