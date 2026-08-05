/*
 * Copyright 2026 the Proj4J contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package org.locationtech.proj4j.golden;

import org.junit.Test;

import java.io.File;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Regression tests for the four defects in
 * {@code core/src/test/java/org/locationtech/proj4j/proj/ProjectionGridRoundTripper.gridExtent} that
 * this probe derivation must not reproduce, plus the DMS parsing the registry dictionaries need.
 *
 * <p>Every test here names the defect it guards, because the point of the file is that these four
 * mistakes are easy to re-introduce and each one silently degrades the entire suite rather than
 * failing.
 */
public class ProbesTest {

    private static List<String> p(String projString) {
        return Arrays.asList(InputSet.split(projString));
    }

    /**
     * Defect 1. {@code gridExtent:123} seeds the maximum-latitude tracker with
     * {@code Double.MIN_VALUE}, which is {@code +4.9e-324} — the smallest positive subnormal, NOT the
     * most negative double. The guard at {@code :132} then fails for any all-negative-latitude CRS and
     * it falls back to a 10-degree box on the equator.
     */
    @Test
    public void doubleMinValueIsPositive() {
        // The trap itself, asserted so nobody "simplifies" the derivation back into it.
        assertTrue("Double.MIN_VALUE is +4.9e-324, not negative", Double.MIN_VALUE > 0.0);
        assertTrue(Double.MIN_VALUE < 1e-300);
    }

    @Test
    public void southernHemisphereCrsIsProbedInTheSouthernHemisphere() {
        // American Samoa, nad27:5300 -- lat_1 = lat_2 = lat_0 = -14d16, all negative.
        double[][] probes = Probes.derive(p("+proj=lcc +datum=NAD27 +lon_0=-170 "
                + "+lat_1=-14d16 +lat_2=-14d16 +lat_0=-14d16"));
        assertEquals(-14.0 - 16.0 / 60.0, probes[0][1], 1e-12);
        for (int i = 0; i < probes.length; i++) {
            assertTrue("probe " + i + " must be south of the equator, was " + probes[i][1],
                    probes[i][1] < 0.0);
        }

        // UTM zone 1 South: no latitude parameter at all, only the +south flag.
        double[][] utm = Probes.derive(p("+proj=utm +zone=1 +south +datum=WGS84 +units=m"));
        assertEquals("zone 1's central meridian", -177.0, utm[0][0], 1e-12);
        for (int i = 0; i < utm.length; i++) {
            assertTrue("+south must not be probed on the equator, was " + utm[i][1],
                    utm[i][1] < 0.0);
        }
    }

    /**
     * Defect 2. {@code updateLat:150} is {@code if (lat == 0.0) return;}, conflating an explicit
     * {@code +lat_0=0} with an absent one. Every UTM zone and every equatorial Mercator carries
     * {@code +lat_0=0}.
     */
    @Test
    public void explicitZeroLatitudeIsAValueNotAnAbsence() {
        // lat_0=0 present, lat_1/lat_2 absent: the span is zero, so the default half-height applies,
        // and the centre is 0 because that is what the definition says -- not because it defaulted.
        double[][] a = Probes.derive(p("+proj=tmerc +lat_0=0 +lon_0=173 +k=0.9996"));
        assertEquals(0.0, a[0][1], 0.0);

        // And a definition with lat_0=0 AND lat_1=60 must centre at 30, which the "0 means absent"
        // reading would put at 60.
        double[][] b = Probes.derive(p("+proj=lcc +lat_0=0 +lat_1=60 +lon_0=0"));
        assertEquals(30.0, b[0][1], 1e-12);
    }

    /**
     * Defect 3. {@code gridExtent:136} is {@code gridWidth = 2 * dlat} with no cap, so
     * {@code +lat_1=-70 +lat_2=70} produces a 280-degree-tall box whose corners are past both poles.
     */
    @Test
    public void boxHeightIsBounded() {
        double[][] wide = Probes.derive(p("+proj=lcc +lat_1=-70 +lat_2=70 +lon_0=0"));
        for (int i = 0; i < wide.length; i++) {
            assertTrue("latitude must stay inside +/-" + Probes.LAT_LIMIT + ", was " + wide[i][1],
                    Math.abs(wide[i][1]) <= Probes.LAT_LIMIT);
        }
        double span = wide[3][1] - wide[1][1];
        assertTrue("half-height must be capped at " + Probes.MAX_LAT_HALF + ", box was " + span,
                span <= 2 * Probes.MAX_LAT_HALF + 1e-9);
    }

    /**
     * Defect 4. {@code gridExtent:140-143} uses the same half-width on both axes, so a box that is
     * 555 km tall is 190 km wide at 70 degrees north. Equal ground distance needs a
     * {@code 1/cos(lat)} factor.
     */
    @Test
    public void longitudeHalfWidthIsCosineScaled() {
        double[][] equator = Probes.derive(p("+proj=merc +lat_0=0 +lon_0=0"));
        double[][] high = Probes.derive(p("+proj=tmerc +lat_0=70 +lon_0=0"));
        double wEq = equator[2][0] - equator[1][0];
        double wHigh = high[2][0] - high[1][0];
        assertTrue("the high-latitude box must be wider in longitude (" + wHigh + " vs " + wEq + ")",
                wHigh > wEq * 2.0);
        // And bounded, so a polar CRS does not get an infinite width.
        double[][] polar = Probes.derive(p("+proj=stere +lat_0=90 +lon_0=0"));
        assertTrue(Math.abs(polar[2][0] - polar[1][0]) <= 2 * Probes.MAX_LON_HALF + 1e-9);
        for (int i = 0; i < polar.length; i++) {
            assertTrue(polar[i][0] >= -180.0 && polar[i][0] < 180.0);
        }
    }

    @Test
    public void dmsFormsFromTheDictionariesParse() {
        assertEquals(-85.0 - 50.0 / 60.0, Angles.parseDegrees("-85d50"), 1e-12);
        assertEquals(30.5, Angles.parseDegrees("30d30"), 1e-12);
        assertEquals(46.0 + 57.0 / 60.0 + 8.660 / 3600.0, Angles.parseDegrees("46d57'8.660\"N"), 1e-12);
        assertEquals(7.0 + 26.0 / 60.0 + 22.5 / 3600.0, Angles.parseDegrees("7d26'22.500\"E"), 1e-12);
        assertEquals(6.0, Angles.parseDegrees("6d0E"), 1e-12);
        assertEquals(-14.0 - 16.0 / 60.0, Angles.parseDegrees("-14d16"), 1e-12);
        assertEquals(-83.0 - 10.0 / 60.0, Angles.parseDegrees("83d10'W"), 1e-12);
        assertEquals(30.0, Angles.parseDegrees("0.5235987755982988R"), 1e-9);
        assertEquals(51.0, Angles.parseDegrees("51"), 0.0);
        assertEquals(-0.5, Angles.parseDegrees("-.5"), 0.0);
        // Rejected, so a nonsense token cannot become a probe:
        assertTrue(Double.isNaN(Angles.parseDegrees("")));
        assertTrue(Double.isNaN(Angles.parseDegrees("abc")));
        assertTrue(Double.isNaN(Angles.parseDegrees("NaN")));
        assertTrue(Double.isNaN(Angles.parseDegrees("Infinity")));
        assertTrue(Double.isNaN(Angles.parseDegrees("0x1p3")));
        assertTrue(Double.isNaN(Angles.parseDegrees("1e5")));
    }

    @Test
    public void longitudeWrapIsTotalAndUsesFloorNotALoop() {
        assertEquals(0.0, Probes.wrapLon(360.0), 0.0);
        assertEquals(-179.0, Probes.wrapLon(181.0), 0.0);
        assertEquals(179.0, Probes.wrapLon(-181.0), 0.0);
        assertEquals(-180.0, Probes.wrapLon(180.0), 0.0);
        // 1e18 radians is the input that makes ProjectionMath.normalizeLongitude spin ~1.6e17 times.
        double w = Probes.wrapLon(1e18);
        assertTrue(w >= -180.0 && w < 180.0);
    }

    /**
     * The synthetic matrix's shape is part of the key set, so a change to it must be a deliberate,
     * reviewed act followed by a probe and baseline regeneration -- not something that drifts.
     */
    @Test
    public void syntheticMatrixShapeIsPinned() {
        assertEquals("all 188 PROJ 9.8.1 PROJ_HEAD names", 188, InputSet.PROJ_NAMES.length);
        assertEquals("no duplicate +proj= candidates",
                188, new java.util.TreeSet<String>(Arrays.asList(InputSet.PROJ_NAMES)).size());
        assertEquals(47, InputSet.MODIFIERS.length);
        assertEquals(49, InputSet.ELLIPSOIDS.length);
        assertEquals(6, InputSet.HOSTS.length);
        // 188 proj names + 47 modifiers x 6 hosts + 49 ellipsoids
        assertEquals(188 + 47 * 6 + 49, InputSet.syntheticSpecs().size());
        // and every key must be unique, or the generator's duplicate check would abort the run
        java.util.Set<String> keys = new java.util.TreeSet<String>();
        List<String[]> specs = InputSet.syntheticSpecs();
        for (int i = 0; i < specs.size(); i++) {
            assertTrue("duplicate synthetic key " + specs.get(i)[0], keys.add(specs.get(i)[0]));
        }
    }

    /** The dictionaries are the input set; a change to their size must be noticed immediately. */
    @Test
    public void registryDictionaryCountsAreUnchanged() throws Exception {
        int total = 0;
        for (int i = 0; i < RegistryDict.AUTHORITIES.length; i++) {
            List<RegistryDict.Def> defs = RegistryDict.read(RegistryDict.AUTHORITIES[i]);
            assertEquals(RegistryDict.AUTHORITIES[i] + " def count",
                    RegistryDict.EXPECTED_COUNTS[i], defs.size());
            total += defs.size();
        }
        assertEquals(RegistryDict.EXPECTED_TOTAL, total);
    }

    @Test
    public void committedProbeFileCoversEveryInputKey() throws Exception {
        File goldenDir = new File(System.getProperty("golden.dir", "."));
        File probesFile = new File(goldenDir, "probes.tsv");
        org.junit.Assume.assumeTrue("no probes.tsv at " + probesFile, probesFile.isFile());
        Probes.Table t = Probes.read(probesFile);

        List<RegistryDict.Def> defs = RegistryDict.readAll();
        for (int i = 0; i < defs.size(); i++) {
            assertTrue("no probe for REG/" + defs.get(i).key(),
                    t.has(GoldenFormat.SECTION_REG, defs.get(i).key()));
        }
        List<InputSet.Case> syn = InputSet.synthetic();
        for (int i = 0; i < syn.size(); i++) {
            assertTrue("no probe for SYN/" + syn.get(i).key,
                    t.has(GoldenFormat.SECTION_SYN, syn.get(i).key));
        }
        List<InputSet.Case> pairs = InputSet.pairs(new File(goldenDir, "pairs.tsv"),
                InputSet.index(defs));
        assertEquals("curated pair count", 200, pairs.size());
        for (int i = 0; i < pairs.size(); i++) {
            assertTrue("no probe for PAIR/" + pairs.get(i).key,
                    t.has(GoldenFormat.SECTION_PAIR, pairs.get(i).key));
        }
    }

    /** All 25 source/target {@code Datum.TYPE_*} combinations must be represented. */
    @Test
    public void curatedPairsCoverEveryDatumTypeCombination() throws Exception {
        File goldenDir = new File(System.getProperty("golden.dir", "."));
        File pairsFile = new File(goldenDir, "pairs.tsv");
        org.junit.Assume.assumeTrue("no pairs.tsv at " + pairsFile, pairsFile.isFile());
        java.util.Set<String> combos = new java.util.TreeSet<String>();
        java.io.BufferedReader r = GoldenFormat.reader(pairsFile);
        try {
            r.readLine();
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty()) continue;
                String[] c = GoldenFormat.split(line, 5);
                combos.add(c[3] + "-" + c[4]);
            }
        } finally {
            r.close();
        }
        assertEquals("expected all 5x5 datum type combinations, got " + combos, 25, combos.size());
    }
}
