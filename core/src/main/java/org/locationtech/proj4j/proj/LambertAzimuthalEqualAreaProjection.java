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

/*
 * This file was semi-automatically converted from the public-domain USGS PROJ source.
 */
package org.locationtech.proj4j.proj;

import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.ProjectionException;
import org.locationtech.proj4j.util.AuthalicLat;
import org.locationtech.proj4j.util.FastStrictTrig;
import org.locationtech.proj4j.util.MathHelpers;
import org.locationtech.proj4j.util.ProjectionMath;

/**
 * Lambert Azimuthal Equal Area, {@code 9.8.1:src/projections/laea.cpp}.
 *
 * <h2>fdlibm trigonometry</h2>
 *
 * <p>Every {@code sin} and {@code cos} in this class — all 26 of them, in
 * {@link #initialize()}, {@link #project_s}, {@link #project_e},
 * {@link #projectInverse_s} and {@link #projectInverse_e} — goes through
 * {@link FastStrictTrig} rather than {@link Math}. {@code Math.sin}/{@code cos}/{@code tan} are
 * {@code @IntrinsicCandidate}: HotSpot substitutes a platform-specific implementation, so they
 * differ in the last bit between architectures. {@code FastStrictTrig} is a bit-identical,
 * allocation-free transcription of the fdlibm algorithm {@link StrictMath} uses; see its class
 * documentation for why {@code StrictMath} itself is not called.
 *
 * <p>Measured, not assumed. At the spherical north-polar aspect the hard argument-reduction case
 * {@code lplam = 3.1179938779914944} (just under {@code pi}) gave
 *
 * <pre>
 *   AArch64  Math.cos       -&gt; 0xbfeffdb812a39370
 *   x86-64   Math.cos       -&gt; 0xbfeffdb812a39371
 *   both     StrictMath.cos -&gt; 0xbfeffdb812a39371
 * </pre>
 *
 * so a bit-pinned test captured on an Apple-silicon machine failed on Intel. The
 * {@code StrictMath} value is also the <em>more accurate</em> one here: it is delta 0.0 against
 * PROJ 9.8.1, where the AArch64 intrinsic sat 1.86e-9 m low. {@code Math.sin} happens to agree
 * across both architectures at these arguments, which is why only {@code y} moved — that is
 * coincidence, not safety, since {@code sin} is intrinsified too.
 *
 * <p>{@code Math.sqrt}, {@code Math.abs}, {@code Math.asin} and {@code Math.atan2} are
 * deliberately left alone: none is an intrinsic candidate whose result can differ from
 * {@code StrictMath}, so converting them would move values for no benefit.
 */
public class LambertAzimuthalEqualAreaProjection extends Projection {

  private static final long serialVersionUID = 2680576002433982735L;

  private static final int N_POLE = 0;
  private static final int S_POLE = 1;
  private static final int EQUIT = 2;
  private static final int OBLIQ = 3;

  private int mode = 0;
  private double phi0;
  private double  sinb1; 
  private double  cosb1; 
  private double  xmf; 
  private double  ymf; 
  private double  mmf; 
  private double  qp; 
  private double  dd; 
  private double  rq; 
  private double  sinph0;
  private double  cosph0;

  /**
   * The order-6 authalic-latitude machinery, {@code 9.8.1:src/latitudes.cpp}. Replaces
   * {@code ProjectionMath.authset}/{@code authlat}/{@code qsfn}.
   */
  private AuthalicLat authalic;
  
	public LambertAzimuthalEqualAreaProjection() {
		this( false );
	}

	public LambertAzimuthalEqualAreaProjection( boolean south ) {
		//minLatitude = Math.toRadians(0);
		//maxLatitude = Math.toRadians(90);
		//projectionLatitude1 = south ? -ProjectionMath.QUARTERPI : ProjectionMath.QUARTERPI;
		//projectionLatitude2 = south ? -ProjectionMath.HALFPI : ProjectionMath.HALFPI;
		//initialize();
	}

	 public void initialize() {
	    super.initialize();
	    double t;

	    phi0 = projectionLatitude;

	    if (Math.abs((t = Math.abs(phi0)) - ProjectionMath.HALFPI) < ProjectionMath.EPS10) {
	      mode = phi0 < 0. ? S_POLE : N_POLE;
	    }
	    else if (Math.abs(t) < ProjectionMath.EPS10) {
	      mode = EQUIT;
	    }
	    else {
	      mode = OBLIQ;
	    }
	    if (!spherical) {
	      double sinphi;

	      e = Math.sqrt(es);
	      authalic = new AuthalicLat(es);
	      qp = authalic.qp();
	      mmf = .5 / (1. - es);
	      switch (mode) {
	      case N_POLE:
	      case S_POLE:
	        dd = 1.;
	        break;
	      case EQUIT:
	        dd = 1. / (rq = Math.sqrt(.5 * qp));
	        xmf = 1.;
	        ymf = .5 * qp;
	        break;
	      case OBLIQ:
	        rq = Math.sqrt(.5 * qp);
	        sinphi = FastStrictTrig.sin(phi0);
	        // 9.8.1:laea.cpp:283-285 takes the authalic latitude of phi0 through the
	        // direct phi -> xi series and then its sine and cosine, rather than
	        // sinb1 = q/qp with cosb1 = sqrt(1 - sinb1^2). The asin form loses relative
	        // accuracy near the poles, which is why upstream stopped using it.
	        final double b1 = authalic.forward(phi0, sinphi, FastStrictTrig.cos(phi0));
	        sinb1 = FastStrictTrig.sin(b1);
	        cosb1 = FastStrictTrig.cos(b1);
	        dd = FastStrictTrig.cos(phi0) / (Math.sqrt(1. - es * sinphi * sinphi) *
	           rq * cosb1);
	        ymf = (xmf = rq) / dd;
	        xmf *= dd;
	        break;
	      }
	    } else {
	      if (mode == OBLIQ) {
	        sinph0 = FastStrictTrig.sin(phi0);
	        cosph0 = FastStrictTrig.cos(phi0);
	      }
	    }
	 	}

	  public ProjCoordinate project(double lplam, double lpphi, ProjCoordinate out) {
      if (spherical) {
        project_s(lplam, lpphi, out);
      }
      else {
        project_e(lplam, lpphi, out);
      }
      return out;
	  }
	  
    public void project_s(double lplam, double lpphi, ProjCoordinate out) {
      double  coslam, cosphi, sinphi;

      sinphi = FastStrictTrig.sin(lpphi);
      cosphi = FastStrictTrig.cos(lpphi);
      coslam = FastStrictTrig.cos(lplam);
      switch (mode) {
      case EQUIT:
      case OBLIQ:
        if (mode == EQUIT)
          out.y = 1. + cosphi * coslam;
        else 
          out.y = 1. + sinph0 * sinphi + cosph0 * cosphi * coslam;

        if (out.y <= EPS10) throw new ProjectionException("F");
        out.x = (out.y = Math.sqrt(2. / out.y)) * cosphi * FastStrictTrig.sin(lplam);
        out.y *= mode == EQUIT ? sinphi :
           cosph0 * sinphi - sinph0 * cosphi * coslam;
        break;
      case N_POLE:
        coslam = -coslam;
      case S_POLE:
        if (Math.abs(lpphi + phi0) < EPS10) throw new ProjectionException("F");;
        out.y = ProjectionMath.QUARTERPI - lpphi * .5;
        out.y = 2. * (mode == S_POLE ? FastStrictTrig.cos(out.y) : FastStrictTrig.sin(out.y));
        out.x = out.y * FastStrictTrig.sin(lplam);
        out.y *= coslam;
        break;
      }
    }
    
    public void project_e(double lplam, double lpphi, ProjCoordinate out) {
      double coslam, sinlam, sinphi, q, sinb=0.0, cosb=0.0, b=0.0;

      coslam = FastStrictTrig.cos(lplam);
      sinlam = FastStrictTrig.sin(lplam);
      sinphi = FastStrictTrig.sin(lpphi);
      // 9.8.1:laea.cpp:39-46. xi is the authalic latitude from the direct phi -> xi
      // series; q is recovered as sin(xi)*qp (laea still needs the raw q for the polar
      // aspects), and sinb/cosb are sin(xi)/cos(xi) instead of q/qp and sqrt(1-sinb^2).
      final double xi = authalic.forward(lpphi, sinphi, FastStrictTrig.cos(lpphi));
      q = FastStrictTrig.sin(xi) * qp;
      if (mode == OBLIQ || mode == EQUIT) {
        sinb = FastStrictTrig.sin(xi);
        cosb = FastStrictTrig.cos(xi);
      }
      switch (mode) {
      case OBLIQ:
        b = 1. + sinb1 * sinb + cosb1 * cosb * coslam;
        break;
      case EQUIT:
        b = 1. + cosb * coslam;
        break;
      case N_POLE:
        b = ProjectionMath.HALFPI + lpphi;
        q = qp - q;
        break;
      case S_POLE:
        b = lpphi - ProjectionMath.HALFPI;
        q = qp + q;
        break;
      }
      if (Math.abs(b) < EPS10) throw new ProjectionException("F");
      
      switch (mode) {
      case OBLIQ:
      case EQUIT:
        if (mode == OBLIQ) {
          out.y = ymf * ( b = Math.sqrt(2. / b) )
             * (cosb1 * sinb - sinb1 * cosb * coslam);
        }
        else {
          out.y = (b = Math.sqrt(2. / (1. + cosb * coslam))) * sinb * ymf; 
        }
        out.x = xmf * b * cosb * sinlam;
        break;
      case N_POLE:
      case S_POLE:
        // 9.8.1:laea.cpp:75 uses 1e-15, not 0: at the pole `qp - q` is rounding noise and
        // sqrt of a denormal would put the point sub-millimetres off the origin.
        if (q >= 1e-15) {
          out.x = (b = Math.sqrt(q)) * sinlam;
          out.y = coslam * (mode == S_POLE ? b : -b);
        } else if (Double.isNaN(q) || Double.isNaN(sinlam) || Double.isNaN(coslam)) {
          // NaN in, NaN out -- and it has to be said explicitly, because `q >= 1e-15` is
          // FALSE for NaN and the `else` below would otherwise answer an undefined point
          // with the *origin*: a finite, plausible coordinate that every downstream
          // isFinite() guard passes, and that projectRadians then turns into exactly
          // (x_0, y_0) once a false easting is set. Measured before this line existed:
          // +proj=laea +lat_0=90 +lon_0=-150 +datum=WGS84 +x_0=1000000 +y_0=2000000 with
          // (NaN, NaN) returned (1000000.0, 2000000.0).
          //
          // This is not a deviation from upstream, it is upstream's *observable*
          // behaviour. 9.8.1:src/projections/laea.cpp:75-81 has the same `else` arm, but
          // it is unreachable for NaN: 9.8.1:src/trans.cpp:352-354 short-circuits any
          // coordinate carrying a NaN to all-NaN before the operation is ever invoked
          //   if (pj_coord_has_nans(coord)) coord.v[0..3] = quiet_NaN();
          //   else if (direction == PJ_FWD) pj_fwd4d(coord, P);
          // so laea_e_forward is simply not called. Confirmed against the shipped 9.8.1
          // `gie`: more_builtins.gie:795's own row ("When given NaNs, return NaNs",
          // tolerance 0) reports GOT nan nan for this very proj-string, and still does
          // with +x_0=1000000 added, which proves the zero never reaches fwd_finalize.
          //
          // Proj4J puts the rule here rather than in the funnel because
          // Projection.projectRadians documents NaN as *propagated through* the kernel
          // (its "NaN input is propagated, not rejected" section): the checks are skipped
          // and whatever project() returns comes back. Under that contract every kernel
          // must be NaN-transparent, and this arm was the one place in laea that was not.
          // All three of laea's other aspects, and both inverse arms, already are --
          // their guards (`|b| < EPS10`, `rho < EPS10`, `rh*.5 > 1.`, `0 == q`) are all
          // false for NaN, which is why the defect was confined to the polar forward.
          //
          // Both operands matter, not just q. A NaN *latitude* arrives as a NaN q; a NaN
          // *longitude* with a finite latitude arrives as NaN sinlam/coslam while q stays
          // finite, and at the pole itself q is below the 1e-15 floor, so
          // (NaN, +pi/2) also used to answer (0, 0).
          out.x = out.y = Double.NaN;
        } else
          out.x = out.y = 0.;
        break;
      }
    }
  
    public ProjCoordinate projectInverse(double xyx, double xyy, ProjCoordinate out) {
      if (spherical) {
        projectInverse_s(xyx, xyy, out);
      }
      else {
        projectInverse_e(xyx, xyy, out);
      }
      return out;
    }
    
    public void projectInverse_s(double xyx, double xyy, ProjCoordinate out) {
      double  cosz=0.0, rh, sinz=0.0;
      double lpphi, lplam;
      
      // MathHelpers.norm2, not Math.hypot: hypot is fdlibm and about ten times the cost of a
      // sqrt, and it buys overflow scaling this call cannot use. Both operands here are the
      // projected coordinate already divided by the semi-major axis, so |xyx|, |xyy| are of
      // order 1 -- squaring them cannot overflow or underflow. Same substitution etmerc
      // already carries; SolverBenchmark measures the pair head to head.
      rh = MathHelpers.norm2(xyx, xyy);
      if ((lpphi = rh * .5 ) > 1.) throw new ProjectionException("I_ERROR");
      lpphi = 2. * Math.asin(lpphi);
      if (mode == OBLIQ || mode == EQUIT) {
        sinz = FastStrictTrig.sin(lpphi);
        cosz = FastStrictTrig.cos(lpphi);
      }
      switch (mode) {
      case EQUIT:
        lpphi = Math.abs(rh) <= EPS10 ? 0. : Math.asin(xyy * sinz / rh);
        xyx *= sinz;
        xyy = cosz * rh;
        break;
      case OBLIQ:
        lpphi = Math.abs(rh) <= EPS10 ? phi0 :
          Math.asin(cosz * sinph0 + xyy * sinz * cosph0 / rh);
        xyx *= sinz * cosph0;
        xyy = (cosz - FastStrictTrig.sin(lpphi) * sinph0) * rh;
        break;
      case N_POLE:
        xyy = -xyy;
        lpphi = ProjectionMath.HALFPI - lpphi;
        break;
      case S_POLE:
        lpphi -= ProjectionMath.HALFPI;
        break;
      }
      lplam = (xyy == 0. && (mode == EQUIT || mode == OBLIQ)) ?
        0. : Math.atan2(xyx, xyy);
      out.x = lplam;
      out.y = lpphi;
    }
    
    public void projectInverse_e(double xyx, double xyy, ProjCoordinate out) {
      double lpphi, lplam;
      double cCe, sCe, q, rho, ab=0.0;

      switch (mode) {
      case EQUIT:
      case OBLIQ:
        if ((rho = MathHelpers.norm2(xyx /= dd, xyy *=  dd)) < EPS10) {
          lplam = 0.;
          lpphi = phi0;
          out.x = lplam;
          out.y = lpphi;
          return;
        }
        cCe = FastStrictTrig.cos(sCe = 2. * Math.asin(.5 * rho / rq));
        xyx *= (sCe = FastStrictTrig.sin(sCe));
        if (mode == OBLIQ) {
          q = qp * (ab = cCe * sinb1 + xyy * sCe * cosb1 / rho);
          xyy = rho * cosb1 * cCe - xyy * sinb1 * sCe;
        } else {
          q = qp * (ab = xyy * sCe / rho);
          xyy = rho * cCe;
        }
        break;
      case N_POLE:
        xyy = -xyy;
      case S_POLE:
        if (0 == (q = (xyx * xyx + xyy * xyy)) ) {
          lplam = 0.;
          lpphi = phi0;
          out.x = lplam;
          out.y = lpphi;
          return;
        }
        /*
        q = P->qp - q;
        */
        ab = 1. - q / qp;
        if (mode == S_POLE)
          ab = - ab;
        break;
      }
      lplam = Math.atan2(xyx, xyy);
      lpphi = authalic.inverse(Math.asin(ab));
      out.x = lplam;
      out.y = lpphi;
    }
    
	  /**
	   * Returns true if this projection is equal area
	   */
	  public boolean isEqualArea() {
	    return true;
	  }

	  public boolean hasInverse() {
	    return true;
	  }
	  
	  public String toString() {
	    return "Lambert Azimuthal Equal Area";
	  }


}
