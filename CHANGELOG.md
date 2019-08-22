# Changelog
All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- Added `GeostationarySatelliteProjection`/`geos` projection by [@Yaqiang](https://github.com/Yaqiang)
- Registry.getProjections exposes all available projects by [@noberasco](https://github.com/noberasco)
- OSGi compatibility by [@Neutius](https://github.com/Neutius)

### Changed
- Parse `geos` (Geostationary Satellite Projection) proj4 strings by [@pomadchin](https://github.com/pomadchin)
- Projection units reported as meters by default by [@bosborn](https://github.com/bosborn)
- BasicCoordinateTransform now thread-safe by [@sebasbaumh](https://github.com/sebasbaumh)
- Improve CRS Caching performance by [@pomadchin](https://github.com/pomadchin)
- CoordinateReferenceSystem.equals considered logical equality by [@pomadchin](https://github.com/pomadchin)

## [1.0.0] - 2019-12-12

### Added
- Added support for Extended Transverse Mercator

### Changed
- Update EPSG DB v9.2
- Increasing accuracy of `etmerc` projection

### Fixed
- Fix possible `null` dereference
- Fix `cea` (Cylindrical Equal Area) projection
