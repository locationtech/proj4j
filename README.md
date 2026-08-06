# neoProj4J

neoProj4J is a Java library for converting coordinates between different geospatial coordinate reference systems.
It is designed to be compatible with `proj.4` parameters and derives some of its implementation from the `proj.4` sources.

## About this fork

neoProj4J is a fork of [Proj4J](https://github.com/locationtech/proj4j), taken from upstream release
`v1.4.3` (commit [`7362c85`](https://github.com/locationtech/proj4j/commit/7362c85e34b37cf133e2cbc0a4d3d049b166a720)).

Upstream Proj4J is licensed under the Apache License 2.0 and is a project of the LocationTech working group
of the Eclipse Foundation. All of that work, and the `proj.4` and JMapProjLib work it in turn builds on,
remains the property of its authors; this fork preserves the Apache 2.0 licence and every copyright and
attribution notice unchanged, and is indebted to the people who wrote them.

neoProj4J is an independent fork. It is **not** a LocationTech or Eclipse Foundation project, is not
affiliated with or endorsed by either, and upstream is not responsible for it. Please report issues with
this fork here, not to the upstream project.

How it differs: neoProj4J targets parity with [PROJ](https://proj.org/) 9.8.1 — projections, parameter
parsing, datum and grid handling, and a `+proj=pipeline` engine — validated against PROJ's own `gie` and the
OGP GIGS conformance suites.

## User Guide

neoProj4J is published to Maven Central under its own coordinates. Note that the groupId is **not**
upstream's — depending on `org.locationtech.proj4j:proj4j` gets you upstream Proj4J, not this fork.

| artifact | | |
|---|---|---|
| `neoproj4j` | required | the library |
| `neoproj4j-epsg` | required in practice | the EPSG/ESRI dictionaries and the `conus` grid |
| `neoproj4j-geoapi` | optional | [GeoAPI](https://www.geoapi.org/) wrappers |
| `neoproj4j-db` | optional | PROJ 9.8.1's authority database, as a pure-Java reader |
| `neoproj4j-grids-us-legacy` | optional | PROJ's CTABLE V2 US grids (`conus`, `alaska`) |

All five share the groupId `io.github.emilevictor.neoproj4j` and are versioned together. The current
release is `2.0.0`.

**!Important!** The core artifact contains no EPSG-licensed files. Add `neoproj4j-epsg` unless you
supply CRS definitions yourself — without it, `createFromName("epsg:4326")` cannot resolve anything.

### Using neoProj4J with Maven

To include neoProj4J in a Maven project, add a dependency block like the following:
```xml
<properties>
    <neoproj4j.version>2.0.0</neoproj4j.version>
</properties>
<dependency>
    <groupId>io.github.emilevictor.neoproj4j</groupId>
    <artifactId>neoproj4j</artifactId>
    <version>${neoproj4j.version}</version>
</dependency>
```

#### neoProj4J EPSG

The `neoproj4j-epsg` module distributes a portion of the EPSG dataset, under the [EPSG database distribution license](LICENSE.EPSG). It also redistributes PROJ 9.8.1's `conus` datum-shift grid verbatim under [PROJ's MIT license](LICENSE.PROJ), which is what makes NAD27 transforms shift correctly across the conterminous United States with only `neoproj4j` and `neoproj4j-epsg` on the classpath.

```xml
<dependency>
    <groupId>io.github.emilevictor.neoproj4j</groupId>
    <artifactId>neoproj4j-epsg</artifactId>
    <version>${neoproj4j.version}</version>
</dependency>
```

#### Using neoProj4J with GeoAPI

The `neoproj4j-geoapi` module provides wrappers for using neoProj4J behind [GeoAPI](https://www.geoapi.org/) interfaces.
GeoAPI is a set of Java interfaces derived from OGC/ISO standards
for using different implementations of metadata and referencing services through a standard API.

```xml
<dependency>
    <groupId>io.github.emilevictor.neoproj4j</groupId>
    <artifactId>neoproj4j-geoapi</artifactId>
    <version>${neoproj4j.version}</version>
</dependency>
```
Usage examples are available on the [GeoAPI site](https://www.geoapi.org/java/examples/usage.html).

#### The PROJ authority database

The `neoproj4j-db` module carries PROJ 9.8.1's authority database — EPSG v12.029, ESRI, IGNF,
IAU_2015 and NKG — transcoded to a deterministic read-only binary index and read by a pure-Java
reader. There is no SQLite driver and no native library. It is opt-in: the core never scans for it,
and adding it to the classpath is what enables it. See [db/README.md](db/README.md).

```xml
<dependency>
    <groupId>io.github.emilevictor.neoproj4j</groupId>
    <artifactId>neoproj4j-db</artifactId>
    <version>${neoproj4j.version}</version>
</dependency>
```

#### Legacy US datum-shift grids

The `neoproj4j-grids-us-legacy` module vendors PROJ's in-tree CTABLE V2 grids (`conus` and `alaska`)
verbatim, with their blob checksums recorded in the manifest. Without a reachable grid, a
NAD27 → NAD83 transform has no datum shift to apply. Only 2 of PROJ's 7 US grids exist in CTABLE V2
form; the other 5 are GeoTIFF-only. See [grids-us-legacy/README.md](grids-us-legacy/README.md).

```xml
<dependency>
    <groupId>io.github.emilevictor.neoproj4j</groupId>
    <artifactId>neoproj4j-grids-us-legacy</artifactId>
    <version>${neoproj4j.version}</version>
</dependency>
```

### Using neoProj4J with Gradle

```groovy
dependencies {
    implementation 'io.github.emilevictor.neoproj4j:neoproj4j:2.0.0'
    implementation 'io.github.emilevictor.neoproj4j:neoproj4j-epsg:2.0.0'

    // optional
    implementation 'io.github.emilevictor.neoproj4j:neoproj4j-geoapi:2.0.0'
    implementation 'io.github.emilevictor.neoproj4j:neoproj4j-db:2.0.0'
    implementation 'io.github.emilevictor.neoproj4j:neoproj4j-grids-us-legacy:2.0.0'
}
```

See the Maven sections above for what each artifact contains.

### Basic Usage

The following examples give a quick intro on how to use neoProj4J in common
use cases.

#### Transforming coordinates from WGS84 to UTM

##### Obtaining CRSs by name

```Java
CRSFactory crsFactory = new CRSFactory();
CoordinateReferenceSystem WGS84 = crsFactory.createFromName("epsg:4326");
CoordinateReferenceSystem UTM = crsFactory.createFromName("epsg:25833");
```

##### Obtaining CRSs using parameters

```Java
CRSFactory crsFactory = new CRSFactory();
CoordinateReferenceSystem WGS84 = crsFactory.createFromParameters("WGS84",
    "+proj=longlat +datum=WGS84 +no_defs");
CoordinateReferenceSystem UTM = crsFactory.createFromParameters("UTM",
    "+proj=utm +zone=33 +ellps=GRS80 +towgs84=0,0,0,0,0,0,0 +units=m +no_defs");
```

##### Transforming coordinates

```Java
CoordinateTransformFactory ctFactory = new CoordinateTransformFactory();
CoordinateTransform wgsToUtm = ctFactory.createTransform(WGS84, UTM);
// `result` is an output parameter to `transform()`
ProjCoordinate result = new ProjCoordinate();
wgsToUtm.transform(new ProjCoordinate(lon, lat), result);
```

## Building and testing

```
mvn clean verify
```

Build with JDK 21. The modules target Java 8 bytecode, but JDK 8 cannot run the build itself, and
JDK 23+ silently stops running the classpath annotation processors the benchmark harness needs.

## Installing locally, for a downstream project to test against

A plain `mvn install` reports BUILD SUCCESS and installs almost nothing: the root POM sets
`maven.install.skip=true` and only two modules override it, so the reactor succeeds while `~/.m2`
ends up without the core artifact or the parent POM. Use:

```
mvn -B -DskipTests -Dmaven.javadoc.skip=true -Dgpg.skip=true \
    -Dmaven.install.skip=false -pl '!conformance' install
```

That installs the parent POM plus all five published modules, each with a `-sources` jar.

## Publishing to Maven Central

```
mvn -Pcentral deploy -DskipTests -pl '!conformance'
```

Every flag there matters, the tag must be in place before you start, and a green build means
"staged", not "released". Read [HOWTORELEASE.txt](./HOWTORELEASE.txt) first — in particular the part
about publishing your public key to a keyserver, which is the step that will otherwise fail the
deployment with an error that does not name the cause.

## Contributing

If you are interested in contributing to neoProj4J please read the [**Contributing Guide**](CONTRIBUTING.md).
