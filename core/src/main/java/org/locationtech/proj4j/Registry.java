/*******************************************************************************
 * Copyright 2009, 2017 Martin Davis
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
package org.locationtech.proj4j;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import org.locationtech.proj4j.datum.Datum;
import org.locationtech.proj4j.datum.Ellipsoid;
import org.locationtech.proj4j.proj.*;

/**
 * Supplies predefined values for various library classes
 * such as {@link Ellipsoid}, {@link Datum}, and {@link Projection}.
 *
 * @author Martin Davis
 */
public class Registry {

    /**
     * Creates a registry and populates its projection table. The datum and ellipsoid tables are
     * static, so every instance shares them.
     */
    public Registry() {
        super();
        initialize();
    }

    /**
     * The datums reachable by {@code +datum=}, searched by {@link #getDatum(String)}.
     * <p>
     * The array is public and its contents are mutable; treat it as read-only.
     */
    public final static Datum[] datums = {
            Datum.WGS84,
            Datum.GGRS87,
            Datum.NAD27,
            Datum.NAD83,
            Datum.POTSDAM,
            Datum.CARTHAGE,
            Datum.HERMANNSKOGEL,
            Datum.IRE65,
            Datum.NZGD49,
            Datum.OSGB36
    };

    /**
     * Looks up a datum by its PROJ.4 code, as used in {@code +datum=}.
     *
     * @param code the datum code, matched exactly and case-sensitively, e.g. {@code WGS84}
     * @return the datum, or null if no datum in {@link #datums} has that code
     */
    public Datum getDatum(String code) {
        for (int i = 0; i < datums.length; i++) {
            if (datums[i].getCode().equals(code)) {
                return datums[i];
            }
        }
        return null;
    }

    /**
     * The ellipsoids reachable by {@code +ellps=}, searched by {@link #getEllipsoid(String)}.
     * <p>
     * This is the list {@code +ellps=} resolves against, and it is <em>not</em> the same list as
     * {@code Ellipsoid.ellipsoids}, which only the WKT reader consults and which matches numerically
     * rather than by name. Adding an ellipsoid to one does not add it to the other.
     * <p>
     * The array is public and its contents are mutable; treat it as read-only.
     */
    public final static Ellipsoid[] ellipsoids = {
            Ellipsoid.SPHERE,
            new Ellipsoid("MERIT", 6378137.0, 0.0, 298.257, "MERIT 1983"),
            new Ellipsoid("SGS85", 6378136.0, 0.0, 298.257, "Soviet Geodetic System 85"),
            Ellipsoid.GRS80,
            new Ellipsoid("IAU76", 6378140.0, 0.0, 298.257, "IAU 1976"),
            Ellipsoid.AIRY,
            Ellipsoid.MOD_AIRY,
            new Ellipsoid("APL4.9", 6378137.0, 0.0, 298.25, "Appl. Physics. 1965"),
            new Ellipsoid("NWL9D", 6378145.0, 298.25, 0.0, "Naval Weapons Lab., 1965"),
            new Ellipsoid("andrae", 6377104.43, 300.0, 0.0, "Andrae 1876 (Den., Iclnd.)"),
            new Ellipsoid("aust_SA", 6378160.0, 0.0, 298.25, "Australian Natl & S. Amer. 1969"),
            new Ellipsoid("GRS67", 6378160.0, 0.0, 298.2471674270, "GRS 67 (IUGG 1967)"),
            Ellipsoid.BESSEL,
            new Ellipsoid("bess_nam", 6377483.865, 0.0, 299.1528128, "Bessel 1841 (Namibia)"),
            Ellipsoid.CLARKE_1866,
            Ellipsoid.CLARKE_1880,
            new Ellipsoid("CPM", 6375738.7, 0.0, 334.29, "Comm. des Poids et Mesures 1799"),
            new Ellipsoid("delmbr", 6376428.0, 0.0, 311.5, "Delambre 1810 (Belgium)"),
            new Ellipsoid("engelis", 6378136.05, 0.0, 298.2566, "Engelis 1985"),
            Ellipsoid.EVEREST,
            new Ellipsoid("evrst48", 6377304.063, 0.0, 300.8017, "Everest 1948"),
            new Ellipsoid("evrst56", 6377301.243, 0.0, 300.8017, "Everest 1956"),
            new Ellipsoid("evrst69", 6377295.664, 0.0, 300.8017, "Everest 1969"),
            new Ellipsoid("evrstSS", 6377298.556, 0.0, 300.8017, "Everest (Sabah & Sarawak)"),
            new Ellipsoid("fschr60", 6378166.0, 0.0, 298.3, "Fischer (Mercury Datum) 1960"),
            new Ellipsoid("fschr60m", 6378155.0, 0.0, 298.3, "Modified Fischer 1960"),
            new Ellipsoid("fschr68", 6378150.0, 0.0, 298.3, "Fischer 1968"),
            new Ellipsoid("helmert", 6378200.0, 0.0, 298.3, "Helmert 1906"),
            new Ellipsoid("hough", 6378270.0, 0.0, 297.0, "Hough"),
            Ellipsoid.INTERNATIONAL,
            Ellipsoid.INTERNATIONAL_1967,
            Ellipsoid.KRASSOVSKY,
            new Ellipsoid("kaula", 6378163.0, 0.0, 298.24, "Kaula 1961"),
            new Ellipsoid("lerch", 6378139.0, 0.0, 298.257, "Lerch 1979"),
            new Ellipsoid("mprts", 6397300.0, 0.0, 191.0, "Maupertius 1738"),
            new Ellipsoid("plessis", 6376523.0, 6355863.0, 0.0, "Plessis 1817 France)"),
            new Ellipsoid("SEasia", 6378155.0, 6356773.3205, 0.0, "Southeast Asia"),
            new Ellipsoid("walbeck", 6376896.0, 6355834.8467, 0.0, "Walbeck"),
            Ellipsoid.WGS60,
            Ellipsoid.WGS66,
            Ellipsoid.WGS72,
            Ellipsoid.WGS84,
            new Ellipsoid("NAD27", 6378249.145, 0.0, 293.4663, "NAD27: Clarke 1880 mod."),
            new Ellipsoid("NAD83", 6378137.0, 0.0, 298.257222101, "NAD83: GRS 1980 (IUGG, 1980)"),
            // Present in PROJ 9.8.1's src/ellps.cpp and previously absent here, so +ellps= failed
            // for all four. NOTE: this array, not Ellipsoid.ellipsoids, is what getEllipsoid()
            // searches - the two lists are separate and had already drifted apart. Adding a
            // constant to Ellipsoid alone leaves it unreachable via +ellps=, which is exactly
            // how these four were first added and then found to still throw
            // "Unknown ellipsoid". Ellipsoid.ellipsoids is read only by the WKT reader, which
            // matches numerically rather than by name.
            Ellipsoid.CLRK80IGN,
            Ellipsoid.DANISH,
            Ellipsoid.GSK2011,
            Ellipsoid.PZ90,
    };

    /**
     * Looks up an ellipsoid by its PROJ.4 short name, as used in {@code +ellps=}.
     *
     * @param name the short name, matched exactly and case-sensitively, e.g. {@code GRS80}
     * @return the ellipsoid, or null if no ellipsoid in {@link #ellipsoids} has that short name
     */
    public Ellipsoid getEllipsoid(String name) {
        for (int i = 0; i < ellipsoids.length; i++) {
            if (ellipsoids[i].shortName.equals(name)) {
                return ellipsoids[i];
            }
        }
        return null;
    }

    private Map<String, Class> projRegistry;
    private Map<String, String> projDescriptions;

    private void register(String name, Class cls, String description) {
        projRegistry.put(name, cls);
        projDescriptions.put(name, description);
    }

    /**
     * Instantiates the projection registered under a {@code +proj=} name.
     * <p>
     * Returns {@code null} if — and now <em>only</em> if — the name is not registered at all.
     * That is the signal {@code Proj4Parser} turns into
     * {@code InvalidValueException("Unknown projection: …")}, so it must keep meaning exactly
     * "unknown".
     * <p>
     * A name that <em>is</em> registered but cannot be instantiated no longer returns
     * {@code null}. Three names — {@code alsk}, {@code apian} and {@code bacon} — are
     * registered to the abstract {@link Projection} base class itself, so
     * {@code Class.newInstance()} on them throws {@link InstantiationException}. Until 1.5.0
     * that was caught, printed to {@code System.err} as an unconditional stack trace, and
     * turned into {@code null}, which the parser then reported as
     * <i>"Unknown projection: alsk"</i> — a lie about a name that is in the registry, plus
     * unsolicited stderr noise in every host process. Both are fixed here: the name resolves to
     * an {@link UnsupportedParameterException} carrying
     * {@link ErrorCause#PROJECTION_NOT_IMPLEMENTED} and saying which class is missing, and
     * nothing is written to {@code System.err}.
     *
     * @param name the {@code +proj=} name
     * @return the projection, or null if {@code name} is not registered
     * @throws UnsupportedParameterException if the name is registered but the registered class
     *                                       cannot be instantiated — abstract, an interface, or
     *                                       without an accessible no-argument constructor
     */
    public Projection getProjection(String name) {
        // if ( projRegistry == null )
        // initialize();
        Class cls = (Class) projRegistry.get(name);
        if (cls == null) {
            // Genuinely unknown. Proj4Parser turns null into "Unknown projection: <name>".
            return null;
        }
        if (Modifier.isAbstract(cls.getModifiers()) || Modifier.isInterface(cls.getModifiers())) {
            throw new UnsupportedParameterException(notImplementedMessage(name, cls, null));
        }
        try {
            Projection projection = (Projection) cls.newInstance();
            projection.setName(name);
            return projection;
        } catch (InstantiationException e) {
            // No accessible no-arg constructor, or the constructor threw.
            throw new UnsupportedParameterException(
                    ErrorCause.PROJECTION_NOT_IMPLEMENTED, notImplementedMessage(name, cls, e), e);
        } catch (IllegalAccessException e) {
            // The class or its constructor is not visible from here: a packaging error in
            // Proj4J itself, not a statement about the caller's definition.
            throw new Proj4jException(ErrorCause.INTERNAL_ERROR,
                    "Projection \"" + name + "\" is registered to " + cls.getName()
                            + ", which is not accessible from Registry", e);
        }
    }

    /**
     * Builds the message for a registered-but-uninstantiable name. Names the {@code +proj=}
     * value, its human-readable description, and the class that is missing, so the reader can
     * tell "Proj4J has not implemented this" from "you typed it wrong".
     */
    private String notImplementedMessage(String name, Class cls, Throwable detail) {
        StringBuilder sb = new StringBuilder();
        sb.append("Projection \"").append(name).append('"');
        String description = projDescriptions.get(name);
        if (description != null) {
            sb.append(" (").append(description).append(')');
        }
        sb.append(" is registered but not implemented in Proj4J: it is bound to ")
                .append(cls.getName());
        if (Modifier.isAbstract(cls.getModifiers())) {
            sb.append(", which is abstract");
        } else if (detail != null) {
            sb.append(", which cannot be instantiated (")
                    .append(detail.getClass().getName()).append(')');
        }
        return sb.toString();
    }

    /**
     * Every projection this registry can actually instantiate.
     * <p>
     * Names that are registered but not implemented are skipped rather than propagated, because
     * this method's contract is "the projections you can use". {@link #getProjection(String)} is
     * the diagnostic entry point.
     *
     * @return a fresh list of freshly-constructed projections
     */
    public List<Projection> getProjections() {
        List<Projection> projections = new ArrayList<>();

        for (String name : projRegistry.keySet()) {
            Projection projection;
            try {
                projection = getProjection(name);
            } catch (UnsupportedParameterException notImplemented) {
                continue;
            }

            if (projection != null) {
                projections.add(projection);
            }
        }

        return projections;
    }

    /**
     * The human-readable description registered alongside a {@code +proj=} name &mdash;
     * {@code "Albers Equal Area"} for {@code "aea"}, {@code "Azimuthal Equidistant"} for
     * {@code "aeqd"}.
     *
     * <p><b>Why this accessor exists.</b>
     * <p>These descriptions have been in this class since 2009, one per {@code register(...)} call,
     * and until 1.5.0 there was <b>no way to read them</b>. Their only reader was the
     * "registered but not implemented" message, which is now unreachable for every name that has an
     * implementation &mdash; so roughly a hundred human-readable projection names were write-only
     * data. {@link org.locationtech.proj4j.api.Crs#describe()} and
     * {@link org.locationtech.proj4j.api.Proj#projections()} are what they were always for: they let
     * an introspection call say <i>"lcc -- Lambert Conformal Conic"</i> instead of just
     * <i>"lcc"</i>.
     *
     * @param name the {@code +proj=} name
     * @return the description, or {@code null} if {@code name} is not registered or was registered
     *         with no description. As with {@link #getProjection(String)}, {@code null} means
     *         exactly "nothing registered under that name"; it never means "registered but not
     *         implemented", which {@link #getProjectionDescriptions()} will still list.
     * @since 1.5.0
     */
    public String getProjectionDescription(String name) {
        return name == null ? null : projDescriptions.get(name);
    }

    /**
     * Every registered {@code +proj=} name mapped to its human-readable description, sorted by
     * name.
     *
     * <p>Includes names that are registered but <em>not</em> implemented, unlike
     * {@link #getProjections()}: this method's contract is "what the registry knows about", which is
     * what an introspection surface needs, and it does not instantiate anything. Use
     * {@link #getProjection(String)} to find out whether a given name can actually be used.
     *
     * @return an unmodifiable, name-sorted view; never null
     * @since 1.5.0
     */
    public SortedMap<String, String> getProjectionDescriptions() {
        return Collections.unmodifiableSortedMap(
                new TreeMap<String, String>(projDescriptions));
    }

    private synchronized void initialize() {
        // guard against race condition
        if (projRegistry != null)
            return;
        projRegistry = new HashMap();
        projDescriptions = new HashMap<String, String>();
        register("aea", AlbersProjection.class, "Albers Equal Area");
        register("aeqd", EquidistantAzimuthalProjection.class, "Azimuthal Equidistant");
        register("airy", AiryProjection.class, "Airy");
        register("aitoff", AitoffProjection.class, "Aitoff");
        register("adams_hemi", AdamsHemisphereProjection.class, "Adams Hemisphere in a Square");
        register("adams_ws1", AdamsWorldInASquareIProjection.class, "Adams World in a Square I");
        register("adams_ws2", AdamsWorldInASquareIIProjection.class, "Adams World in a Square II");
        /*
         * alsk was bound to the abstract Projection base class, so getProjection("alsk") raised
         * PROJECTION_NOT_IMPLEMENTED - honest, and a deliberate improvement on the earlier lie
         * "Unknown projection: alsk", but its 16 builtins.gie assertions were unreachable.
         * AlaskaModifiedStereographicProjection is the mod_ster.cpp port that makes them
         * reachable; the other four names from the same upstream file are registered below.
         */
        register("alsk", AlaskaModifiedStereographicProjection.class,
                "Mod. Stereographic of Alaska");
        register("apian", ApianGlobular1Projection.class, "Apian Globular I");
        register("august", AugustProjection.class, "August Epicycloidal");
        register("bacon", BaconGlobularProjection.class, "Bacon Globular");
        register("bertin1953", Bertin1953Projection.class, "Bertin 1953");
        register("bipc", BipolarProjection.class, "Bipolar conic of western hemisphere");
        register("boggs", BoggsProjection.class, "Boggs Eumorphic");
        register("bonne", BonneProjection.class, "Bonne (Werner lat_1=90)");
        register("calcofi", CalCOFIProjection.class,
                "Cal Coop Ocean Fish Invest Lines/Stations");
        register("cass", CassiniProjection.class, "Cassini");
        register("cc", CentralCylindricalProjection.class, "Central Cylindrical");
        register("ccon", CentralConicProjection.class, "Central Conic");
        register("cea", CylindricalEqualAreaProjection.class, "Equal Area Cylindrical");
        // register( "chamb", Projection.class, "Chamberlin Trimetric" );
        register("collg", CollignonProjection.class, "Collignon");
        register("crast", CrasterProjection.class, "Craster Parabolic (Putnins P4)");
        register("denoy", DenoyerProjection.class, "Denoyer Semi-Elliptical");
        register("eck1", Eckert1Projection.class, "Eckert I");
        register("eck2", Eckert2Projection.class, "Eckert II");
        register("eck3", Eckert3Projection.class, "Eckert III");
        register("eck4", Eckert4Projection.class, "Eckert IV");
        register("eck5", Eckert5Projection.class, "Eckert V");
        register("eck6", Eckert6Projection.class, "Eckert VI");
        register("eqc", PlateCarreeProjection.class, "Equidistant Cylindrical (Plate Caree)");
        register("eqearth", EqualEarthProjection.class, "Equal Earth");
        register("eqdc", EquidistantConicProjection.class, "Equidistant Conic");
        register("euler", EulerProjection.class, "Euler");
        register("fahey", FaheyProjection.class, "Fahey");
        register("fouc", FoucautProjection.class, "Foucaut");
        register("fouc_s", FoucautSinusoidalProjection.class, "Foucaut Sinusoidal");
        register("gall", GallProjection.class, "Gall (Gall Stereographic)");
        register("geocent", GeocentProjection.class, "Geocentric");
        register("geos", GeostationarySatelliteProjection.class, "Geostationary Satellite");
        register("gins8", Ginsburg8Projection.class, "Ginsburg VIII (TsNIIGAiK)");
        register("gn_sinu", GeneralSinusoidalProjection.class, "General Sinusoidal Series");
        register("gnom", GnomonicAzimuthalProjection.class, "Gnomonic");
        register("gstmerc", GaussSchreiberTransverseMercatorProjection.class,
                "Gauss-Schreiber Transverse Mercator (aka Gauss-Laborde Reunion)");
        register("goode", GoodeProjection.class, "Goode Homolosine");
        register("guyou", GuyouProjection.class, "Guyou");
        register("gs48", ModifiedStereographic48Projection.class,
                "Mod. Stereographic of 48 U.S.");
        register("gs50", ModifiedStereographic50Projection.class,
                "Mod. Stereographic of 50 U.S.");
        register("hammer", HammerProjection.class, "Hammer & Eckert-Greifendorff");
        register("hatano", HatanoProjection.class, "Hatano Asymmetrical Equal Area");
        /*
         * The interrupted family, one class per upstream file: igh.cpp, igh_o.cpp, imoll.cpp and
         * imoll_o.cpp all dispatch over the Mollweide and (for the igh pair) sinusoidal children
         * this library already had. Note "goode" above is the UNinterrupted Goode Homolosine and
         * is a different operator, not an alias.
         */
        register("igh", InterruptedGoodeHomolosineProjection.class,
                "Interrupted Goode Homolosine");
        register("igh_o", InterruptedGoodeHomolosineOceanicProjection.class,
                "Interrupted Goode Homolosine Oceanic View");
        register("imoll", InterruptedMollweideProjection.class, "Interrupted Mollweide");
        register("imoll_o", InterruptedMollweideOceanicProjection.class,
                "Interrupted Mollweide Oceanic View");
        register("imw_p", InternationalMapOfTheWorldPolyconicProjection.class,
                "International Map of the World Polyconic");
        register("kav5", KavraiskyVProjection.class, "Kavraisky V");
        register("kav7", Kavrayskiy7Projection.class, "Kavrayskiy VII");
        register("krovak", KrovakProjection.class, "Krovak");
        register("labrd", LabordeProjection.class, "Laborde");
        register("laea", LambertAzimuthalEqualAreaProjection.class, "Lambert Azimuthal Equal Area");
        register("lagrng", LagrangeProjection.class, "Lagrange");
        register("larr", LarriveeProjection.class, "Larrivee");
        register("lask", LaskowskiProjection.class, "Laskowski");
        register("latlong", LongLatProjection.class, "Lat/Long (Geodetic alias)");
        register("longlat", LongLatProjection.class, "Lat/Long (Geodetic alias)");
        register("latlon", LongLatProjection.class, "Lat/Long (Geodetic alias)");
        register("lonlat", LongLatProjection.class, "Lat/Long (Geodetic)");
        register("lcc", LambertConformalConicProjection.class, "Lambert Conformal Conic");
        register("lcca", LambertConformalConicAlternativeProjection.class,
                "Lambert Conformal Conic Alternative");
        register("leac", LambertEqualAreaConicProjection.class, "Lambert Equal Area Conic");
        register("lee_os", LeeOblatedStereographicProjection.class, "Lee Oblated Stereographic");
        register("loxim", LoximuthalProjection.class, "Loximuthal");
        register("lsat", LandsatProjection.class, "Space oblique for LANDSAT");
        register("mbt_s", McBrydeThomasFlatPolarSine1Projection.class, "McBryde-Thomas Flat-Polar Sine (No. 1)");
        register("mbt_fps", McBrydeThomasFlatPolarSine2Projection.class, "McBryde-Thomas Flat-Pole Sine (No. 2)");
        register("mbtfpp", McBrydeThomasFlatPolarParabolicProjection.class, "McBride-Thomas Flat-Polar Parabolic");
        register("mbtfpq", McBrydeThomasFlatPolarQuarticProjection.class, "McBryde-Thomas Flat-Polar Quartic");
        register("mbtfps", McBrydeThomasFlatPolarSinusoidalProjection.class, "McBryde-Thomas Flat-Polar Sinusoidal");
        register("merc", MercatorProjection.class, "Mercator");
        register("mil_os", MillerOblatedStereographicProjection.class,
                "Miller Oblated Stereographic");
        register("misrsom", MisrSpaceObliqueMercatorProjection.class,
                "Space oblique for MISR");
        register("mill", MillerProjection.class, "Miller Cylindrical");
        // register( "mpoly", Projection.class, "Modified Polyconic" );
        register("moll", MolleweideProjection.class, "Mollweide");
        register("murd1", Murdoch1Projection.class, "Murdoch I");
        register("murd2", Murdoch2Projection.class, "Murdoch II");
        register("murd3", Murdoch3Projection.class, "Murdoch III");
        register("natearth", NaturalEarthProjection.class, "Natural Earth");
        register("natearth2", NaturalEarth2Projection.class, "Natural Earth 2");
        register("patterson", PattersonProjection.class, "Patterson Cylindrical");
        register("comill", CompactMillerProjection.class, "Compact Miller");
        register("nell", NellProjection.class, "Nell");
        register("nell_h", NellHProjection.class, "Nell-Hammer");
        register("nicol", NicolosiProjection.class, "Nicolosi Globular");
        register("nsper", PerspectiveProjection.class, "Near-sided perspective");
        register("nzmg", NewZealandMapGridProjection.class, "New Zealand Map Grid");
        register("ob_tran", ObliqueTransformationProjection.class,
                "General Oblique Transformation");
        register("ocea", ObliqueCylindricalEqualAreaProjection.class,
                "Oblique Cylindrical Equal Area");
        // register( "oea", Projection.class, "Oblated Equal Area" );
        register("omerc", ObliqueMercatorProjection.class, "Oblique Mercator");
        register("ortel", OrteliusOvalProjection.class, "Ortelius Oval");
        register("ortho", OrthographicAzimuthalProjection.class, "Orthographic");
        register("pconic", PerspectiveConicProjection.class, "Perspective Conic");
        register("peirce_q", PeirceQuincuncialProjection.class, "Peirce Quincuncial");
        register("poly", PolyconicProjection.class, "Polyconic (American)");
        register("putp1", PutninsP1Projection.class, "Putnins P1");
        register("putp2", PutninsP2Projection.class, "Putnins P2");
        register("putp3", PutninsP3Projection.class, "Putnins P3");
        register("putp3p", PutninsP3PProjection.class, "Putnins P3'");
        register("putp4p", PutninsP4Projection.class, "Putnins P4'");
        register("putp5", PutninsP5Projection.class, "Putnins P5");
        register("putp5p", PutninsP5PProjection.class, "Putnins P5'");
        register("putp6", PutninsP6Projection.class, "Putnins P6");
        register("putp6p", PutninsP6PProjection.class, "Putnins P6'");
        register("qua_aut", QuarticAuthalicProjection.class, "Quartic Authalic");
        register("robin", RobinsonProjection.class, "Robinson");
        register("rpoly", RectangularPolyconicProjection.class, "Rectangular Polyconic");
        register("sinu", SinusoidalProjection.class, "Sinusoidal (Sanson-Flamsteed)");
        register("som", SpaceObliqueMercatorProjection.class, "Space Oblique Mercator");
        register("somerc", SwissObliqueMercatorProjection.class, "Swiss Oblique Mercator");
        register("spilhaus", SpilhausProjection.class, "Spilhaus");
        register("stere", StereographicAzimuthalProjection.class, "Stereographic");
        register("sterea", ObliqueStereographicAlternativeProjection.class, "Oblique Stereographic Alternative");
        register("tcc", TranverseCentralCylindricalProjection.class, "Transverse Central Cylindrical");
        register("tcea", TransverseCylindricalEqualArea.class, "Transverse Cylindrical Equal Area");
        register("tissot", TissotProjection.class, "Tissot Conic");
        register("tmerc", TransverseMercatorProjection.class, "Transverse Mercator");
        register("times", TimesProjection.class, "Times");
        register("tobmerc", ToblerMercatorProjection.class, "Tobler-Mercator");
        register("col_urban", ColombiaUrbanProjection.class, "Colombia Urban");
        register("webmerc", WebMercatorProjection.class, "Web Mercator / Pseudo Mercator");
        register("etmerc", ExtendedTransverseMercatorProjection.class, "Extended Transverse Mercator");
        register("tpeqd", TwoPointEquidistantProjection.class, "Two Point Equidistant");
        /*
         * tpers was DELIBERATELY unregistered until its two parameters could be
         * dispatched, and the ordering is worth keeping on the record.
         *
         * TiltedPerspectiveProjection has been a complete port of nsper.cpp's
         * PJ_PROJECTION(tpers) for some time, but Proj4Parser sent +azi to
         * SpilhausProjection and nowhere else, so registering this name on its own
         * would have made `+proj=tpers +azi=20` a SILENT WRONG ANSWER: the azimuth
         * parsed, passed the STRICT allow-list and was then dropped - the same
         * defect as `+proj=peirce_q +shape=square` projecting as a diamond. (`+tilt`
         * was not a Proj4Keyword at all, so that half already failed closed.)
         *
         * Both halves now exist - Proj4Keyword.tilt is registered, and
         * Proj4Parser.parseProjection has an `instanceof TiltedPerspectiveProjection`
         * branch reading +azi and +tilt through parseAngleRadians (both are pj_param
         * 'r' sigils, nsper.cpp:186-187) - so this registration is safe. It unblocks
         * 17 builtins.gie assertions.
         *
         * The rule the episode establishes: registering a +proj= name whose operator
         * reads a parameter this parser does not dispatch to it is strictly worse
         * than leaving the name unregistered, because NOT_IMPLEMENTED is honest and a
         * dropped parameter is not.
         */
        register("tpers", TiltedPerspectiveProjection.class, "Tilted perspective");
        register("ups", UniversalPolarStereographicProjection.class,
                "Universal Polar Stereographic");
        register("urm5", Urmaev5Projection.class, "Urmaev V");
        register("urmfps", UrmaevFlatPolarSinusoidalProjection.class, "Urmaev Flat-Polar Sinusoidal");
        /*
         * utm binds to TransverseMercatorProjection, not to the Poder/Engsager-only
         * ExtendedTransverseMercatorProjection.
         *
         * Upstream has one implementation file: PJ_PROJECTION(utm) and
         * PJ_PROJECTION(tmerc) both end in the same setup(P, algo) and differ only in
         * which parameters they read and in utm's es != 0 requirement (tmerc.cpp).
         * Binding utm to the extended class made +approx and +algo unreachable for it -
         * there was no way to ask for the legacy algorithm on a UTM definition at all -
         * and it is also the class +zone is already dispatched on in Proj4Parser, so
         * this makes the two consistent rather than introducing a new pattern.
         *
         * The es != 0 requirement moves with it: it is enforced in Proj4Parser, keyed on
         * the operation name being "utm", because TransverseMercatorProjection is
         * legitimately spherical for +proj=tmerc.
         */
        register("utm", TransverseMercatorProjection.class, "Universal Transverse Mercator (UTM)");
        register("vandg", VanDerGrintenProjection.class, "van der Grinten (I)");
        register("vandg2", VanDerGrinten2Projection.class, "van der Grinten II");
        register("vandg3", VanDerGrinten3Projection.class, "van der Grinten III");
        register("vandg4", VanDerGrinten4Projection.class, "van der Grinten IV");
        register("vitk1", VitkovskyProjection.class, "Vitkovsky I");
        register("wag1", Wagner1Projection.class, "Wagner I (Kavraisky VI)");
        register("wag2", Wagner2Projection.class, "Wagner II");
        register("wag3", Wagner3Projection.class, "Wagner III");
        register("wag4", Wagner4Projection.class, "Wagner IV");
        register("wag5", Wagner5Projection.class, "Wagner V");
        register("wag6", Wagner6Projection.class, "Wagner VI");
        register("wag7", Wagner7Projection.class, "Wagner VII");
        register("weren", WerenskioldProjection.class, "Werenskiold I");
        register("wink1", Winkel1Projection.class, "Winkel I");
        register("wink2", Winkel2Projection.class, "Winkel II");
        register("wintri", WinkelTripelProjection.class, "Winkel Tripel");
    }
}
