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
package org.locationtech.proj4j.benchmark.counting;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Loads {@code org.locationtech.proj4j} classes with {@code java.lang.Math} and
 * {@code java.lang.StrictMath} call sites redirected to {@link CountingMath} and
 * {@link CountingStrictMath}, so Tier 2 can count transcendentals per transform <b>without a single
 * line of instrumentation in {@code core}</b>.
 *
 * <h2>Why a class loader, and not the obvious alternatives</h2>
 * <ul>
 *   <li><b>A facade in {@code core}</b> would put a counter increment on the hot path of a published
 *       library. Non-starter, and {@code reference/performance.md} says "test-only" for this reason.</li>
 *   <li><b>A {@code java.lang.instrument} agent or an ASM transformer</b> would work, but adds a
 *       bytecode-library dependency and an agent-attach step to a gate that has to run on every PR.</li>
 *   <li><b>Source-level counting</b> (a script that greps for {@code Math.} calls) counts <i>call
 *       sites</i>, not <i>calls</i>. The interesting regressions are extra <i>iterations</i>, which no
 *       static count can see.</li>
 * </ul>
 *
 * <h2>How the rewrite works, and why it is safe</h2>
 *
 * <p>A class file's constant pool contains the referenced class names as {@code CONSTANT_Utf8_info}
 * entries. Replacing {@code "java/lang/Math"} with this module's counting class's internal name is the
 * <i>entire</i> transformation: the {@code CONSTANT_Class_info} that points at it, the
 * {@code CONSTANT_Methodref_info} that points at that, and every {@code invokestatic} index in the
 * bytecode are all unchanged, because <b>no constant pool entry is added or removed</b>.
 *
 * <p>Changing a UTF8 entry's <i>length</i> is safe too, which is what makes this a dozen lines rather
 * than a hundred. Nothing in a class file stores an absolute offset - every structure is either
 * length-prefixed or fixed-width, and the constant pool is not nested inside any attribute, so no
 * {@code attribute_length} covers it. Attributes reference the pool only by 2-byte index, and those
 * indices do not move. So the replacement name does not need to be padded to fourteen characters.
 *
 * <p><b>The one false-positive case, stated so nobody has to find it the hard way:</b> a UTF8 entry is
 * a UTF8 entry, so a class containing the <i>string literal</i> {@code "java/lang/Math"} would have
 * that literal rewritten too. No class in {@code core} does, it would be harmless if one did (the
 * string is only ever used in a message), and detecting the difference would require parsing the whole
 * pool graph to see which entries are reachable from a {@code CONSTANT_Class_info}. Not worth it;
 * documented instead.
 *
 * <h2>Loader topology</h2>
 *
 * <p>Child-first for {@code org.locationtech.proj4j.*}, <b>except</b>
 * {@code org.locationtech.proj4j.benchmark.*}, which is delegated to the parent. That exception is
 * load-bearing: it means there is exactly <b>one</b> copy of {@link OpCounters}, owned by the parent,
 * so the recorder can read the tallies as ordinary static state instead of reflecting into a second
 * copy of the class.
 *
 * <p>Consequences the caller has to live with:
 * <ul>
 *   <li>Core classes loaded here are <b>not</b> assignable to the same-named classes on the app class
 *       path. {@link OpCountRecorder} therefore drives them reflectively. That cost is paid once per
 *       transform in a run that performs one transform per CRS pair, so it is irrelevant.</li>
 *   <li>Resources are <b>not</b> overridden, so {@code proj4/nad/epsg} and {@code ntv1_can.dat} resolve
 *       through the parent as usual and the init files are found normally.</li>
 *   <li>{@code core}'s static singletons ({@code Registry}, the {@code Datum} constants) are
 *       re-initialised in this loader. Class initialisation calls transcendentals - the {@code Datum}
 *       and {@code Ellipsoid} constants alone do - so {@link OpCountRecorder} performs a throwaway
 *       transform before resetting the counters. Without that, the first pair measured would carry all
 *       of static init and the counts would depend on enum declaration order.</li>
 * </ul>
 */
public final class CountingClassLoader extends ClassLoader {

    private static final String MATH = "java/lang/Math";
    private static final String STRICT_MATH = "java/lang/StrictMath";

    private static final String COUNTING_MATH = internalName(CountingMath.class);
    private static final String COUNTING_STRICT_MATH = internalName(CountingStrictMath.class);

    /** Loaded child-first and rewritten. */
    private static final String REWRITE_PREFIX = "org.locationtech.proj4j.";

    /**
     * Delegated to the parent even though it matches {@link #REWRITE_PREFIX}. Keeps {@link OpCounters}
     * a singleton and keeps the benchmark and gate classes on one side of the loader boundary.
     */
    private static final String DELEGATE_PREFIX = "org.locationtech.proj4j.benchmark.";

    private int rewrittenClasses;
    private int rewrittenReferences;

    public CountingClassLoader(ClassLoader parent) {
        super(parent);
    }

    /** Uses this class's own loader as the parent, which is the normal case. */
    public static CountingClassLoader create() {
        return new CountingClassLoader(CountingClassLoader.class.getClassLoader());
    }

    /** How many {@code core} classes were defined by this loader. Zero means the rewrite did nothing. */
    public int rewrittenClasses() {
        return rewrittenClasses;
    }

    /** How many {@code Math}/{@code StrictMath} constant-pool references were redirected. */
    public int rewrittenReferences() {
        return rewrittenReferences;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null && shouldRewrite(name)) {
                byte[] original = readClassBytes(name);
                if (original != null) {
                    byte[] rewritten = rewriteConstantPool(original);
                    loaded = defineClass(name, rewritten, 0, rewritten.length);
                    rewrittenClasses++;
                }
            }
            if (loaded == null) {
                loaded = super.loadClass(name, false);
            }
            if (resolve) {
                resolveClass(loaded);
            }
            return loaded;
        }
    }

    private static boolean shouldRewrite(String name) {
        return name.startsWith(REWRITE_PREFIX) && !name.startsWith(DELEGATE_PREFIX);
    }

    private byte[] readClassBytes(String name) {
        String resource = name.replace('.', '/') + ".class";
        // Read through the parent's resource path rather than defining from our own URLs: the parent
        // already has core and epsg on its class path, and reading a resource does not cause the parent
        // to *define* the class, which is the thing that would defeat child-first loading.
        try (InputStream in = getParent().getResourceAsStream(resource)) {
            if (in == null) {
                return null;
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(16384);
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + resource, e);
        }
    }

    /**
     * Walks the constant pool and rewrites the two class-name UTF8 entries.
     *
     * <p>Only the pool is parsed; everything after it is copied verbatim. Long and Double entries
     * occupy two pool slots, which is the one asymmetry in the format and the classic way to get this
     * loop wrong.
     */
    byte[] rewriteConstantPool(byte[] cf) {
        if (cf.length < 10 || u4(cf, 0) != 0xCAFEBABEL) {
            throw new IllegalArgumentException("Not a class file");
        }
        final int poolCount = u2(cf, 8);

        ByteArrayOutputStream out = new ByteArrayOutputStream(cf.length + 64);
        out.write(cf, 0, 10);

        int p = 10;
        for (int index = 1; index < poolCount; index++) {
            final int tag = cf[p] & 0xFF;
            switch (tag) {
                case 1: { // CONSTANT_Utf8
                    int len = u2(cf, p + 1);
                    String text = new String(cf, p + 3, len, java.nio.charset.StandardCharsets.UTF_8);
                    String replacement = replacementFor(text);
                    if (replacement != null) {
                        byte[] bytes = replacement.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        out.write(1);
                        out.write(bytes.length >>> 8);
                        out.write(bytes.length & 0xFF);
                        out.write(bytes, 0, bytes.length);
                        rewrittenReferences++;
                    } else {
                        out.write(cf, p, 3 + len);
                    }
                    p += 3 + len;
                    break;
                }
                case 7:   // Class
                case 8:   // String
                case 16:  // MethodType
                case 19:  // Module
                case 20:  // Package
                    out.write(cf, p, 3);
                    p += 3;
                    break;
                case 15:  // MethodHandle
                    out.write(cf, p, 4);
                    p += 4;
                    break;
                case 3:   // Integer
                case 4:   // Float
                case 9:   // Fieldref
                case 10:  // Methodref
                case 11:  // InterfaceMethodref
                case 12:  // NameAndType
                case 17:  // Dynamic
                case 18:  // InvokeDynamic
                    out.write(cf, p, 5);
                    p += 5;
                    break;
                case 5:   // Long
                case 6:   // Double
                    out.write(cf, p, 9);
                    p += 9;
                    index++; // occupies two pool slots
                    break;
                default:
                    throw new IllegalStateException("Unknown constant pool tag " + tag
                            + " at entry " + index + "; this JDK's class file format is newer than "
                            + "CountingClassLoader knows about. Add the tag's width above.");
            }
        }

        out.write(cf, p, cf.length - p);
        return out.toByteArray();
    }

    private static String replacementFor(String utf8) {
        if (MATH.equals(utf8)) {
            return COUNTING_MATH;
        }
        if (STRICT_MATH.equals(utf8)) {
            return COUNTING_STRICT_MATH;
        }
        return null;
    }

    private static String internalName(Class<?> c) {
        return c.getName().replace('.', '/');
    }

    private static int u2(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    private static long u4(byte[] b, int off) {
        return ((long) (b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }
}
