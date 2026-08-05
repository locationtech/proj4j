# neoProj4J

<!-- TODO(coordinates): add CI and artifact-repository badges for this fork once the
     coordinates and the publishing pipeline are settled. -->

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

<!-- TODO(coordinates): this fork is not published to Maven Central; state where its artifacts
     are actually published once the coordinates are settled. -->

**!Important!** As of `1.2.2` version, `proj4-core` contains no EPSG Licensed files.
In order to make neoProj4J properly operate, it makes sense to consider the `proj4-epsg` dependency usage.

### Using neoProj4J with Maven

<!-- TODO(coordinates): groupId/artifactId in the snippet below are still upstream's. -->

To include neoProj4J in a Maven project, add a dependency block like the following:
```xml
<properties>
    <proj4j.version>{latest version}</proj4j.version>
</properties>
<dependency>
    <groupId>org.locationtech.proj4j</groupId>
    <artifactId>proj4j</artifactId>
    <version>${proj4j.version}</version>
</dependency>
```
where `{latest version}` refers to the latest released version.

#### Proj4j EPSG

`Proj4J-EPSG` module distributes a portion of the EPSG dataset. This artifact is released the [EPSG database distribution license](LICENSE.EPSG). It also redistributes PROJ 9.8.1's `conus` datum-shift grid verbatim under [PROJ's MIT license](LICENSE.PROJ), which is what makes NAD27 transforms shift correctly across the conterminous United States with only `proj4j` and `proj4j-epsg` on the classpath.

To include `Proj4J-EPSG` in a Maven project, add a dependency block like the following:
```xml
<properties>
    <proj4j.version>{latest version}</proj4j.version>
</properties>
<dependency>
    <groupId>org.locationtech.proj4j</groupId>
    <artifactId>proj4j-epsg</artifactId>
    <version>${proj4j.version}</version>
</dependency>
```
where `{latest version}` refers to the latest released version.

#### Using Proj4j with GeoAPI

`Proj4j-GeoAPI` module provides wrappers for using neoProj4J behind [GeoAPI](https://www.geoapi.org/) interfaces.
GeoAPI is a set of Java interfaces derived from OGC/ISO standards
for using different implementations of metadata and referencing services through a standard API.
To include the module in a Maven project, add a dependency block like the following:
```xml
<properties>
    <proj4j.version>{latest version}</proj4j.version>
</properties>
<dependency>
    <groupId>org.locationtech.proj4j</groupId>
    <artifactId>proj4j-geoapi</artifactId>
    <version>${proj4j.version}</version>
</dependency>
```
where `{latest version}` refers to the latest released version.
Usage examples are available on the [GeoAPI site](https://www.geoapi.org/java/examples/usage.html).

### Using neoProj4J with Gradle

<!-- TODO(coordinates): group/artifact in the snippets in this section are still upstream's. -->

To include neoProj4J in a Gradle project, add a dependency block like the following:

```
dependencies {
    implementation 'org.locationtech.proj4j:proj4j:{latest version}'
}
```
where `{latest version}` refers to the latest released version.

#### Proj4j EPSG

`Proj4J-EPSG` module distributes a portion of the EPSG dataset. This artifact is released the [EPSG database distribution license](LICENSE.EPSG). It also redistributes PROJ 9.8.1's `conus` datum-shift grid verbatim under [PROJ's MIT license](LICENSE.PROJ), which is what makes NAD27 transforms shift correctly across the conterminous United States with only `proj4j` and `proj4j-epsg` on the classpath.

To include `Proj4J-EPSG` in a Gradle project, add the following line to the dependency block:

```
    implementation 'org.locationtech.proj4j:proj4j-epsg:{latest version}'
```
where `{latest version}` refers to the latest released version.

#### Using Proj4j with GeoAPI

`Proj4j-GeoAPI` module provides wrappers for using neoProj4J behind [GeoAPI](https://www.geoapi.org/) interfaces.
GeoAPI is a set of Java interfaces derived from OGC/ISO standards
for using different implementations of metadata and referencing services through a standard API.
To include the module in a Gradle project, add the following line to the dependency block:

```
    implementation 'org.locationtech.proj4j:proj4j-geoapi:{latest version}'
```
where `{latest version}` refers to the latest released version.
Usage examples are available on the [GeoAPI site](https://www.geoapi.org/java/examples/usage.html).

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

## Building, Testing and installing locally

`mvn clean install`

## Publish to Maven Central

`mvn -Dmaven.test.skip=true -Pcentral clean package deploy`

For more details see [HOWTORELEASE.txt](./HOWTORELEASE.txt).

## Contributing

If you are interested in contributing to neoProj4J please read the [**Contributing Guide**](CONTRIBUTING.md).
