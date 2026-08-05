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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.Test;

/**
 * The seven introspection calls a downstream consumer asked for by name -- {@code version()},
 * {@code databaseVersion()}, {@code availableGrids()}, {@code isGeocentric()}, {@code isAngular()},
 * {@code axisOrder()}, {@code describe()} -- and, more importantly, the two things they must
 * <b>refuse</b> to claim.
 *
 * <p>Introspection that lies is worse than no introspection at all, because it converts a caller's
 * uncertainty into false confidence. The two refusals are:
 *
 * <ol>
 * <li>{@code databaseVersion()} must not claim a {@code proj.db}. There is none; the EPSG data is a
 * v9.2-era PROJ.4 {@code +init=} dictionary carrying no version stamp, while PROJ 9.8.1 ships EPSG
 * v12.029.</li>
 * <li>{@code availableGrids()} must distinguish reachable from declared, because a silently-skipped
 * grid reporting success is this library's worst measured defect: 95.573&nbsp;m at San Francisco with
 * no warning.</li>
 * </ol>
 */
public class ProjIntrospectionTest {

    // -------------------------------------------------------------------------------- 1. version()

    @Test
    public void versionNamesTheLibraryAndThePROJSemanticsItTargets() {
        String v = Proj.version();
        assertNotNull(v);
        assertTrue(v, v.startsWith("proj4j "));
        assertTrue("version() must name the PROJ release whose semantics are implemented: " + v,
                v.contains("9.8.1"));
        // Core's own test classpath has no ProjDatabase implementation -- deliberately, so that
        // everything here is exercised in the deployment a consumer gets before adding proj4j-db.
        // version() must say so rather than let a reader assume authority metadata is available.
        assertTrue("version() must not imply an authority database is present: " + v,
                v.contains("no authority database configured"));
    }

    @Test
    public void projSemanticsVersionIsTheTargetRelease() {
        assertEquals("9.8.1", Proj.projSemanticsVersion());
    }

    // ------------------------------------------------------------------------ 2. databaseVersion()

    /** The first honesty requirement, stated as an assertion. */
    @Test
    public void databaseVersionRefusesToNameAVersionItCannotVerify() {
        assertFalse("there is no proj.db and the shipped dictionary carries no version stamp, so "
                        + "any string here would be a guess or a lie",
                Proj.databaseVersion().isPresent());

        DatabaseInfo info = Proj.databaseInfo();
        assertFalse(info.isDatabasePresent());
        assertFalse(info.version().isPresent());
        assertFalse(info.epsgVersion().isPresent());
    }

    /**
     * Refusing to give a version is only honest if the gap is explained somewhere a human will read
     * it. That is what {@link DatabaseInfo#vintageNote()} is for, and it has to name both numbers.
     */
    @Test
    public void databaseInfoExplainsTheVintageGapInProse() {
        DatabaseInfo info = Proj.databaseInfo();
        assertTrue("the legacy dictionary ships in proj4j-epsg, which is on the test classpath",
                info.dictionaryPresent());
        assertTrue(info.dictionaryOrigin().isPresent());

        String note = info.vintageNote();
        assertTrue("must say there is no proj.db: " + note, note.contains("No proj.db"));
        assertTrue("must name the dictionary's vintage: " + note, note.contains("v9.2"));
        assertTrue("must name what PROJ 9.8.1 has instead: " + note, note.contains("v12.029"));
        assertTrue("must say the files carry no version stamp: " + note,
                note.contains("no version stamp"));
        assertTrue("must say what is unavailable rather than approximated: " + note,
                note.contains("area of use"));
    }

    // ------------------------------------------------------------------------ 3. availableGrids()

    /** Everything listed as available really is, measured by a probe rather than assumed. */
    @Test
    public void availableGridsListsOnlyGridsThatWereActuallyFound() {
        List<GridInfo> available = Proj.availableGrids();
        assertNotNull(available);
        for (GridInfo g : available) {
            assertTrue(g.describe(), g.isAvailable());
            assertTrue("an available grid must name the resolver that found it",
                    g.resolverName().isPresent());
            assertTrue("an available grid must name its origin", g.origin().isPresent());
            assertFalse("an available grid has no skip reason", g.skipReason().isPresent());
        }
    }

    /**
     * The one grid {@code proj4j-epsg} actually ships. If this ever fails, the grid resolution chain
     * has stopped seeing the shipped resource -- which is exactly the silent condition that produced
     * the 95 m defect.
     */
    @Test
    public void theOneShippedGridIsReportedAsReachable() {
        Optional<GridInfo> g = Proj.grid("ntv1_can.dat");
        assertTrue(g.isPresent());
        assertTrue("proj4j-epsg ships proj4/nad/ntv1_can.dat: " + g.get().describe(),
                g.get().isAvailable());
        assertTrue(g.get().origin().get(), g.get().origin().get().contains("proj4/nad"));
        assertEquals(1113184L, g.get().sizeBytes().getAsLong());
    }

    /**
     * The second honesty requirement: declared and unreachable is reported as such, with a reason,
     * rather than being omitted from a list and thereby looking like it does not exist.
     *
     * <p>Which of the four grids {@code +datum=NAD27} declares are actually present depends on what
     * is on the classpath, and the point of this API is that a caller does not have to guess. So the
     * assertion is over every unreachable one rather than over a named file, and it insists that at
     * least one exists -- because a run in which they were all present would prove nothing here and
     * must not pass silently.
     */
    @Test
    public void declaredButUnreachableGridsAreReportedWithAReason() {
        List<GridInfo> declared = Proj.declaredGrids();
        assertFalse(declared.isEmpty());

        int unreachable = 0;
        for (GridInfo g : declared) {
            assertTrue(g.name() + " came from the datum table, so it is declared", g.isDeclared());
            assertTrue("every token in PROJ's datum table is @-optional and that must be recorded, "
                    + "not discarded: " + g.name(), g.isOptional());
            assertTrue(g.declaredBy().get(), g.declaredBy().get().startsWith("+datum="));
            if (g.isAvailable()) {
                assertTrue(g.resolverName().isPresent());
                assertFalse(g.skipReason().isPresent());
                assertTrue(g.describe(), g.describe().contains("REACHABLE"));
                continue;
            }
            unreachable++;
            assertTrue("an unreachable grid must say why", g.skipReason().isPresent());
            assertFalse(g.resolverName().isPresent());
            assertFalse(g.origin().isPresent());
            String described = g.describe();
            assertTrue(described, described.contains("DECLARED BUT UNREACHABLE"));
            assertTrue("must say what the @ prefix means: " + described,
                    described.contains("silently"));
        }
        assertTrue("no declared grid was unreachable, so this test proved nothing; the classpath "
                + "must have changed. Declared: " + declared, unreachable > 0);
    }

    /**
     * A URL is information for a human. It must never be presented as something that will happen.
     *
     * <p>{@code hawaii} is used because it cannot be made reachable: no CTABLE V2 form of it exists
     * upstream, so no legacy grid pack can contain it.
     */
    @Test
    public void aKnownUrlIsInformationNotAnAction() {
        GridInfo hawaii = Proj.grid("hawaii").get();
        assertEquals(Optional.of("us_noaa_hawaii.tif"), hawaii.modernName());
        assertEquals(Optional.of("https://cdn.proj.org/us_noaa_hawaii.tif"), hawaii.knownUrl());
        assertFalse("a grid reachable only over the network is not available, full stop",
                hawaii.isAvailable());
        assertFalse("core ships no network code", Proj.isNetworkEnabled());
        assertTrue(hawaii.describe(), hawaii.describe().contains("no network I/O"));
    }

    /**
     * Five of the seven US grids have no legacy form to ship at all, so their absence is not a
     * packaging oversight and the report says so instead of leaving a reader to guess.
     */
    @Test
    public void theFiveGeoTiffOnlyUsGridsSayWhyTheyCannotBeShipped() {
        for (String name : new String[]{"hawaii", "prvi", "stgeorge", "stlrnc", "stpaul"}) {
            GridInfo g = Proj.grid(name).get();
            assertTrue(name + " must carry the GeoTIFF-only note", g.note().isPresent());
            assertTrue(g.note().get(), g.note().get().contains("GeoTIFF-only"));
            assertFalse(name + " has no legacy form anywhere, so it cannot be reachable",
                    g.isAvailable());
        }
        // ...whereas conus and alaska do exist upstream in CTABLE V2 and carry no such note.
        assertFalse(Proj.grid("conus").get().note().isPresent());
        assertFalse(Proj.grid("alaska").get().note().isPresent());
    }

    /** {@code format()} is empty until the file has been read: a format from a name is a guess. */
    @Test
    public void formatIsNotGuessedFromAFileName() {
        assertFalse("listing a grid does not read it, so its format is unknown",
                Proj.grid("ntv1_can.dat").get().format().isPresent());
        // Reading it does establish the format, and then it is reported.
        GridInfo loaded = null;
        for (GridInfo g : Proj.createCrs("EPSG:4267").grids()) {
            if ("ntv1_can.dat".equals(g.name())) {
                loaded = g;
            }
        }
        assertNotNull(loaded);
        assertEquals(Optional.of("ntv1"), loaded.format());
    }

    // ------------------------------------------------- 4, 5, 6. isGeocentric/isAngular/axisOrder

    @Test
    public void isGeocentricIsTrueOnlyForAGeocentricCrs() {
        assertTrue(Proj.createCrs("+proj=geocent +datum=WGS84").isGeocentric());
        assertFalse(Proj.createCrs("EPSG:4326").isGeocentric());
        assertFalse(Proj.createCrs("EPSG:3857").isGeocentric());
    }

    @Test
    public void isAngularSeparatesDegreesFromMetres() {
        assertTrue("EPSG:4326 is degrees", Proj.createCrs("EPSG:4326").isAngular());
        assertFalse("EPSG:3857 is metres", Proj.createCrs("EPSG:3857").isAngular());
        assertFalse("a geocentric CRS is metres, not angles",
                Proj.createCrs("+proj=geocent +datum=WGS84").isAngular());
    }

    @Test
    public void theThreeKindsArePartitioned() {
        Crs geographic = Proj.createCrs("EPSG:4326");
        Crs projected = Proj.createCrs("EPSG:3857");
        Crs geocentric = Proj.createCrs("+proj=geocent +datum=WGS84");

        assertTrue(geographic.isGeographic() && !geographic.isProjected()
                && !geographic.isGeocentric());
        assertTrue(!projected.isGeographic() && projected.isProjected()
                && !projected.isGeocentric());
        assertTrue(!geocentric.isGeographic() && !geocentric.isProjected()
                && geocentric.isGeocentric());
    }

    @Test
    public void axisOrderIsProjSOwnThreeLetterEncoding() {
        String order = Proj.createCrs("EPSG:4326").axisOrder();
        assertEquals("enu", order);
        assertEquals(3, order.length());
        for (int i = 0; i < order.length(); i++) {
            assertTrue("must be a PROJ +axis= direction character: " + order,
                    "ewnsud".indexOf(order.charAt(i)) >= 0);
        }
    }

    // -------------------------------------------------------------------------------- 7. describe()

    @Test
    public void describeStatesTheDeploymentInFull() {
        String d = Proj.describe();
        assertNotNull(d);
        assertTrue(d, d.contains("PROJ semantics target: 9.8.1"));
        assertTrue("must state the metadata situation: " + d, d.contains("authority database   ="));
        assertTrue("must state the resolution chain: " + d,
                d.contains("working directory: NEVER CONSULTED"));
        assertTrue("must state that no environment variable is read: " + d,
                d.contains("environment variables: NONE READ"));
        assertTrue("must state the network position: " + d, d.contains("network: ABSENT"));
        assertTrue("must split the grid inventory into reachable and unreachable: " + d,
                d.contains("reachable now") && d.contains("UNREACHABLE"));
        assertTrue("must record the measured cost of a silently skipped grid: " + d,
                d.contains("95.573 m"));
        assertTrue("must explain why five of the seven US grids are absent: " + d,
                d.contains("GeoTIFF-only"));
        assertTrue("must state the default context: " + d, d.contains("axisOrderPolicy"));
        assertTrue("must state that no policy is settable from the environment: " + d,
                d.contains("settable from the environment: NO"));
    }

    @Test
    public void describeResolutionNamesEveryResolverAndItsEnumerability() {
        String d = Proj.describeResolution();
        assertTrue(d, d.contains("first match wins"));
        assertTrue("enumerability must be stated per resolver, so that an empty grid list is "
                + "never read as \"nothing installed\": " + d, d.contains("enumerable="));
        assertTrue(d, d.contains("classpath:proj4/nad/"));
    }

    // ---------------------------------------------------------------- the write-only descriptions

    /**
     * About a hundred human-readable projection descriptions have been in {@code Registry} since
     * 2009 with no way to read them; their only reader was a message that is now unreachable. This
     * is what they were for.
     */
    @Test
    public void projectionDescriptionsAreReadable() {
        assertEquals(Optional.of("Albers Equal Area"), Proj.projectionDescription("aea"));
        assertEquals(Optional.of("Azimuthal Equidistant"), Proj.projectionDescription("aeqd"));
        assertFalse(Proj.projectionDescription("no_such_projection").isPresent());

        List<ProjectionInfo> all = Proj.projections();
        assertTrue("the registry carries around a hundred descriptions, not a handful: "
                + all.size(), all.size() > 90);
        int described = 0;
        for (ProjectionInfo p : all) {
            if (p.description().isPresent()) {
                described++;
            }
        }
        assertEquals("every registered projection has a description", all.size(), described);
        assertTrue(Proj.supportedProjections().contains("lcc"));
        assertTrue(Proj.supportedProjections().contains("utm"));
    }

    /**
     * A CRS reports the {@code +proj=} name it was actually built from -- {@code utm}, not the
     * {@code tmerc} it is implemented by -- together with the registry's description of it.
     */
    @Test
    public void aCrsReportsItsProjectionDescription() {
        Crs utm = Proj.createCrs("EPSG:32633");
        assertEquals(Optional.of("utm"), utm.projectionName());
        assertTrue(utm.projectionDescription().isPresent());

        Crs lcc = Proj.createCrs("+proj=lcc +lat_1=33 +lat_2=45 +lat_0=39 +lon_0=-96 "
                + "+datum=NAD83");
        assertEquals(Optional.of("lcc"), lcc.projectionName());
        assertEquals(Optional.of("Lambert Conformal Conic"), lcc.projectionDescription());
        assertTrue(lcc.describe(), lcc.describe().contains("Lambert Conformal Conic"));
    }
}
