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

package org.locationtech.proj4j.determinism;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import org.junit.Test;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.io.Proj4FileReader;
import org.locationtech.proj4j.units.Unit;
import org.locationtech.proj4j.units.Units;

/**
 * Asserts that no code path in {@code core} depends on the <b>ambient default locale</b> when it is
 * handling an identifier, an authority code, a parameter key, a resource name or any other token
 * compared against an ASCII literal. {@link Locale#ROOT} is the correct form for all of those.
 *
 * <h2>The defect this exists to prevent</h2>
 *
 * <p>{@code Proj4FileReader.readParametersFromFile} built its classpath resource name with a
 * no-argument {@code authorityCode.toLowerCase()}. Under a Turkish default locale the JDK applies
 * the Turkish casing rule, under which {@code 'I'} lower-cases to the <b>dotless</b> {@code 'ı'}
 * (U+0131) rather than to ASCII {@code 'i'}. {@code "ESRI"} therefore became {@code "esrı"}, the
 * lookup for {@code proj4/nad/esri} never resolved, and <em>every</em> ESRI-authority definition
 * became unreachable with {@code Unable to access CRS file: proj4/nad/esrı}. On the {@code tr_TR}
 * determinism leg that single call site produced <b>5 errors</b> across
 * {@code CoordinateTransformTest} and {@code MetaCRSTest}.
 *
 * <h2>What was measured, on Temurin 21</h2>
 *
 * <p>Every classification rule below is measured rather than assumed, because two of the four
 * plausible guesses are wrong:
 *
 * <table border="1">
 * <caption>Locale sensitivity of the constructs this scan classifies</caption>
 * <tr><th>construct</th><th>en_US</th><th>de_DE / tr_TR</th><th>lt_LT</th><th>ar_EG</th></tr>
 * <tr><td>{@code "ESRI".toLowerCase()}</td><td>esri</td><td>tr: <b>esrı</b></td><td>esri</td><td>esri</td></tr>
 * <tr><td>{@code "id".toUpperCase()}</td><td>ID</td><td>tr: <b>İD</b></td><td>ID</td><td>ID</td></tr>
 * <tr><td>{@code Character.toLowerCase('I')}</td><td>i</td><td>i</td><td>i</td><td>i</td></tr>
 * <tr><td>{@code String.format("%f", 1.5)}</td><td>1.500000</td><td><b>1,500000</b></td><td><b>1,500000</b></td><td><b>١٫٥...</b></td></tr>
 * <tr><td>{@code String.format("%d", 12345)}</td><td>12345</td><td>12345</td><td>12345</td><td><b>١٢٣٤٥</b></td></tr>
 * <tr><td>{@code String.format("%08X", 48879)}</td><td>0000BEEF</td><td>0000BEEF</td><td>0000BEEF</td><td>0000BEEF</td></tr>
 * <tr><td>{@code NumberFormat.getNumberInstance().parse("1.5")}</td><td>1.5</td><td><b>15</b></td><td><b>1</b></td><td><b>1</b></td></tr>
 * </table>
 *
 * <p>Three conclusions, and the second and third are the ones that keep this scan from being
 * either vacuous or a churn machine:
 *
 * <ol>
 * <li><b>{@code Character.toLowerCase(char)} and {@code Character.toUpperCase(char)} are
 *     locale-invariant.</b> They have no {@code Locale} overload and apply the Unicode default case
 *     mapping, so the four call sites in {@code core} that use them for parsing -- {@code Angle},
 *     {@code AngleFormat}, {@code WktNames}, {@code CrsDefinitions} -- are already safe and
 *     rewriting them would be pure churn. The scan classifies them benign <em>and counts them</em>,
 *     so if that classification ever silently stops matching, the discrimination assertion fails.</li>
 * <li><b>Not every {@code String.format} is locale-sensitive.</b> {@code %x}, {@code %X},
 *     {@code %o}, {@code %s} and {@code %c} are invariant even under {@code ar_EG}, measured above;
 *     {@code %d} is <em>not</em>, and {@code %f}/{@code %e}/{@code %g} are not. So the rule keys on
 *     the conversion characters actually present in the format literal, not on the mere absence of
 *     a {@code Locale} first argument. Four of core's six {@code String.format} calls are
 *     hex-or-string only and are correctly left alone.</li>
 * <li><b>Display and parsing pull in opposite directions.</b> A number rendered for a human may
 *     legitimately follow the default locale; a number read back out of a CRS definition may not.
 *     {@link Unit} now holds both: a default-locale {@code format} for display and a
 *     {@code Locale.ROOT} one for {@link Unit#parse(String)}. The three deliberately
 *     default-locale formatters are in {@link #ALLOWED} with their reason, and the allow-list is
 *     itself asserted to be fully reached, so a stale entry is a failure rather than a silent
 *     widening.</li>
 * </ol>
 *
 * <h2>Why this test can fail</h2>
 *
 * <p>A scan that cannot fail is worthless and its failure mode is silent, so the detector's
 * sensitivity is proven four ways rather than assumed, following the shape of the sibling
 * {@link NoJdkAngleConversionTest}:
 *
 * <ul>
 * <li>{@link #classifierDistinguishesAmbientFromPinnedAndInvariant()} feeds the classifier crafted
 *     lines of every kind and asserts each verdict.</li>
 * <li>{@link #theScanReachesTheFileTheDefectWasIn()} asserts the walk actually reached
 *     {@code io/Proj4FileReader.java} and saw a pinned conversion there. If the walk silently
 *     reached nothing, the main assertion would pass trivially.</li>
 * <li>The main assertion requires non-zero counts in <em>four</em> benign buckets, so a classifier
 *     that has started rejecting or accepting everything is caught.</li>
 * <li>{@link #bytecodeScanFindsBothAritiesAndAgreesWithTheSourceScan()} re-derives the same claim
 *     from the compiled constant pool -- which distinguishes {@code toLowerCase()} from
 *     {@code toLowerCase(Locale)} by <em>descriptor</em>, something a text grep over a class file
 *     cannot do -- and {@link #bytecodeScanDetectsAnInjectedViolation()} feeds it a fabricated
 *     class file carrying the forbidden methodref, so the zero it reports is proven to be a zero it
 *     could have failed to report.</li>
 * </ul>
 *
 * <p>{@link #theFixedSitesStayFixedUnderATurkishDefaultLocale()} is the behavioural counterpart:
 * it runs the real code under {@code tr_TR}, {@code lt_LT} and {@code ar_EG} on <em>every</em> leg,
 * so the guarantee no longer depends on CI happening to schedule a Turkish job.
 *
 * <p>If the main assertion fails, do not add the offender to {@link #ALLOWED}. Pass
 * {@code Locale.ROOT}.
 */
public class NoAmbientLocaleInCoreTest {

    // ------------------------------------------------------------------ the deliberate allow-list

    /**
     * The only default-locale formatter constructions permitted in {@code core}, each with the
     * reason it is not a defect. Matched as {@code (relative source path, distinctive substring of
     * the line)}, so a <em>different</em> ambient-locale construction appearing in the same file is
     * still a failure.
     *
     * <p>Every entry must be reached -- see {@link #everyAllowListEntryIsStillReached()} -- so an
     * entry that has been fixed or deleted fails rather than quietly widening the guard.
     */
    private static final String[][] ALLOWED = {
        // Display only. Unit.format(double) renders a measurement for a human, and a reader in
        // Germany should see a decimal comma. Unit.parse is separately pinned to Locale.ROOT.
        {"org/locationtech/proj4j/units/Unit.java", "format = NumberFormat.getNumberInstance()"},
        // Write-only dead state: AngleFormat.format(double, StringBuffer, FieldPosition) appends
        // ints and doubles straight to the buffer and never consults this DecimalFormat. Verified
        // by grep: the field is touched only by the two setters on the following lines.
        {"org/locationtech/proj4j/units/AngleFormat.java", "format = new DecimalFormat()"},
        // Diagnostics only. ProjCoordinate.toShortString() is used in test failure messages;
        // ProjCoordinate.toString(), which is the one on real paths, uses Double.toString and is
        // locale-independent already.
        {"org/locationtech/proj4j/ProjCoordinate.java", "new DecimalFormat(DECIMAL_FORMAT_PATTERN)"},
    };

    // ------------------------------------------------------------------------------ the needles

    private static final String[] CASE_NEEDLES = {"toLowerCase", "toUpperCase"};

    private static final String[] FORMAT_NEEDLES = {"String.format", ".printf", ".format("};

    /**
     * Locale-defaulting formatter factories. Matched on the bare method name and then required to
     * be qualified by one of {@link #FORMATTER_OWNERS}, because anchoring on {@code
     * "NumberFormat.getNumberInstance"} misses the fully-qualified spelling -- which is not a
     * hypothetical: the injected-violation control caught exactly that hole, a
     * {@code new java.text.DecimalFormat("0.0")} that the first version of this scan waved through.
     */
    private static final String[] FORMATTER_FACTORIES = {
        "getNumberInstance", "getIntegerInstance", "getPercentInstance", "getCurrencyInstance",
        "getInstance", "getDateInstance", "getTimeInstance", "getDateTimeInstance",
    };

    /** {@code getInstance} alone is far too common, so the qualifier is checked. */
    private static final String[] FORMATTER_OWNERS = {
        "NumberFormat", "DateFormat", "DecimalFormatSymbols", "Collator", "MessageFormat",
    };

    /** Locale-defaulting formatter constructors, matched however they are qualified. */
    private static final String[] FORMATTER_CONSTRUCTORS = {
        "DecimalFormat", "SimpleDateFormat", "DecimalFormatSymbols", "MessageFormat",
    };

    /**
     * Conversion characters whose rendering depends on the locale. Measured, not guessed: see the
     * table in the class comment. {@code %d} is here because {@code ar_EG} substitutes
     * Arabic-Indic digits; {@code %x}/{@code %X}/{@code %o}/{@code %s}/{@code %c} are deliberately
     * absent because the same measurement shows they do not.
     */
    private static final String LOCALE_SENSITIVE_CONVERSIONS = "deEfgGaAtT";

    // ------------------------------------------------------------------------------- the guard

    @Test
    public void noCoreCodePathDependsOnTheAmbientDefaultLocale() throws IOException {
        Scan scan = scanCoreSources();

        assertTrue("found only " + scan.filesScanned + " core sources to scan under "
                + coreSourceRoot() + ", so this test would prove nothing", scan.filesScanned > 100);

        // Four independent discrimination proofs. Each of these buckets is non-empty in the tree as
        // it stands, so a classifier that has collapsed to "everything is fine" or "everything is a
        // violation" fails here rather than reporting a comfortable zero.
        assertTrue("the scan found no Locale.ROOT-pinned case conversions at all; the classifier "
                + "has stopped recognising the correct form, so a zero-violation result is "
                + "meaningless", scan.pinnedCase > 0);
        assertTrue("the scan found no Character.toLowerCase/toUpperCase occurrences; those exist "
                + "in core and are locale-invariant by JDK contract, so failing to see them means "
                + "the walk or the classifier is broken", scan.invariantCharacterCase > 0);
        assertTrue("the scan found no String.format calls whose conversions are all "
                + "locale-invariant; core has several, so this bucket being empty means the format "
                + "classifier is broken", scan.invariantFormat > 0);
        assertTrue("the scan found no Locale-pinned String.format call; PolarCoordinate has one, "
                + "so this bucket being empty means the format classifier is broken",
                scan.pinnedFormat > 0);

        if (!scan.violations.isEmpty()) {
            fail("These call sites depend on the ambient default locale. Under tr_TR the Turkish "
                    + "casing rule maps 'I' to dotless 'ı' and 'i' to dotted 'İ', so an "
                    + "identifier, authority code, parameter key or resource name round-tripped "
                    + "through a no-argument toLowerCase()/toUpperCase() stops matching its ASCII "
                    + "literal; under de_DE and lt_LT a '%f' or a NumberFormat gains a decimal "
                    + "comma, and under ar_EG '%d' renders in Arabic-Indic digits. Pass "
                    + "Locale.ROOT. Do NOT add the site to ALLOWED unless it is genuinely "
                    + "human-facing text. Offending sites (" + scan.violations.size() + "): "
                    + scan.violations);
        }
    }

    /**
     * The allow-list is an assertion in its own right: every entry must still correspond to a real
     * occurrence. A stale entry means the guard has been silently widened around code that no
     * longer exists.
     */
    @Test
    public void everyAllowListEntryIsStillReached() throws IOException {
        Scan scan = scanCoreSources();
        List<String> unreached = new ArrayList<String>();
        for (int i = 0; i < ALLOWED.length; i++) {
            String key = ALLOWED[i][0] + " :: " + ALLOWED[i][1];
            if (!scan.allowListHits.contains(key)) {
                unreached.add(key);
            }
        }
        assertTrue("these ALLOWED entries matched nothing, so the guard is wider than the code "
                + "justifies -- delete them: " + unreached, unreached.isEmpty());
        assertEquals("every ALLOWED entry should have been hit exactly once by the walk",
                ALLOWED.length, scan.allowListHits.size());
    }

    // ------------------------------------------------- sensitivity proof 1: reached the target

    /**
     * The walk must have reached the file the production defect lived in, and must see the fix
     * there. This is the analogue of {@code NoJdkAngleConversionTest} asserting it reached the
     * vendored {@code geodesic} package.
     */
    @Test
    public void theScanReachesTheFileTheDefectWasIn() throws IOException {
        Scan scan = scanCoreSources();
        String defectSite = "org/locationtech/proj4j/io/Proj4FileReader.java";
        assertTrue("the walk never reached " + defectSite + ", so its zero-violation verdict for "
                + "that file proves nothing; visited " + scan.filesScanned + " files",
                scan.filesVisited.contains(defectSite));
        assertTrue("the walk reached " + defectSite + " but saw no Locale-pinned case conversion "
                + "in it; the ESRI resource-name fix is the whole reason this test exists",
                scan.pinnedCaseFiles.contains(defectSite));

        // ... and the packages that carry the identifier-handling code, so a build-layout change
        // that quietly excluded a source tree cannot pass.
        String[] required = {
            "org/locationtech/proj4j/io/wkt/WktReader.java",
            "org/locationtech/proj4j/io/wkt/WktMethods.java",
            "org/locationtech/proj4j/io/wkt/CrsDefinitions.java",
            "org/locationtech/proj4j/io/projjson/ProjJsonReader.java",
            "org/locationtech/proj4j/units/Unit.java",
        };
        for (int i = 0; i < required.length; i++) {
            assertTrue("the walk did not reach " + required[i], scan.filesVisited.contains(required[i]));
        }
    }

    // ------------------------------------------------- sensitivity proof 2: the classifier itself

    @Test
    public void classifierDistinguishesAmbientFromPinnedAndInvariant() {
        // --- case conversion: the ambient forms are violations.
        assertViolation("        String filename = \"proj4/nad/\" + authorityCode.toLowerCase();");
        assertViolation("            String k = node.keyword().toUpperCase();");
        assertViolation("        String n = name.toLowerCase().replace(\"-\", \" \");");
        assertViolation("        return s.toLowerCase() .equals(other);");

        // --- case conversion: pinned is fine, in both spellings that appear in this tree.
        assertBenign("        String lower = gridName.toLowerCase(java.util.Locale.ROOT);");
        assertBenign("        return auth.toUpperCase(Locale.ROOT) + \":\" + code.trim();");

        // --- Character.toLowerCase/toUpperCase take a char, have no Locale overload and are
        // locale-invariant. Measured, not assumed. They must NOT be reported.
        assertBenign("            char c = Character.toUpperCase(text.charAt(length-1));");
        assertBenign("            sb.append(Character.toLowerCase(c));");
        assertBenign("        return Character.toUpperCase(s.charAt(0)) + s.substring(1);");

        // --- String.format: sensitive conversions without a Locale are violations.
        assertViolation("        return String.format(\"<x%f, y%f>\", lam, phi);");
        assertViolation("        return String.format(\"%d rows\", n);");
        assertViolation("        out.printf(\"%.3f%n\", value);");

        // --- String.format: invariant conversions are fine without a Locale.
        assertBenign("            return String.format(\"Grid: %s\", id);");
        assertBenign("        return String.format(\"ILP %x:%x\", lam, phi);");
        assertBenign("            throw new Error(String.format(\"wrong count $0%08X $0\", n));");
        assertBenign("                        sb.append(String.format(\"\\\\u%04x\", c));");
        // %% is an escaped percent, not a conversion.
        assertBenign("        return String.format(\"100%% done\");");

        // --- String.format: pinned is always fine, whatever the conversions.
        assertBenign("        return String.format(Locale.ROOT, \"<x%f, y%f>\", lam, phi);");

        // --- a format string held in a constant is unreadable to this scan, so it is reported
        // rather than waved through. A false negative here would be invisible; a false positive
        // is a one-line Locale.ROOT away.
        assertViolation("        return String.format(MESSAGE, lam, phi);");
        assertBenign("        return String.format(Locale.ROOT, MESSAGE, lam, phi);");
        // ... but an instance formatter's format() is not a Formatter call and is not judged here.
        assertNothingFound("        format.format(projectionLongitude, sb, null);");

        // --- formatter construction, in every spelling. The fully-qualified constructor is here
        // because the first version of this scan missed it and the injected-violation control
        // found the hole; anchoring on the literal text "new DecimalFormat" was not enough.
        assertViolation("        format = NumberFormat.getNumberInstance();");
        assertViolation("        format = new DecimalFormat(\"0.0###\");");
        assertViolation("        return new java.text.DecimalFormat(\"0.0\").format(1.0);");
        assertViolation("        f = java.text.NumberFormat.getInstance();");
        assertBenign("        PARSE = NumberFormat.getNumberInstance(Locale.ROOT);");
        assertBenign("        f = new DecimalFormat(p, DecimalFormatSymbols.getInstance(Locale.ROOT));");
        // getInstance is far too common a name to flag unqualified.
        assertNothingFound("        Calendar c = Calendar.getInstance();");
        // ... and a type whose name merely starts with a formatter's name is not that formatter.
        assertNothingFound("        DecimalFormatSymbolsProvider p = lookup();");

        // --- comments and javadoc are never code, in either direction.
        assertNothingFound("     * a no-argument {@code toLowerCase()} on an authority code");
        assertNothingFound("        // s = s.toUpperCase();");
        assertNothingFound("        /* String.format(\"%f\", x) */");

        // --- a string literal that merely mentions the needle is not a call.
        assertNothingFound("        throw new IllegalStateException(\"call toLowerCase(Locale.ROOT)\");");

        // --- and a line with neither yields nothing at all.
        assertNothingFound("        String x = ProjectionMath.toRad(deg) + \"\";");
    }

    private static void assertViolation(String line) {
        List<Occurrence> found = scan(line, "<test>");
        assertEquals("expected exactly one occurrence in: " + line, 1, found.size());
        assertTrue("should have been classified a violation: " + line,
                found.get(0).kind == Kind.VIOLATION);
    }

    private static void assertBenign(String line) {
        List<Occurrence> found = scan(line, "<test>");
        assertTrue("expected at least one occurrence in: " + line, found.size() > 0);
        for (int i = 0; i < found.size(); i++) {
            assertFalse("should have been classified benign: " + line + " -> " + found.get(i).kind,
                    found.get(i).kind == Kind.VIOLATION);
        }
    }

    private static void assertNothingFound(String line) {
        assertEquals("expected no occurrence at all in: " + line, 0, scan(line, "<test>").size());
    }

    // ---------------------------------------- sensitivity proof 3: the same claim from bytecode

    /**
     * The source scan depends on finding {@code src/main/java} and on a hand-written lexer. This
     * leg depends on neither: it parses the compiled constant pool and asks whether any class in
     * {@code core} references {@code String.toLowerCase:()Ljava/lang/String;} -- the <b>no-argument
     * descriptor</b>.
     *
     * <p>A byte-level {@code grep} cannot answer this question: the string {@code toLowerCase}
     * appears identically for both arities, and the descriptors live in separate constant-pool
     * entries. Parsing is the only instrument that discriminates, which is exactly why the
     * positive control below feeds it a fabricated class file rather than trusting it.
     */
    @Test
    public void bytecodeScanFindsBothAritiesAndAgreesWithTheSourceScan() throws IOException {
        File classes = coreClassesDirectory();
        List<File> all = new ArrayList<File>();
        collect(classes, all, ".class");
        assertTrue("found no compiled classes under " + classes, all.size() > 100);

        List<String> ambient = new ArrayList<String>();
        TreeSet<String> pinned = new TreeSet<String>();
        for (int i = 0; i < all.size(); i++) {
            File f = all.get(i);
            String rel = relative(classes, f).replace(File.separatorChar, '/');
            Set<String> refs = methodRefs(readBytes(f));
            if (refs.contains("java/lang/String.toLowerCase:()Ljava/lang/String;")
                    || refs.contains("java/lang/String.toUpperCase:()Ljava/lang/String;")) {
                ambient.add(rel);
            }
            if (refs.contains("java/lang/String.toLowerCase:(Ljava/util/Locale;)Ljava/lang/String;")
                    || refs.contains(
                            "java/lang/String.toUpperCase:(Ljava/util/Locale;)Ljava/lang/String;")) {
                pinned.add(rel);
            }
        }

        // Positive control that keeps working on its own: the pinned arity must be found, and in
        // the class the defect was in. A parser that could not see these methodrefs would report
        // zero for both arities and look clean.
        assertTrue("the constant-pool parser found no Locale-pinned String case conversion "
                + "anywhere in core; it cannot see these methodrefs at all, so its zero for the "
                + "ambient arity is meaningless", pinned.size() >= 5);
        assertTrue("the constant-pool parser did not find the pinned conversion in Proj4FileReader, "
                + "which is the class the ESRI defect was in: " + pinned,
                pinned.contains("org/locationtech/proj4j/io/Proj4FileReader.class"));

        assertTrue("these compiled core classes still reference the no-argument "
                + "String.toLowerCase()/toUpperCase(), which follows the ambient default locale: "
                + ambient, ambient.isEmpty());
    }

    /**
     * The injected-violation control, always on. A fabricated class file carrying a
     * {@code Methodref} to {@code java/lang/String.toLowerCase:()Ljava/lang/String;} must be
     * detected, and the same file with the {@code Locale} descriptor must not be.
     */
    @Test
    public void bytecodeScanDetectsAnInjectedViolation() throws IOException {
        Set<String> bad = methodRefs(
                fabricateClassFile("java/lang/String", "toLowerCase", "()Ljava/lang/String;"));
        assertTrue("the parser failed to see an injected no-argument String.toLowerCase methodref, "
                + "so every clean result it has ever produced is worthless: " + bad,
                bad.contains("java/lang/String.toLowerCase:()Ljava/lang/String;"));

        Set<String> good = methodRefs(fabricateClassFile("java/lang/String", "toLowerCase",
                "(Ljava/util/Locale;)Ljava/lang/String;"));
        assertFalse("the parser conflated the two arities, so it cannot enforce anything",
                good.contains("java/lang/String.toLowerCase:()Ljava/lang/String;"));
        assertTrue("the parser lost the pinned form entirely",
                good.contains("java/lang/String.toLowerCase:(Ljava/util/Locale;)Ljava/lang/String;"));
    }

    // --------------------------------------------------- the behavioural guard, on every CI leg

    /**
     * The scan is a text and bytecode argument; this is the behavioural one. It exercises the two
     * fixed sites and the WKT keyword path under the three locales that break ASCII assumptions in
     * different ways, so the guarantee holds on every leg rather than only when CI happens to
     * schedule a Turkish job.
     *
     * <p>The static formatters in {@link Unit} and {@link ProjCoordinate} are forced to initialise
     * <em>before</em> the default locale moves, so this test cannot leave a differently-configured
     * formatter behind for whatever runs next in the same JVM.
     */
    @Test
    public void theFixedSitesStayFixedUnderATurkishDefaultLocale() throws IOException {
        // Warm the default-locale statics under the real ambient locale first.
        assertNotNull(Unit.format);
        assertNotNull(Units.DEGREES.format(1.5));
        assertNotNull(new ProjCoordinate(1.5, 2.5).toShortString());

        Locale original = Locale.getDefault();
        try {
            Locale[] hostile = {
                new Locale("tr", "TR"), new Locale("lt", "LT"), new Locale("ar", "EG"),
            };
            for (int i = 0; i < hostile.length; i++) {
                Locale.setDefault(hostile[i]);
                String where = " under default locale " + hostile[i];

                // 1. The headline defect: an upper-case authority code must still resolve.
                String[] params = new Proj4FileReader().readParametersFromFile("ESRI", "102008");
                assertNotNull("ESRI:102008 must resolve" + where, params);
                assertTrue("ESRI:102008 came back empty" + where, params.length > 0);

                // ... end to end, through the factory, which is how a consumer meets it.
                CoordinateReferenceSystem esri =
                        new CRSFactory().createFromName("ESRI:102008");
                assertNotNull("CRSFactory could not build ESRI:102008" + where, esri);

                // 2. The ErrorCause metric keys are hard-coded ASCII and must stay ASCII.
                for (ErrorCause c : ErrorCause.values()) {
                    String key = c.metricKey();
                    assertTrue("metric key " + key + " is not ASCII lower-case" + where,
                            key.matches("^(crs|coord|env)\\.[a-z0-9_]+$"));
                }
                assertEquals("crs.invalid_crs_syntax" + where, "crs.invalid_crs_syntax",
                        ErrorCause.INVALID_CRS_SYNTAX.metricKey());

                // 3. WKT keyword dispatch upper-cases keywords; a lower-case 'i' must survive it.
                assertEquals("'id'.toUpperCase(ROOT) must be ASCII" + where,
                        "ID", "id".toUpperCase(Locale.ROOT));

                // 4. Unit.parse reads a machine-written value, so a decimal point is a decimal
                // point even where the locale says otherwise.
                assertEquals("Unit.parse must read an ASCII decimal point" + where,
                        1.5, Units.METRES.parse("1.5"), 0.0);
            }
        } finally {
            Locale.setDefault(original);
        }
    }

    // ------------------------------------------------------------------------------- the scanner

    private enum Kind {
        /** Ambient default locale where an ASCII-stable result is required. */
        VIOLATION,
        /** An explicit {@code Locale} argument. */
        PINNED_CASE,
        /** {@code Character.toLowerCase/toUpperCase}: no {@code Locale} overload exists. */
        INVARIANT_CHARACTER_CASE,
        /** A format string whose conversions are all locale-invariant. */
        INVARIANT_FORMAT,
        /** A format call with an explicit {@code Locale} first argument. */
        PINNED_FORMAT,
        /** On the deliberate allow-list. */
        ALLOWED_DISPLAY,
    }

    private static final class Occurrence {
        final int line;
        final Kind kind;
        final String text;
        final String allowKey;

        Occurrence(int line, Kind kind, String text, String allowKey) {
            this.line = line;
            this.kind = kind;
            this.text = text;
            this.allowKey = allowKey;
        }
    }

    private static final class Scan {
        final List<String> violations = new ArrayList<String>();
        final Set<String> filesVisited = new TreeSet<String>();
        final Set<String> pinnedCaseFiles = new TreeSet<String>();
        final Set<String> allowListHits = new LinkedHashSet<String>();
        int filesScanned;
        int pinnedCase;
        int invariantCharacterCase;
        int invariantFormat;
        int pinnedFormat;
    }

    private static Scan scanCoreSources() throws IOException {
        File root = coreSourceRoot();
        List<File> sources = new ArrayList<File>();
        collect(root, sources, ".java");
        Scan s = new Scan();
        s.filesScanned = sources.size();
        for (int i = 0; i < sources.size(); i++) {
            File f = sources.get(i);
            String rel = relative(root, f).replace(File.separatorChar, '/');
            s.filesVisited.add(rel);
            List<Occurrence> found = scan(read(f), rel);
            for (int j = 0; j < found.size(); j++) {
                Occurrence o = found.get(j);
                switch (o.kind) {
                    case VIOLATION:
                        s.violations.add(rel + ":" + o.line + "  " + o.text);
                        break;
                    case PINNED_CASE:
                        s.pinnedCase++;
                        s.pinnedCaseFiles.add(rel);
                        break;
                    case INVARIANT_CHARACTER_CASE:
                        s.invariantCharacterCase++;
                        break;
                    case INVARIANT_FORMAT:
                        s.invariantFormat++;
                        break;
                    case PINNED_FORMAT:
                        s.pinnedFormat++;
                        break;
                    case ALLOWED_DISPLAY:
                        s.allowListHits.add(o.allowKey);
                        break;
                    default:
                        throw new IllegalStateException(String.valueOf(o.kind));
                }
            }
        }
        return s;
    }

    /**
     * Finds and classifies every locale-relevant construct in {@code source}.
     *
     * <p>Comments, string literals and character literals are excluded by an explicit lexer pass
     * rather than by a line heuristic, so a needle mentioned in javadoc or inside a message is
     * never reported and a real call is never missed because it shares a line with a quote.
     */
    private static List<Occurrence> scan(String source, String relativePath) {
        byte[] state = lex(source);
        List<Occurrence> out = new ArrayList<Occurrence>();

        for (int k = 0; k < CASE_NEEDLES.length; k++) {
            String needle = CASE_NEEDLES[k];
            for (int at = source.indexOf(needle); at >= 0; at = source.indexOf(needle, at + 1)) {
                if (state[at] != CODE) {
                    continue;
                }
                int open = skipSpace(source, at + needle.length());
                if (open >= source.length() || source.charAt(open) != '(') {
                    continue;
                }
                String args = arguments(source, open);
                boolean character = precededBy(source, at, "Character.");
                Kind kind;
                if (character) {
                    kind = Kind.INVARIANT_CHARACTER_CASE;
                } else if (mentionsLocale(args)) {
                    kind = Kind.PINNED_CASE;
                } else {
                    kind = Kind.VIOLATION;
                }
                out.add(new Occurrence(lineOf(source, at), kind, snippet(source, at), null));
            }
        }

        // "String.format", ".printf" and ".format(" overlap on the same call -- "String.format("
        // matches all three -- so candidates are keyed on the position of the open parenthesis and
        // each call is classified exactly once.
        // "String.format", ".printf" and ".format(" overlap on the same call -- "String.format("
        // matches all three -- so candidates are keyed on the position of the open parenthesis and
        // each call is classified exactly once.
        Set<Integer> formatCalls = new TreeSet<Integer>();
        Set<Integer> definiteFormatter = new TreeSet<Integer>();
        for (int k = 0; k < FORMAT_NEEDLES.length; k++) {
            String needle = FORMAT_NEEDLES[k];
            for (int at = source.indexOf(needle); at >= 0; at = source.indexOf(needle, at + 1)) {
                if (state[at] != CODE) {
                    continue;
                }
                int open;
                if (needle.endsWith("(")) {
                    open = at + needle.length() - 1;
                } else {
                    open = skipSpace(source, at + needle.length());
                    if (open >= source.length() || source.charAt(open) != '(') {
                        continue;
                    }
                }
                formatCalls.add(Integer.valueOf(open));
                if (!".format(".equals(needle)) {
                    // String.format / printf are Formatter calls for certain. A bare ".format("
                    // is not: it also matches DecimalFormat/NumberFormat/AngleFormat instance
                    // calls, whose locale lives in the formatter rather than at the call site and
                    // which FORMATTER_CONSTRUCTORS covers instead.
                    definiteFormatter.add(Integer.valueOf(open));
                }
            }
        }
        for (Integer openBox : formatCalls) {
            int open = openBox.intValue();
            int at = open;
            String args = arguments(source, open);
            String literal = firstStringLiteral(args);
            boolean definite = definiteFormatter.contains(openBox);
            if (literal == null) {
                // A format string held in a constant is still a format string. Conservative on
                // purpose: a Formatter call whose conversions this scan cannot read is reported
                // unless it pins a Locale, because the alternative is a silent false negative.
                if (definite && !startsWithLocale(args)) {
                    out.add(new Occurrence(lineOf(source, at), Kind.VIOLATION, snippet(source, at),
                            null));
                } else if (definite) {
                    out.add(new Occurrence(lineOf(source, at), Kind.PINNED_FORMAT,
                            snippet(source, at), null));
                }
                continue;
            }
            if (literal.indexOf('%') < 0 && !definite) {
                continue;
            }
            Kind kind;
            if (startsWithLocale(args)) {
                kind = Kind.PINNED_FORMAT;
            } else if (hasLocaleSensitiveConversion(literal)) {
                kind = Kind.VIOLATION;
            } else {
                kind = Kind.INVARIANT_FORMAT;
            }
            out.add(new Occurrence(lineOf(source, at), kind, snippet(source, at), null));
        }

        Set<Integer> formatterCalls = new TreeSet<Integer>();
        for (int k = 0; k < FORMATTER_FACTORIES.length; k++) {
            String needle = FORMATTER_FACTORIES[k];
            for (int at = source.indexOf(needle); at >= 0; at = source.indexOf(needle, at + 1)) {
                if (state[at] != CODE || !isCallAt(source, at, needle)) {
                    continue;
                }
                String qualifier = dottedPrefixBefore(source, at);
                if (endsWithOwner(qualifier)) {
                    formatterCalls.add(Integer.valueOf(at));
                }
            }
        }
        for (int k = 0; k < FORMATTER_CONSTRUCTORS.length; k++) {
            String needle = FORMATTER_CONSTRUCTORS[k];
            for (int at = source.indexOf(needle); at >= 0; at = source.indexOf(needle, at + 1)) {
                if (state[at] != CODE || !isCallAt(source, at, needle)) {
                    continue;
                }
                // Accept any qualification -- "new DecimalFormat", "new java.text.DecimalFormat" --
                // by stepping back over the dotted prefix and requiring the keyword "new".
                int before = at - dottedPrefixBefore(source, at).length();
                String head = source.substring(Math.max(0, before - 8), Math.max(0, before)).trim();
                if (head.endsWith("new")) {
                    formatterCalls.add(Integer.valueOf(at));
                }
            }
        }
        for (Integer atBox : formatterCalls) {
            int at = atBox.intValue();
            int open = skipSpace(source, at + tokenLength(source, at));
            String args = arguments(source, open);
            if (mentionsLocale(args)) {
                out.add(new Occurrence(lineOf(source, at), Kind.PINNED_CASE, snippet(source, at),
                        null));
                continue;
            }
            String allowKey = allowListKey(relativePath, snippet(source, at));
            Kind kind = allowKey != null ? Kind.ALLOWED_DISPLAY : Kind.VIOLATION;
            out.add(new Occurrence(lineOf(source, at), kind, snippet(source, at), allowKey));
        }
        return out;
    }

    /** Whether {@code needle} at {@code at} is a whole identifier immediately followed by "(". */
    private static boolean isCallAt(String source, int at, String needle) {
        int after = at + needle.length();
        if (after < source.length() && Character.isJavaIdentifierPart(source.charAt(after))) {
            return false;
        }
        int open = skipSpace(source, after);
        return open < source.length() && source.charAt(open) == '(';
    }

    private static int tokenLength(String source, int at) {
        int i = at;
        while (i < source.length() && Character.isJavaIdentifierPart(source.charAt(i))) {
            i++;
        }
        return i - at;
    }

    /** The dotted qualifier immediately preceding {@code at}, e.g. {@code "java.text."}. */
    private static String dottedPrefixBefore(String source, int at) {
        int i = at;
        while (i > 0) {
            char c = source.charAt(i - 1);
            if (c == '.' || Character.isJavaIdentifierPart(c)) {
                i--;
            } else {
                break;
            }
        }
        return source.substring(i, at);
    }

    private static boolean endsWithOwner(String qualifier) {
        for (int i = 0; i < FORMATTER_OWNERS.length; i++) {
            if (qualifier.endsWith(FORMATTER_OWNERS[i] + ".")) {
                return true;
            }
        }
        return false;
    }

    private static String allowListKey(String relativePath, String lineText) {
        for (int i = 0; i < ALLOWED.length; i++) {
            if (ALLOWED[i][0].equals(relativePath) && lineText.contains(ALLOWED[i][1])) {
                return ALLOWED[i][0] + " :: " + ALLOWED[i][1];
            }
        }
        return null;
    }

    private static boolean mentionsLocale(String args) {
        return args.indexOf("Locale.") >= 0 || args.indexOf("Locale)") >= 0
                || args.indexOf("locale") >= 0;
    }

    private static boolean startsWithLocale(String args) {
        String t = args.trim();
        return t.startsWith("Locale.") || t.startsWith("java.util.Locale.")
                || t.startsWith("locale");
    }

    /**
     * Whether a format literal contains a conversion whose rendering follows the locale. Measured
     * membership -- {@code %x} and {@code %s} are not in the set, {@code %d} is.
     */
    private static boolean hasLocaleSensitiveConversion(String literal) {
        for (int i = 0; i < literal.length(); i++) {
            if (literal.charAt(i) != '%') {
                continue;
            }
            int j = i + 1;
            if (j < literal.length() && literal.charAt(j) == '%') {
                i = j;
                continue;
            }
            while (j < literal.length() && "0123456789$+-#, (.".indexOf(literal.charAt(j)) >= 0) {
                j++;
            }
            if (j < literal.length() && LOCALE_SENSITIVE_CONVERSIONS.indexOf(literal.charAt(j)) >= 0) {
                return true;
            }
        }
        return false;
    }

    /** The text between the parenthesis at {@code open} and its match, or the rest of the text. */
    private static String arguments(String source, int open) {
        int depth = 0;
        boolean inString = false;
        boolean inChar = false;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (inChar) {
                if (c == '\\') {
                    i++;
                } else if (c == '\'') {
                    inChar = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '\'') {
                inChar = true;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return source.substring(open + 1, i);
                }
            }
        }
        return source.substring(Math.min(open + 1, source.length()));
    }

    /** The first double-quoted literal in {@code args}, with its escapes left as written. */
    private static String firstStringLiteral(String args) {
        int start = -1;
        for (int i = 0; i < args.length(); i++) {
            char c = args.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '"') {
                if (start < 0) {
                    start = i + 1;
                } else {
                    return args.substring(start, i);
                }
            }
        }
        return null;
    }

    private static boolean precededBy(String source, int at, String prefix) {
        int start = at - prefix.length();
        return start >= 0 && source.regionMatches(start, prefix, 0, prefix.length());
    }

    private static int skipSpace(String source, int from) {
        int i = from;
        while (i < source.length() && Character.isWhitespace(source.charAt(i))) {
            i++;
        }
        return i;
    }

    private static int lineOf(String source, int at) {
        int line = 1;
        for (int i = 0; i < at; i++) {
            if (source.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static String snippet(String source, int at) {
        int start = source.lastIndexOf('\n', at) + 1;
        int end = source.indexOf('\n', at);
        return source.substring(start, end < 0 ? source.length() : end).trim();
    }

    // ------------------------------------------------------------------------------- the lexer

    private static final byte CODE = 0;
    private static final byte OTHER = 1;

    /**
     * Marks every character as code or not-code. Not-code is: a line comment, a block or javadoc
     * comment, a string literal body, and a character literal body. Deliberately simple -- no text
     * blocks, because this codebase targets Java 8 source level.
     */
    private static byte[] lex(String s) {
        byte[] out = new byte[s.length()];
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            char n = i + 1 < s.length() ? s.charAt(i + 1) : '\0';
            if (c == '/' && n == '/') {
                while (i < s.length() && s.charAt(i) != '\n') {
                    out[i++] = OTHER;
                }
            } else if (c == '/' && n == '*') {
                out[i++] = OTHER;
                out[i++] = OTHER;
                while (i < s.length() && !(s.charAt(i) == '*' && i + 1 < s.length()
                        && s.charAt(i + 1) == '/')) {
                    out[i++] = OTHER;
                }
                if (i < s.length()) {
                    out[i++] = OTHER;
                }
                if (i < s.length()) {
                    out[i++] = OTHER;
                }
            } else if (c == '"') {
                out[i++] = OTHER;
                while (i < s.length() && s.charAt(i) != '"') {
                    if (s.charAt(i) == '\\') {
                        out[i++] = OTHER;
                    }
                    if (i < s.length()) {
                        out[i++] = OTHER;
                    }
                }
                if (i < s.length()) {
                    out[i++] = OTHER;
                }
            } else if (c == '\'') {
                out[i++] = OTHER;
                while (i < s.length() && s.charAt(i) != '\'') {
                    if (s.charAt(i) == '\\') {
                        out[i++] = OTHER;
                    }
                    if (i < s.length()) {
                        out[i++] = OTHER;
                    }
                }
                if (i < s.length()) {
                    out[i++] = OTHER;
                }
            } else {
                out[i++] = CODE;
            }
        }
        // Belt as well as braces, and it is what makes a single javadoc line classifiable on its
        // own: any line whose first non-space characters open or continue a comment is not code,
        // whatever the block lexer above concluded about where the comment started.
        int lineStart = 0;
        while (lineStart < s.length()) {
            int lineEnd = s.indexOf('\n', lineStart);
            if (lineEnd < 0) {
                lineEnd = s.length();
            }
            int t = lineStart;
            while (t < lineEnd && (s.charAt(t) == ' ' || s.charAt(t) == '\t')) {
                t++;
            }
            boolean commentLine = t < lineEnd && (s.charAt(t) == '*'
                    || (s.charAt(t) == '/' && t + 1 < lineEnd
                        && (s.charAt(t + 1) == '/' || s.charAt(t + 1) == '*')));
            if (commentLine) {
                for (int k = lineStart; k < lineEnd; k++) {
                    out[k] = OTHER;
                }
            }
            lineStart = lineEnd + 1;
        }
        return out;
    }

    // -------------------------------------------------------------------- the constant-pool parser

    /**
     * Every {@code owner.name:descriptor} a class file's constant pool names as a method reference.
     * Parsing rather than grepping is the point: the two arities of {@code String.toLowerCase}
     * share the name {@code toLowerCase} byte for byte and differ only in a descriptor held in a
     * separate entry.
     */
    private static Set<String> methodRefs(byte[] b) {
        Set<String> out = new TreeSet<String>();
        if (b.length < 10 || u4(b, 0) != 0xCAFEBABEL) {
            throw new IllegalArgumentException("not a class file");
        }
        int count = u2(b, 8);
        int[] tag = new int[count];
        int[] a1 = new int[count];
        int[] a2 = new int[count];
        String[] utf = new String[count];
        int p = 10;
        for (int i = 1; i < count; i++) {
            int t = b[p++] & 0xFF;
            tag[i] = t;
            switch (t) {
                case 1: { // Utf8
                    int len = u2(b, p);
                    p += 2;
                    StringBuilder sb = new StringBuilder(len);
                    for (int k = 0; k < len; k++) {
                        sb.append((char) (b[p + k] & 0xFF));
                    }
                    utf[i] = sb.toString();
                    p += len;
                    break;
                }
                case 7: case 8: case 16: case 19: case 20: // Class, String, MethodType, Module, Package
                    a1[i] = u2(b, p);
                    p += 2;
                    break;
                case 15: // MethodHandle
                    p += 3;
                    break;
                case 3: case 4: // Integer, Float
                    p += 4;
                    break;
                case 5: case 6: // Long, Double -- occupy two slots
                    p += 8;
                    i++;
                    break;
                case 9: case 10: case 11: case 12: case 17: case 18:
                    // Fieldref, Methodref, InterfaceMethodref, NameAndType, Dynamic, InvokeDynamic
                    a1[i] = u2(b, p);
                    a2[i] = u2(b, p + 2);
                    p += 4;
                    break;
                default:
                    throw new IllegalStateException("unknown constant pool tag " + t + " at " + i);
            }
        }
        for (int i = 1; i < count; i++) {
            if (tag[i] != 10 && tag[i] != 11) {
                continue;
            }
            String owner = utf[a1[a1[i]]];
            int nat = a2[i];
            out.add(owner + "." + utf[a1[nat]] + ":" + utf[a2[nat]]);
        }
        return out;
    }

    /**
     * A minimal but structurally valid class file whose constant pool carries exactly one
     * {@code Methodref}. Used as the injected-violation control: a real binary carrier, so the
     * parser is exercised the same way it is on {@code target/classes}.
     */
    private static byte[] fabricateClassFile(String owner, String name, String descriptor)
            throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeU4(out, 0xCAFEBABEL);
        writeU2(out, 0);   // minor
        writeU2(out, 52);  // major (Java 8)
        writeU2(out, 7);   // constant_pool_count: 6 entries
        writeUtf8(out, owner);              // #1
        out.write(7); writeU2(out, 1);      // #2 Class -> #1
        writeUtf8(out, name);               // #3
        writeUtf8(out, descriptor);         // #4
        out.write(12); writeU2(out, 3); writeU2(out, 4);  // #5 NameAndType
        out.write(10); writeU2(out, 2); writeU2(out, 5);  // #6 Methodref
        writeU2(out, 0x0021); // access_flags
        writeU2(out, 2);      // this_class
        writeU2(out, 2);      // super_class
        writeU2(out, 0);      // interfaces
        writeU2(out, 0);      // fields
        writeU2(out, 0);      // methods
        writeU2(out, 0);      // attributes
        return out.toByteArray();
    }

    private static void writeUtf8(ByteArrayOutputStream out, String s) {
        out.write(1);
        writeU2(out, s.length());
        for (int i = 0; i < s.length(); i++) {
            out.write(s.charAt(i) & 0xFF);
        }
    }

    private static void writeU2(ByteArrayOutputStream out, int v) {
        out.write((v >>> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    private static void writeU4(ByteArrayOutputStream out, long v) {
        out.write((int) ((v >>> 24) & 0xFF));
        out.write((int) ((v >>> 16) & 0xFF));
        out.write((int) ((v >>> 8) & 0xFF));
        out.write((int) (v & 0xFF));
    }

    private static int u2(byte[] b, int at) {
        return ((b[at] & 0xFF) << 8) | (b[at + 1] & 0xFF);
    }

    private static long u4(byte[] b, int at) {
        return ((long) (b[at] & 0xFF) << 24) | ((b[at + 1] & 0xFF) << 16)
                | ((b[at + 2] & 0xFF) << 8) | (b[at + 3] & 0xFF);
    }

    // ---------------------------------------------------------------------------------- plumbing

    private static File coreSourceRoot() {
        File classes = coreClassesDirectory();
        File module = classes.getParentFile().getParentFile();
        File src = new File(module, "src" + File.separator + "main" + File.separator + "java");
        if (!src.isDirectory()) {
            throw new IllegalStateException("cannot locate core sources; looked in " + src);
        }
        return src;
    }

    private static File coreClassesDirectory() {
        URL url = CoordinateReferenceSystem.class.getResource(
                "/org/locationtech/proj4j/CoordinateReferenceSystem.class");
        if (url == null) {
            throw new IllegalStateException("cannot locate the compiled core classes");
        }
        String path = url.getPath();
        int index = path.indexOf("/org/locationtech/proj4j/CoordinateReferenceSystem.class");
        File dir = new File(path.substring(0, index).replace("%20", " "));
        if (!dir.isDirectory()) {
            throw new IllegalStateException("compiled core classes are not in a directory: " + dir);
        }
        return dir;
    }

    private static void collect(File dir, List<File> out, String suffix) {
        File[] entries = dir.listFiles();
        if (entries == null) {
            return;
        }
        Arrays.sort(entries);
        for (int i = 0; i < entries.length; i++) {
            if (entries[i].isDirectory()) {
                collect(entries[i], out, suffix);
            } else if (entries[i].getName().endsWith(suffix)) {
                out.add(entries[i]);
            }
        }
    }

    private static String relative(File root, File file) {
        String r = root.getAbsolutePath();
        String f = file.getAbsolutePath();
        return f.startsWith(r) ? f.substring(r.length() + 1) : f;
    }

    /**
     * Reads the file byte for byte into a latin-1 string. Deliberately not a charset decode: a
     * source file in this repo contains a raw NUL, and a decode-and-split pipeline is exactly the
     * kind of instrument that goes silently blind on it.
     */
    private static String read(File f) throws IOException {
        byte[] b = readBytes(f);
        StringBuilder sb = new StringBuilder(b.length);
        for (int i = 0; i < b.length; i++) {
            sb.append((char) (b[i] & 0xFF));
        }
        return sb.toString();
    }

    private static byte[] readBytes(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }
}
