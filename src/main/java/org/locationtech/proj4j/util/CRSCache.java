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
package org.locationtech.proj4j.util;

import org.locationtech.proj4j.*;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.io.IOException;

public class CRSCache {
    private static CRSFactory crsFactory = new CRSFactory();
    private Cache<String, CoordinateReferenceSystem> crsCache = Caffeine.newBuilder().build();
    private Cache<String, String> epsgCache = Caffeine.newBuilder().build();

    public CRSCache CRSCache() {
        crsCache = Caffeine.newBuilder().build();
        return this;
    }

    public CRSCache CRSCache(Cache<String, CoordinateReferenceSystem> crsCache, Cache<String, String> epsgCache) {
        this.crsCache = crsCache;
        this.epsgCache = epsgCache;
        return this;
    }

    public CoordinateReferenceSystem createFromName(String name)
            throws UnsupportedParameterException, InvalidValueException, UnknownAuthorityCodeException {
        return crsCache.get(name, k -> crsFactory.createFromName(name));
    }

    public CoordinateReferenceSystem createFromParameters(String name, String paramStr)
            throws UnsupportedParameterException, InvalidValueException {
        String nonNullName = name == null ? "" : name;
        return crsCache.get(nonNullName + paramStr, k -> crsFactory.createFromParameters(name, paramStr));
    }

    public CoordinateReferenceSystem createFromParameters(String name, String[] params)
            throws UnsupportedParameterException, InvalidValueException {
        String nonNullName = name == null ? "" : name;
        return crsCache.get(nonNullName + String.join(" ", params), k -> crsFactory.createFromParameters(name, params));
    }

    public String readEpsgFromParameters(String paramStr) {
        return epsgCache.get(paramStr, k -> { try { return crsFactory.readEpsgFromParameters(paramStr); } catch (IOException e) {  return null; } });
    }

    public String readEpsgFromParameters(String[] params) {
        return epsgCache.get(String.join(" ", params), k -> { try { return crsFactory.readEpsgFromParameters(params); } catch (IOException e) {  return null; } });
    }
}
