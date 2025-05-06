# Proj4J [![GitHub Action Status](https://github.com/locationtech/proj4j/workflows/CI/badge.svg)](https://github.com/locationtech/proj4j/actions) [![Maven Central](https://img.shields.io/maven-central/v/org.locationtech.proj4j/proj4j)](https://search.maven.org/search?q=g:org.locationtech.proj4j%20AND%20a:proj4j)

Proj4J is a Java library for converting coordinates between different geospatial coordinate reference systems.
It is designed to be compatible with `proj.4` parameters and derives some of its implementation from the `proj.4` sources.

Proj4J is a project in the [LocationTech](http://www.locationtech.org) working group of the Eclipse Foundation.

![LocationTech](locationtech_mark.png)

## User Guide

Proj4J artifacts are available on maven central.

**!Important!** As of `1.2.2` version, `proj4-core` contains no EPSG Licensed files.
In order to make proj4j properly operate, it makes sense to consider `proj4-epsg` dependency usage.

### Using Proj4J with Maven

To include Proj4J in a Maven project, add a dependency block like the following:
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
where `{latest version}` refers to the version indicated by the badge above.

#### Proj4j EPSG

`Proj4J-EPSG` module distributes a portion of the EPSG dataset. This artifact is released the [EPSG database distribution license](https://raw.githubusercontent.com/locationtech/proj4j/master/LICENSE.EPSG).

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
where `{latest version}` refers to the version indicated by the badge above.

#### Using Proj4j with GeoAPI

`Proj4j-GeoAPI` module provides wrappers for using Proj4J behind [GeoAPI](https://www.geoapi.org/) interfaces.
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
where `{latest version}` refers to the version indicated by the badge above.
Usage examples are available on the [GeoAPI site](https://www.geoapi.org/java/examples/usage.html).

### Using Proj4J with Gradle

To include Proj4J in a Gradle project, add a dependency block like the following:

```
dependencies {
    implementation 'org.locationtech.proj4j:proj4j:{latest version}'
}
```
where `{latest version}` refers to the version indicated by the badge above.

#### Proj4j EPSG

`Proj4J-EPSG` module distributes a portion of the EPSG dataset. This artifact is released the [EPSG database distribution license](https://raw.githubusercontent.com/locationtech/proj4j/master/LICENSE.EPSG).

To include `Proj4J-EPSG` in a Gradle project, add the following line to the dependency block:

```
    implementation 'org.locationtech.proj4j:proj4j-epsg:{latest version}'
```
where `{latest version}` refers to the version indicated by the badge above.

#### Using Proj4j with GeoAPI

`Proj4j-GeoAPI` module provides wrappers for using Proj4J behind [GeoAPI](https://www.geoapi.org/) interfaces.
GeoAPI is a set of Java interfaces derived from OGC/ISO standards
for using different implementations of metadata and referencing services through a standard API.
To include the module in a Gradle project, add the following line to the dependency block:

```
    implementation 'org.locationtech.proj4j:proj4j-geoapi:{latest version}'
```
where `{latest version}` refers to the version indicated by the badge above.
Usage examples are available on the [GeoAPI site](https://www.geoapi.org/java/examples/usage.html).

### Basic Usage

The following examples give a quick intro on how to use Proj4J in common
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

If you are interested in contributing to Proj4J please read the [**Contributing Guide**](CONTRIBUTING.md).
