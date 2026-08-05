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

package org.locationtech.proj4j.util.trig.repoint;

import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.proj.Projection;
import org.locationtech.proj4j.util.AuthalicLat;
import org.locationtech.proj4j.util.AuxLat;
import org.locationtech.proj4j.util.Clenshaw6;
import org.locationtech.proj4j.util.ConformalLat;

import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Produces a deterministic dump of the <em>raw bit patterns</em> of every value reachable through
 * a call site that the {@code StrictMath} &rarr; {@code FastStrictTrig} re-point touched, reduced
 * to one SHA-256 per code path.
 *
 * <p>The digests are the mechanism by which
 * {@link RepointBitIdentityTest} makes a before/after claim that outlives the change: they were
 * captured by compiling and running this exact class against the tree <em>as it stood before</em>
 * the re-point, in a frozen {@code /tmp} copy, and are pinned as constants there. Nothing in this
 * package computes an expected value from a formula, so nothing here can agree with the
 * implementation by construction.
 *
 * <p>Only {@code Double.doubleToRawLongBits} is used. {@code ==} would call {@code -0.0} equal to
 * {@code 0.0} and every {@code NaN} unequal to itself, and a tolerance would defeat the purpose:
 * the whole justification for this change being risk-free is that it moves no bit, so the test
 * has to be able to see a single bit move.
 */
final class RepointDump {

    private RepointDump() {
    }

    /** The eight projection specifications whose kernels contain a re-pointed site. */
    static final String[] PROJECTION_SPECS = {
            "+proj=spilhaus +ellps=WGS84",
            "+proj=spilhaus +R=6371000",
            "+proj=adams_ws1 +ellps=WGS84",
            "+proj=adams_ws2 +ellps=WGS84",
            "+proj=adams_hemi +ellps=WGS84",
            "+proj=guyou +ellps=WGS84",
            "+proj=peirce_q +ellps=WGS84 +lat_0=90 +shape=square",
            "+proj=peirce_q +ellps=WGS84 +lat_0=90 +shape=diamond",
    };

    /**
     * Every code path, keyed by a stable tag. The values are SHA-256 hex digests over the
     * concatenated raw-bit hex of every {@code double} the path produced.
     *
     * @return an insertion-ordered map from tag to digest
     */
    static Map<String, String> digests() {
        Map<String, String> out = new LinkedHashMap<String, String>();
        out.put("clenshaw6", clenshaw6());
        out.put("conformalLat", conformalLat());
        out.put("authalicLat", authalicLat());
        for (String spec : PROJECTION_SPECS) {
            out.put(spec, projection(spec));
        }
        out.put("lsatForward", lsatForward());
        return out;
    }

    // ------------------------------------------------------------------
    // Clenshaw6: convert(double) at :170 and convertSinCos at :190-191
    // ------------------------------------------------------------------

    private static String clenshaw6() {
        Sink sink = new Sink();
        double[] ns = {0.0016792203863837047, 0.0, 0.005, 0.05, -0.05, 0.2};
        double[] scratch = new double[2];
        for (double n : ns) {
            for (int in = 0; in < AuxLat.NUMBER; in++) {
                for (int o = 0; o < AuxLat.NUMBER; o++) {
                    if (in == o) {
                        continue;
                    }
                    Clenshaw6 c;
                    try {
                        c = Clenshaw6.forConversion(n, in, o);
                    } catch (RuntimeException e) {
                        continue; // conversion not tabulated
                    }
                    for (int i = -3600; i <= 3600; i++) {
                        double zeta = i * (Math.PI / 3600.0); // spans [-pi, pi]
                        sink.add(c.convert(zeta));
                        c.convertSinCos(StrictMath.sin(zeta), StrictMath.cos(zeta), scratch);
                        sink.add(scratch[0]);
                        sink.add(scratch[1]);
                    }
                }
            }
        }
        return sink.digest();
    }

    // ------------------------------------------------------------------
    // ConformalLat: tsfn at :203, conformalLat at :240-241, inverse at :259
    // ------------------------------------------------------------------

    private static String conformalLat() {
        Sink sink = new Sink();
        double[] squaredEccentricities = {0.0, 0.00669438002290, 0.0066943799901413165, 0.2, 0.9};
        for (double es : squaredEccentricities) {
            double e = Math.sqrt(es);
            for (int i = -9000; i <= 9000; i++) {
                double phi = i * (Math.PI / 18000.0); // spans [-pi/2, pi/2]
                sink.add(ConformalLat.tsfn(phi, StrictMath.sin(phi), e));
                sink.add(ConformalLat.conformalLat(phi, e));
                try {
                    sink.add(ConformalLat.conformalLatInverse(phi, e));
                } catch (RuntimeException ex) {
                    sink.addToken(ex.getClass().getName());
                }
            }
        }
        return sink.digest();
    }

    // ------------------------------------------------------------------
    // AuthalicLat: forward(double) at :196, inverse's Newton fallback at :224-228
    // ------------------------------------------------------------------

    private static String authalicLat() {
        Sink sink = new Sink();
        // 0.00669438 gives n ~ 0.00168, the series branch; 0.5 and 0.9 force the Newton loop,
        // which is the only way the :224-228 sites are ever reached.
        double[] squaredEccentricities = {0.00669438002290, 0.05, 0.5, 0.9};
        for (double es : squaredEccentricities) {
            AuthalicLat al = new AuthalicLat(es);
            sink.addToken("seriesValid=" + al.isSeriesValid());
            for (int i = -9000; i <= 9000; i++) {
                double phi = i * (Math.PI / 18000.0);
                sink.add(al.forward(phi));
                sink.add(al.inverse(phi));
            }
        }
        return sink.digest();
    }

    // ------------------------------------------------------------------
    // The seven projections, forward and inverse, over a 3-degree graticule
    // ------------------------------------------------------------------

    private static String projection(String spec) {
        Sink sink = new Sink();
        Projection p;
        try {
            p = new CRSFactory().createFromParameters("t", spec).getProjection();
        } catch (RuntimeException e) {
            sink.addToken("crsFailed:" + e.getClass().getName());
            return sink.digest();
        }
        ProjCoordinate src = new ProjCoordinate();
        ProjCoordinate dst = new ProjCoordinate();
        ProjCoordinate back = new ProjCoordinate();
        for (int ilon = -180; ilon <= 180; ilon += 3) {
            for (int ilat = -90; ilat <= 90; ilat += 3) {
                src.x = ilon + 0.37;
                src.y = Math.max(-90.0, Math.min(90.0, ilat + 0.11));
                try {
                    p.project(src, dst);
                } catch (RuntimeException e) {
                    sink.addToken("fwd:" + e.getClass().getName());
                    continue;
                }
                sink.add(dst.x);
                sink.add(dst.y);
                try {
                    p.inverseProject(dst, back);
                } catch (RuntimeException e) {
                    sink.addToken("inv:" + e.getClass().getName());
                    continue;
                }
                sink.add(back.x);
                sink.add(back.y);
            }
        }
        return sink.digest();
    }

    // ------------------------------------------------------------------
    // lsat forward -- no re-pointed site of its own, but it shares nothing with the new
    // inverse either, so pinning it is what proves the inverse was added without disturbing it.
    // ------------------------------------------------------------------

    private static String lsatForward() {
        Sink sink = new Sink();
        Projection p;
        try {
            p = new CRSFactory().createFromParameters("t", "+proj=lsat +ellps=GRS80")
                    .getProjection();
        } catch (RuntimeException e) {
            sink.addToken("crsFailed:" + e.getClass().getName());
            return sink.digest();
        }
        ProjCoordinate src = new ProjCoordinate();
        ProjCoordinate dst = new ProjCoordinate();
        for (int ilon = -180; ilon <= 180; ilon += 5) {
            for (int ilat = -80; ilat <= 80; ilat += 5) {
                src.x = ilon;
                src.y = ilat;
                try {
                    p.project(src, dst);
                } catch (RuntimeException e) {
                    sink.addToken("fwd:" + e.getClass().getName());
                    continue;
                }
                sink.add(dst.x);
                sink.add(dst.y);
            }
        }
        return sink.digest();
    }

    // ------------------------------------------------------------------

    /** Accumulates raw bit patterns into a SHA-256. */
    private static final class Sink {

        private final MessageDigest md;
        private int count;

        Sink() {
            try {
                md = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 is required of every JRE", e);
            }
        }

        void add(double v) {
            long bits = Double.doubleToRawLongBits(v);
            for (int i = 56; i >= 0; i -= 8) {
                md.update((byte) (bits >>> i));
            }
            count++;
        }

        void addToken(String s) {
            try {
                md.update(s.getBytes("UTF-8"));
            } catch (UnsupportedEncodingException e) {
                throw new IllegalStateException("UTF-8 is required of every JRE", e);
            }
            count++;
        }

        String digest() {
            byte[] d = md.digest();
            StringBuilder sb = new StringBuilder(64);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xf, 16));
                sb.append(Character.forDigit(b & 0xf, 16));
            }
            return sb.append('/').append(count).toString();
        }
    }
}
