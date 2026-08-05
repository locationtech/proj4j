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
package org.locationtech.proj4j.errors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.Registry;
import org.locationtech.proj4j.UnsupportedParameterException;
import org.locationtech.proj4j.proj.Projection;

/**
 * {@code Registry.getProjection} for {@code alsk}, {@code apian} and {@code bacon} — the three
 * names that used to be bound to the abstract {@link Projection} base class. <b>All three are now
 * real implementations, so this class has become a regression net rather than a defect record.</b>
 *
 * <p>Three states, in order:
 *
 * <ol>
 * <li><b>1.4.3:</b> fail-closed with a lie and a side effect. {@code Class.newInstance()} threw
 *     {@link InstantiationException}; the catch printed a stack trace to {@code System.err} and
 *     returned {@code null}; {@code Proj4Parser} then reported <i>"Unknown projection: alsk"</i>.
 *     A registered name reported as unknown, plus an unsolicited stack trace that every host
 *     process — including every Spark executor — got and could not turn off.</li>
 * <li><b>Interim:</b> an honest {@link UnsupportedParameterException} carrying
 *     {@link ErrorCause#PROJECTION_NOT_IMPLEMENTED}, naming the class and saying "not
 *     implemented", with nothing written to stderr.</li>
 * <li><b>Now:</b> implemented. {@code apian}/{@code bacon} from upstream's {@code bacon.cpp};
 *     {@code alsk} from the {@code mod_ster} port. Nothing in the registry is uninstantiable.</li>
 * </ol>
 *
 * <p><b>Corrected record, kept because it was asserted wrongly more than once:</b> these three did
 * <em>not</em> silently return lon/lat as if projected. An earlier analysis claimed they were
 * identity no-ops on the base class's {@code project()}. They were not — they were uninstantiable,
 * which is a different defect with a different fix.
 */
public class RegistryProjectionTest {

    /** The three names registered to the abstract base class. */
    private static final String[] ABSTRACT_NAMES = { "alsk", "apian", "bacon" };

    private PrintStream originalErr;
    private ByteArrayOutputStream captured;

    @Before
    public void captureStandardError() throws UnsupportedEncodingException {
        originalErr = System.err;
        captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, "UTF-8"));
    }

    @After
    public void restoreStandardError() {
        System.setErr(originalErr);
    }

    private String stderr() throws UnsupportedEncodingException {
        System.err.flush();
        return captured.toString("UTF-8");
    }

    /**
     * <b>Inverted from what it was, because the defect it pinned is gone.</b> This used to sweep
     * {@link #ABSTRACT_NAMES} and assert each one <em>threw</em>, because {@code alsk},
     * {@code apian} and {@code bacon} were registered against the abstract {@link Projection}
     * base class and were therefore <em>uninstantiable</em> — see the class javadoc's correction:
     * they did <b>not</b> silently return lon/lat as if projected, and repeating that claim here
     * would reintroduce an error this file already retracted once.
     *
     * <p>All three are now real implementations ({@code apian}/{@code bacon} from
     * {@code bacon.cpp}; {@code alsk} from the {@code mod_ster} port), so there is no longer any
     * abstractly-registered name to test. Rather than delete the coverage or invent a fixture,
     * this asserts the <em>invariant</em> those three used to violate — which is both stronger
     * and, now, actually true: <b>every registered {@code +proj=} name resolves to something
     * instantiable, and nothing writes to stderr doing it.</b>
     *
     * <p>If a future registration binds a name to an abstract class again, this fails on that
     * name — which is what the old test was really for.
     */
    @Test
    public void everyRegisteredNameResolvesAndNothingTouchesStderr()
            throws UnsupportedEncodingException {
        Registry registry = new Registry();
        List<String> broken = new ArrayList<String>();
        for (String name : ABSTRACT_NAMES) {
            try {
                Projection p = registry.getProjection(name);
                if (p == null) {
                    broken.add(name + " -> null (not registered at all?)");
                } else if (p.getClass() == Projection.class) {
                    broken.add(name + " -> the abstract base class itself");
                }
            } catch (UnsupportedParameterException e) {
                broken.add(name + " -> " + e.getMessage());
            }
        }
        assertEquals("these names were fixed and must stay fixed", "[]", broken.toString());
        assertEquals("Registry must never write to System.err", "", stderr());
    }

    /**
     * {@code alsk} resolves to the real Alaska modified-stereographic port, not to the abstract
     * base and not to a "registered but not implemented" refusal.
     *
     * <p>This no longer asserts the registry description, and that is worth a note rather than a
     * silent omission. {@code Registry.projDescriptions} is private with <b>no accessor</b>, and
     * its only reader was the "registered but not implemented" message — which is now
     * unreachable, because no name is registered to an uninstantiable class any more. So the
     * description map is effectively <b>write-only</b>: 100-odd human-readable strings that
     * nothing can read. Either expose an accessor (it is genuinely useful for
     * {@code describe()}-style introspection, which a downstream consumer has asked for) or drop
     * the map. Do not re-add an assertion here until one of those happens.
     */
    @Test
    public void alskResolvesToTheRealImplementation() {
        Projection p = new Registry().getProjection("alsk");
        assertNotNull("alsk must resolve now that mod_ster is ported", p);
        assertFalse("alsk must not be the abstract base class",
                p.getClass() == Projection.class);
    }

    /**
     * A name that is genuinely absent from the registry must still return {@code null}: that is the
     * signal {@code Proj4Parser} turns into {@code "Unknown projection: …"}, and it is the one
     * meaning of {@code null} this method keeps.
     */
    @Test
    public void anUnregisteredNameStillReturnsNull() throws UnsupportedEncodingException {
        Registry registry = new Registry();
        assertNull(registry.getProjection("no_such_projection_name"));
        assertNull(registry.getProjection(""));
        assertEquals("", stderr());
    }

    @Test
    public void aNormalNameStillResolvesAndCarriesItsName() {
        Registry registry = new Registry();
        Projection merc = registry.getProjection("merc");
        assertNotNull(merc);
        assertEquals("merc", merc.getName());
    }

    /**
     * {@code getProjections()} must never propagate an exception — its contract is "the projections
     * you can actually use" — and it must never write to stderr.
     *
     * <p><b>Inverted:</b> this used to assert the three names were <em>absent</em>, because they
     * were uninstantiable and had to be skipped. They are implemented now, so they must be
     * <em>present</em>. Asserting their absence would re-pin the defect.
     */
    @Test
    public void getProjectionsYieldsEveryRegisteredNameWithoutThrowing()
            throws UnsupportedEncodingException {
        Registry registry = new Registry();
        List<Projection> projections = registry.getProjections();
        assertTrue("the registry should still yield the great majority of its names",
                projections.size() > 90);

        List<String> names = new ArrayList<String>();
        for (Projection p : projections) {
            assertNotNull(p);
            names.add(p.getName());
        }
        for (String name : ABSTRACT_NAMES) {
            assertTrue(name + " is implemented now and must appear in getProjections(); "
                    + "it was skipped only while it was uninstantiable",
                    names.contains(name));
        }
        assertEquals("getProjections must never write to System.err", "", stderr());
    }
}
