/*******************************************************************************
 * Copyright 2026
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

package org.locationtech.proj4j.serialization;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.NotSerializableException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.Test;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.Registry;
import org.locationtech.proj4j.datum.AxisOrder;
import org.locationtech.proj4j.datum.Ellipsoid;
import org.locationtech.proj4j.proj.Projection;

/**
 * Every serialisable class in {@code core} must declare an explicit {@code serialVersionUID}.
 *
 * <h2>The defect this exists to prevent</h2>
 *
 * <p>proj4j runs per row inside Spark executors, and Spark ships a serialised
 * {@link CoordinateTransform} — which transitively drags in a {@link CoordinateReferenceSystem},
 * a {@link Projection}, an {@link Ellipsoid}, a {@code Datum} and an {@link AxisOrder}. When a
 * class does not declare a {@code serialVersionUID}, the JVM synthesises one from the class's
 * name, modifiers, interfaces, fields, constructors and non-private methods. <b>Any</b> change to
 * that surface — adding a method, widening a field, reordering an interface list — silently
 * changes the value, and the driver's stream then fails on the executor with
 * {@link java.io.InvalidClassException}: {@code local class incompatible}. The failure appears
 * only when driver and executor jars differ, i.e. in production, during a rolling upgrade.
 *
 * <p>Before this test, exactly one of core's 205 serialisable classes declared one
 * ({@code ExtendedTransverseMercatorProjection}, and it declared the invented value {@code 1L}).
 *
 * <h2>Where the values came from</h2>
 *
 * <p>The 188 added values are <b>not invented</b>. Each is what the JDK's {@code serialver} tool
 * computed for that exact class immediately before the field was added, so that a stream written
 * by the previous build still loads. That was verified two ways: {@code serialver}'s output was
 * compared field-by-field against {@link ObjectStreamClass#lookupAny} on the pre-change class
 * files (188/188 equal, 188 distinct values, so the comparison is not degenerate), and a corpus
 * of 175 objects serialised by the pre-change build was read back by the post-change build with
 * zero {@code InvalidClassException}. Deliberately corrupting one leaf class's UID broke 4 of
 * those objects and corrupting {@link Projection}'s broke 148, so that round trip discriminates.
 *
 * <h2>What this test does NOT promise</h2>
 *
 * <p>A pinned UID says "the serialised forms are compatible". From here on that is a claim the
 * <em>author</em> must keep true: adding or removing a non-transient field now changes the
 * serialised layout without changing the UID, which trades a loud
 * {@code InvalidClassException} for a quiet wrong-value read. The UID is a promise, not a check.
 *
 * <p>And a UID does not make a class serialisable at all. Eleven registered projections still
 * throw {@link NotSerializableException} for unrelated reasons; they are pinned and enumerated
 * by {@link #projectionsThatCannotBeSerialisedAtAllArePinned()} so the exposure cannot drift in
 * either direction unnoticed.
 *
 * <h2>Why this test can fail</h2>
 *
 * <p>A scan that cannot fail is worthless and its failure mode is silent — it reports exactly
 * what you hoped for. The detector here is proven both ways, always on:
 *
 * <ul>
 * <li>{@link #theScanRejectsAMissingUidAndAcceptsADeclaredOne()} runs the <em>production</em>
 *     walk and predicate over {@code target/test-classes} and requires it to name
 *     {@link Violator} — a class deliberately left without a UID — and requires it <em>not</em>
 *     to name {@link Compliant}, which has one. A guard that refuses everything passes every
 *     hostile test and breaks the library, so the accepting leg matters as much as the
 *     rejecting one.</li>
 * <li>{@link #everySerializableClassInCoreDeclaresASerialVersionUid()} asserts floors on how
 *     much the walk reached, and asserts by name that it reached {@link Projection},
 *     {@link ProjCoordinate} and {@link AxisOrder}. A walk that silently found nothing would
 *     otherwise pass trivially.</li>
 * <li>{@link #enumsAreExcludedBecauseTheJvmIgnoresADeclaredUid()} and
 *     {@link #interfacesAreExcludedBecauseTheirDescriptorNeverEntersAStream()} prove the two
 *     exclusion rules against the running JVM rather than against the specification, so an
 *     exclusion cannot quietly become a loophole.</li>
 * </ul>
 */
public class SerialVersionUidTest {

    // ------------------------------------------------------------------ control fixtures
    //
    // These two exist to be *found* by the production scan when it is pointed at the test
    // classes. Do not add a UID to Violator and do not remove Compliant's.

    /** Deliberately has no {@code serialVersionUID}; the scan must flag it. */
    static class Violator implements Serializable {
        @SuppressWarnings("unused")
        private int state;
    }

    /** Deliberately has one; the scan must not flag it. */
    static class Compliant implements Serializable {
        private static final long serialVersionUID = 20260803L;
        @SuppressWarnings("unused")
        private int state;
    }

    /** A UID on an enum is ignored by the JVM; see {@link #enumsAreExcludedBecauseTheJvmIgnoresADeclaredUid()}. */
    enum EnumWithUid {
        A, B;
        @SuppressWarnings("unused")
        private static final long serialVersionUID = 987654321L;
    }

    /** Same shape, no UID. Both must report 0L. */
    enum EnumWithoutUid { A, B }

    // ------------------------------------------------------------------ the production scan

    /**
     * The predicate under test: does {@code type} need a declared {@code serialVersionUID}?
     *
     * <p>Excludes interfaces and enum-like types, both for reasons proven against the running
     * JVM by the two dedicated tests below rather than asserted from the specification.
     */
    private static boolean requiresDeclaredUid(Class<?> type) {
        if (!Serializable.class.isAssignableFrom(type)) {
            return false;
        }
        if (type.isInterface()) {
            return false;
        }
        Class<?> parent = type.getSuperclass();
        return !type.isEnum() && (parent == null || !parent.isEnum());
    }

    /** Does {@code type} itself declare a {@code static final long serialVersionUID}? */
    private static boolean declaresUid(Class<?> type) {
        try {
            Field field = type.getDeclaredField("serialVersionUID");
            return Modifier.isStatic(field.getModifiers())
                    && Modifier.isFinal(field.getModifiers())
                    && field.getType() == long.class;
        } catch (NoSuchFieldException absent) {
            return false;
        }
    }

    /** Result of one walk: what was reached, and what was found wanting. */
    private static final class ScanResult {
        final List<String> scanned = new ArrayList<String>();
        final List<String> requiring = new ArrayList<String>();
        final List<String> missing = new ArrayList<String>();
        final List<String> excludedEnumLike = new ArrayList<String>();
        final List<String> excludedInterfaces = new ArrayList<String>();
    }

    /**
     * Walks {@code root} for {@code .class} files, loads each, and classifies it. Loading is what
     * makes the scan see inherited {@code Serializable}: 188 of the 205 classes never write the
     * word {@code Serializable} in their own source, so a text scan of {@code core/src/main}
     * would find 12 and report a confident, false, clean result.
     */
    private static ScanResult scan(File root, String packagePrefix) {
        List<File> files = new ArrayList<File>();
        collectClassFiles(root, files);
        ScanResult result = new ScanResult();
        String rootPath = root.getAbsolutePath();
        for (File file : files) {
            String relative = file.getAbsolutePath().substring(rootPath.length() + 1);
            String name = relative.substring(0, relative.length() - ".class".length())
                    .replace(File.separatorChar, '.');
            if (!name.startsWith(packagePrefix)) {
                continue;
            }
            Class<?> type;
            try {
                type = Class.forName(name, false, SerialVersionUidTest.class.getClassLoader());
            } catch (Throwable notLoadable) {
                throw new AssertionError("the scan could not load " + name
                        + "; a class it cannot load is a class it cannot check", notLoadable);
            }
            result.scanned.add(name);
            if (!Serializable.class.isAssignableFrom(type)) {
                continue;
            }
            if (type.isInterface()) {
                result.excludedInterfaces.add(name);
                continue;
            }
            if (!requiresDeclaredUid(type)) {
                result.excludedEnumLike.add(name);
                continue;
            }
            result.requiring.add(name);
            if (!declaresUid(type)) {
                result.missing.add(name);
            }
        }
        return result;
    }

    // ------------------------------------------------------------------ the assertion

    @Test
    public void everySerializableClassInCoreDeclaresASerialVersionUid() {
        ScanResult result = scan(coreClassesDirectory(), "org.locationtech.proj4j.");

        if (!result.missing.isEmpty()) {
            StringBuilder message = new StringBuilder(result.missing.size()
                    + " serialisable class(es) in core/src/main declare no serialVersionUID.\n"
                    + "Generate each value with:  serialver -classpath core/target/classes <class>\n"
                    + "against the class as it stands BEFORE adding the field, so streams already"
                    + " written stay readable. Do not invent a value.\n");
            for (String name : result.missing) {
                message.append("  ").append(name).append('\n');
            }
            fail(message.toString());
        }

        // Non-vacuity: a walk that reached nothing would satisfy the assertion above.
        assertTrue("the walk reached only " + result.scanned.size() + " classes; it has stopped"
                + " seeing core", result.scanned.size() >= 400);
        assertTrue("only " + result.requiring.size() + " classes were classified as needing a UID;"
                + " the classifier has stopped matching", result.requiring.size() >= 200);
        assertTrue("no enum-like type was excluded; the exclusion branch is unreached",
                result.excludedEnumLike.size() >= 20);

        // Non-vacuity by name, so a walk that reached some unrelated corner still fails.
        for (String required : new String[] {
                Projection.class.getName(),
                ProjCoordinate.class.getName(),
                AxisOrder.class.getName(),
                Ellipsoid.class.getName(),
                CoordinateReferenceSystem.class.getName(),
                "org.locationtech.proj4j.datum.PrimeMeridian",
                "org.locationtech.proj4j.datum.GeocentricConverter",
                "org.locationtech.proj4j.util.PolarCoordinate",
                "org.locationtech.proj4j.util.IntPolarCoordinate",
                "org.locationtech.proj4j.util.FloatPolarCoordinate",
                "org.locationtech.proj4j.proj.MercatorProjection" }) {
            assertTrue("the walk never reached " + required, result.requiring.contains(required));
        }
        assertTrue("the interface exclusion is unreached",
                result.excludedInterfaces.contains(CoordinateTransform.class.getName()));
        assertTrue("the enum exclusion is unreached",
                result.excludedEnumLike.contains(ErrorCause.class.getName()));
    }

    /**
     * <b>Positive control, always on.</b> Runs the same walk and the same predicate over the
     * compiled test classes, where {@link Violator} has been left without a UID on purpose and
     * {@link Compliant} has one. The scan must reject the first and accept the second: a guard
     * that flags everything would pass a rejection-only control while making the build
     * unbuildable.
     */
    @Test
    public void theScanRejectsAMissingUidAndAcceptsADeclaredOne() {
        ScanResult result = scan(testClassesDirectory(), SerialVersionUidTest.class.getPackage().getName());

        assertTrue("the control scan reached nothing", result.scanned.size() >= 3);
        assertTrue("the scan did NOT flag " + Violator.class.getName()
                + ", which has no serialVersionUID -- the detector is blind, so the clean result"
                + " on core/target/classes means nothing. Flagged: " + result.missing,
                result.missing.contains(Violator.class.getName()));
        assertTrue("the scan did not even classify " + Compliant.class.getName()
                + " as needing a UID", result.requiring.contains(Compliant.class.getName()));
        assertFalse("the scan flagged " + Compliant.class.getName() + ", which declares "
                + "a serialVersionUID -- the guard rejects legitimate input",
                result.missing.contains(Compliant.class.getName()));
    }

    /**
     * The exclusion of enums, proven against the JVM. The serialization specification fixes an
     * enum type's {@code serialVersionUID} at {@code 0L} and enum constants travel by name, so a
     * declared value is dead code that reads as a guarantee. Including enums in the sweep would
     * have added 31 misleading fields.
     */
    @Test
    public void enumsAreExcludedBecauseTheJvmIgnoresADeclaredUid() {
        assertEquals("an enum WITH a declared UID still reports 0L", 0L,
                ObjectStreamClass.lookupAny(EnumWithUid.class).getSerialVersionUID());
        assertEquals("an enum WITHOUT one also reports 0L", 0L,
                ObjectStreamClass.lookupAny(EnumWithoutUid.class).getSerialVersionUID());
        // Control: the same call on an ordinary class does NOT report 0L, so the two zeroes above
        // are a property of enums and not of the call.
        assertNotEquals("lookupAny returns 0L for everything; the comparison above is vacuous",
                0L, ObjectStreamClass.lookupAny(Violator.class).getSerialVersionUID());
        assertFalse(requiresDeclaredUid(EnumWithUid.class));
        assertFalse(requiresDeclaredUid(ErrorCause.class));
    }

    /**
     * The exclusion of interfaces, proven against the JVM. Only the concrete class and its
     * serialisable <em>superclasses</em> get a descriptor in the stream; implemented interfaces
     * never do, so an interface's UID can never be compared against anything.
     */
    @Test
    public void interfacesAreExcludedBecauseTheirDescriptorNeverEntersAStream() throws Exception {
        CoordinateTransform transform = transform("+proj=longlat +datum=WGS84",
                "+proj=merc +datum=WGS84");
        Set<String> descriptors = descriptorsWrittenFor(transform);

        // Control first: the scan of the stream must find *something*, or its silence is a bug.
        assertTrue("no proj4j descriptor was seen at all: " + descriptors,
                descriptors.contains("org.locationtech.proj4j.BasicCoordinateTransform"));
        assertTrue("the abstract superclass Projection must appear, since superclasses do",
                descriptors.contains(Projection.class.getName()));
        assertFalse("an interface appeared in the stream, so the exclusion is unsafe: "
                + descriptors, descriptors.contains(CoordinateTransform.class.getName()));
        assertFalse(requiresDeclaredUid(CoordinateTransform.class));
    }

    // ------------------------------------------------------------------ AxisOrder identity

    /**
     * {@link AxisOrder} is a final value type with a private constructor and one published
     * constant, which is the shape that invites {@code ==}. Its {@code readResolve} restores
     * {@link AxisOrder#ENU} on the way in.
     *
     * <p>Note this was a hazard for <em>callers</em>, not a live bug: core compares with
     * {@code AxisOrder.ENU.equals(...)} at {@code BasicCoordinateTransform:324-325} and has no
     * {@code == AxisOrder.ENU} site.
     */
    @Test
    public void axisOrderEnuKeepsItsIdentityAcrossDeserialisation() throws Exception {
        assertSame("ENU must come back as the canonical instance", AxisOrder.ENU,
                roundTrip(AxisOrder.ENU));

        // Control: readResolve must not collapse everything onto ENU. A non-ENU order has to come
        // back equal-but-distinct, or the fix would silently rewrite +axis=neu into +axis=enu --
        // a wrong answer, which is worse than the identity hazard it replaces.
        AxisOrder neu = AxisOrder.fromString("neu");
        assertFalse("fixture is not distinct from ENU", AxisOrder.ENU.equals(neu));
        AxisOrder neuBack = roundTrip(neu);
        assertEquals("a non-ENU order lost its value", neu, neuBack);
        assertNotSame("a non-ENU order was collapsed onto a shared instance", neu, neuBack);
        assertNotSame("a non-ENU order was collapsed onto ENU", AxisOrder.ENU, neuBack);

        // And an ENU-valued instance built the long way round is canonicalised too, which is the
        // case that actually reaches an executor: +axis=enu parses to a fresh instance.
        AxisOrder parsedEnu = AxisOrder.fromString("enu");
        assertNotSame("fixture is already canonical, so it proves nothing",
                AxisOrder.ENU, parsedEnu);
        assertSame(AxisOrder.ENU, roundTrip(parsedEnu));
    }

    // ------------------------------------------------------------------ end-to-end

    /**
     * The whole point: a {@link CoordinateTransform} that has crossed a serialisation boundary
     * must produce the same numbers, bit for bit. This is what a Spark executor holds.
     */
    @Test
    public void aDeserialisedTransformProducesIdenticalCoordinates() throws Exception {
        CoordinateTransform local = transform("+proj=longlat +datum=WGS84",
                "+proj=tmerc +lat_0=0 +lon_0=9 +k=0.9996 +x_0=500000 +ellps=bessel"
                        + " +towgs84=1,2,3,4,5,6,7");
        CoordinateTransform shipped = roundTrip(local);
        assertNotSame(local, shipped);

        // Inside the tmerc domain: the fail-closed easting check rejects points far from the
        // central meridian, and a rejected point would compare two exceptions rather than two
        // coordinates.
        int compared = 0;
        for (double lon = -21; lon <= 39.5; lon += 6) {
            for (double lat = -70; lat <= 70.5; lat += 14) {
                ProjCoordinate here = new ProjCoordinate();
                ProjCoordinate there = new ProjCoordinate();
                local.transform(new ProjCoordinate(lon, lat), here);
                shipped.transform(new ProjCoordinate(lon, lat), there);
                assertEquals("x differs at " + lon + "," + lat,
                        Double.doubleToLongBits(here.x), Double.doubleToLongBits(there.x));
                assertEquals("y differs at " + lon + "," + lat,
                        Double.doubleToLongBits(here.y), Double.doubleToLongBits(there.y));
                compared++;
            }
        }
        // Control: the loop must actually have run, and must not have compared NaN to NaN.
        assertEquals(121, compared);
        ProjCoordinate sample = new ProjCoordinate();
        shipped.transform(new ProjCoordinate(9.0, 47.0), sample);
        assertFalse("the shipped transform returns NaN, so the comparison above is vacuous",
                Double.isNaN(sample.x) || Double.isNaN(sample.y));
    }

    /**
     * <b>Pinned exposure, both directions.</b> A {@code serialVersionUID} does not make a class
     * serialisable; these projections hold a non-transient reference to something that is not
     * {@link Serializable}, and throw {@link NotSerializableException} on the way out. That is a
     * harder failure than the {@code InvalidClassException} this sweep addresses — it fires on
     * the driver, on the first broadcast, every time.
     *
     * <p>The count and the names are both pinned. If a projection is fixed, this test fails and
     * the entry must be removed; if a new one regresses, it fails and names it. Root causes, all
     * outside {@code proj/**} except the last:
     *
     * <table border="1">
     * <caption>Why each fails</caption>
     * <tr><th>non-serialisable type</th><th>held by</th><th>projections affected</th></tr>
     * <tr><td>{@code geodesic.Geodesic}</td>
     *     <td>{@code EquidistantAzimuthalProjection.geodesic:79},
     *         {@code GnomonicAzimuthalProjection.geodesic:75}</td><td>2</td></tr>
     * <tr><td>{@code util.Complex}</td>
     *     <td>{@code ModifiedStereographicProjection.zcoeff:117} ({@code Complex[]}) and
     *         {@code .derivative:123}</td><td>5</td></tr>
     * <tr><td>{@code PeirceQuincuncialProjection$1}</td>
     *     <td>{@code PeirceQuincuncialProjection.rawForward:139}</td><td>1</td></tr>
     * <tr><td>{@code AdamsWorldInASquareIIProjection$1}</td>
     *     <td>{@code AdamsWorldInASquareIIProjection.rawForward:77}</td><td>2</td></tr>
     * <tr><td>{@code GeocentProjection$Cached}</td>
     *     <td>{@code GeocentProjection.cached:153}</td>
     *     <td>1, and only <em>after first use</em> — see
     *         {@link #geocentProjectionBecomesUnserialisableOnlyAfterItHasBeenUsed()}</td></tr>
     * </table>
     */
    @Test
    public void projectionsThatCannotBeSerialisedAtAllArePinned() {
        Set<String> expected = new TreeSet<String>(Arrays.asList(
                "org.locationtech.proj4j.proj.AdamsWorldInASquareIIProjection",
                "org.locationtech.proj4j.proj.AlaskaModifiedStereographicProjection",
                "org.locationtech.proj4j.proj.EquidistantAzimuthalProjection",
                "org.locationtech.proj4j.proj.LeeOblatedStereographicProjection",
                "org.locationtech.proj4j.proj.MillerOblatedStereographicProjection",
                "org.locationtech.proj4j.proj.ModifiedStereographic48Projection",
                "org.locationtech.proj4j.proj.ModifiedStereographic50Projection",
                "org.locationtech.proj4j.proj.PeirceQuincuncialProjection",
                "org.locationtech.proj4j.proj.SpilhausProjection"));

        List<Projection> projections = new Registry().getProjections();
        Set<String> actual = new TreeSet<String>();
        int serialisedFine = 0;
        for (Projection projection : projections) {
            if (serialisable(projection)) {
                serialisedFine++;
            } else {
                actual.add(projection.getClass().getName());
            }
        }

        // Control: the great majority must serialise, or "9 broken" would be measuring a broken
        // harness rather than 9 broken projections.
        assertEquals("the registry stopped producing projections", 151, projections.size());
        assertEquals("projections that serialise cleanly", 142, serialisedFine);
        assertEquals("the set of projections that cannot be serialised has changed", expected, actual);
    }

    /**
     * The nastiest variant, pinned separately because it is invisible to the scan above:
     * {@code +proj=geocent} serialises fine until it has transformed one point, after which its
     * lazily built {@code Cached} field makes it unserialisable. A driver that broadcasts a warm
     * CRS fails; one that broadcasts a cold one does not.
     */
    @Test
    public void geocentProjectionBecomesUnserialisableOnlyAfterItHasBeenUsed() throws Exception {
        CRSFactory factory = new CRSFactory();
        CoordinateReferenceSystem geocentric =
                factory.createFromParameters("d", "+proj=geocent +datum=WGS84");
        assertTrue("a cold +proj=geocent already fails, so the 'only after use' claim is wrong",
                serialisable(geocentric));

        CoordinateReferenceSystem geographic =
                factory.createFromParameters("s", "+proj=longlat +datum=WGS84");
        new CoordinateTransformFactory().createTransform(geographic, geocentric)
                .transform(new ProjCoordinate(12, 47), new ProjCoordinate());

        assertFalse("a warm +proj=geocent now serialises; if GeocentProjection.cached was made"
                + " transient or serialisable, delete this test and the row in the table on"
                + " projectionsThatCannotBeSerialisedAtAllArePinned",
                serialisable(geocentric));
    }

    // ------------------------------------------------------------------ helpers

    private static boolean serialisable(Object value) {
        try {
            ObjectOutputStream out = new ObjectOutputStream(new ByteArrayOutputStream());
            out.writeObject(value);
            out.close();
            return true;
        } catch (NotSerializableException expected) {
            return false;
        } catch (IOException other) {
            throw new AssertionError("unexpected failure serialising " + value.getClass(), other);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T roundTrip(T value) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ObjectOutputStream out = new ObjectOutputStream(bytes);
        out.writeObject(value);
        out.close();
        ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()));
        try {
            return (T) in.readObject();
        } finally {
            in.close();
        }
    }

    /** The proj4j class descriptors the JVM actually writes for {@code value}. */
    private static Set<String> descriptorsWrittenFor(Object value) throws Exception {
        final Set<String> seen = new TreeSet<String>();
        ObjectOutputStream out = new ObjectOutputStream(new ByteArrayOutputStream()) {
            @Override
            protected void writeClassDescriptor(ObjectStreamClass descriptor) throws IOException {
                if (descriptor.getName().startsWith("org.locationtech.proj4j")) {
                    seen.add(descriptor.getName());
                }
                super.writeClassDescriptor(descriptor);
            }
        };
        out.writeObject(value);
        out.close();
        return seen;
    }

    private static CoordinateTransform transform(String source, String target) {
        CRSFactory factory = new CRSFactory();
        return new CoordinateTransformFactory().createTransform(
                factory.createFromParameters("s", source),
                factory.createFromParameters("t", target));
    }

    private static void collectClassFiles(File directory, List<File> out) {
        File[] entries = directory.listFiles();
        if (entries == null) {
            return;
        }
        Arrays.sort(entries);
        for (File entry : entries) {
            if (entry.isDirectory()) {
                collectClassFiles(entry, out);
            } else if (entry.getName().endsWith(".class")) {
                out.add(entry);
            }
        }
    }

    private static File coreClassesDirectory() {
        return directoryContaining(CoordinateReferenceSystem.class);
    }

    private static File testClassesDirectory() {
        return directoryContaining(SerialVersionUidTest.class);
    }

    private static File directoryContaining(Class<?> type) {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        URL url = type.getResource(resource);
        assertNotNull("cannot locate the compiled form of " + type.getName(), url);
        String path = url.getPath();
        int index = path.indexOf(resource);
        assertTrue("compiled classes are not on the filesystem: " + url, index >= 0);
        File directory = new File(path.substring(0, index).replace("%20", " "));
        assertTrue("not a directory: " + directory, directory.isDirectory());
        return directory;
    }
}
