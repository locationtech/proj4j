# Notices for Proj4J

This content is produced and maintained by the LocationTech Proj4J project.

 * Project home: https://projects.eclipse.org/projects/locationtech.proj4j

## Trademarks

LocationTech Proj4J, and Proj4J are trademarks of the Eclipse Foundation.

## Copyright

All content is the property of the respective authors or their employers. For
more information regarding authorship of content, please consult the listed
source code repository logs.

## Declared Project Licenses

This program and the accompanying materials are made available under the terms
of the Apache License, Version 2.0 which is available at
https://www.apache.org/licenses/LICENSE-2.0.

SPDX-License-Identifier: Apache-2.0

The `proj4j-epsg` module additionally distributes a portion of the EPSG Geodetic
Parameter Dataset, which is made available under the EPSG Terms of Use rather
than under the Apache License, Version 2.0. See `LICENSE.EPSG` in the root of
this repository, and the "Third-party Content" section below.

## Source Code

The project maintains the following source code repository:

 * https://github.com/locationtech/proj4j

## Third-party Content

### PROJ (formerly proj.4)

Parts of the `proj4j-core` module are a Java port of, or were semi-automatically
converted from, the PROJ source.

 * License: MIT
 * Project: https://proj.org
 * Source: https://github.com/OSGeo/PROJ

```
Copyright (c) 2000, Frank Warmerdam

Permission is hereby granted, free of charge, to any person obtaining a
copy of this software and associated documentation files (the "Software"),
to deal in the Software without restriction, including without limitation
the rights to use, copy, modify, merge, publish, distribute, sublicense,
and/or sell copies of the Software, and to permit persons to whom the
Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included
in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS
OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL
THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
DEALINGS IN THE SOFTWARE.
```

### GeographicLib (Java implementation of `net.sf.geographiclib`)

The `org.locationtech.proj4j.geodesic` package is an implementation of the
`net.sf.geographiclib` classes.

 * License: MIT/X11
 * Project: https://geographiclib.sourceforge.io/
 * Source: https://github.com/geographiclib/geographiclib-java

```
Copyright (c) Charles Karney (2013-2022) <charles@karney.com>

Permission is hereby granted, free of charge, to any person obtaining a
copy of this software and associated documentation files (the "Software"),
to deal in the Software without restriction, including without limitation
the rights to use, copy, modify, merge, publish, distribute, sublicense,
and/or sell copies of the Software, and to permit persons to whom the
Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included
in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS
OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL
THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
DEALINGS IN THE SOFTWARE.
```

### EPSG Geodetic Parameter Dataset

The resource files distributed by the `proj4j-epsg` module contain a portion of
the EPSG Geodetic Parameter Dataset, which is owned by IOGP and made available
under the EPSG Terms of Use. The full terms are reproduced in `LICENSE.EPSG` in
the root of this repository.

See https://github.com/locationtech/proj4j/issues/90 for more details.

 * License: EPSG Terms of Use (see `LICENSE.EPSG`)
 * Project: https://epsg.org
 * Copyright: International Association of Oil & Gas Producers (IOGP)

### Java Map Projection Library (JMapProjLib)

Parts of the `proj4j-core` module derive from Jerry Huxtable's Java Map
Projection Library, contributed under the Apache License, Version 2.0.

 * License: Apache License, 2.0
 * Project: https://github.com/OSUCartography/JMapProjLib
