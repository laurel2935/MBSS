package optimizer;

/* Copyright (C) 2002 Univ. of Massachusetts Amherst, Computer Science Dept.
This file is part of "MALLET" (MAchine Learning for LanguagE Toolkit).
http://www.cs.umass.edu/~mccallum/mallet
This software is provided under the terms of the Common Public License,
version 1.0, as published by http://www.opensource.org.  For further
information, see the file `LICENSE' included with this distribution. */


/** 
@author Aron Culotta <a href="mailto:culotta@cs.umass.edu">culotta@cs.umass.edu</a>
*/

/**
	 Numerical Recipes in C: p.385. lnsrch. A simple backtracking line
	 search. No attempt at accurately finding the true minimum is
	 made. The goal is only to ensure that BackTrackLineSearch will
	 return a position of higher value.
*/

import cc.mallet.optimize.InvalidOptimizableException;
import cc.mallet.optimize.LineOptimizer;
import cc.mallet.types.MatrixOps;

//"Line Searches and Backtracking", p385, "Numeric Recipes in C"

public class ProjectedLinearSearch implements LineOptimizer.ByGradient
{
	ProjectedGradientValue function;
	
	public ProjectedLinearSearch (ProjectedGradientValue optimizable) {
		this.function = optimizable;
	}

	final int maxIterations = 100;
	final double stpmax = 100;
	final double EPS = 3.0e-12;

	// termination conditions: either
	//   a) abs(delta x/x) < REL_TOLX for all coordinates
	//   b) abs(delta x) < ABS_TOLX for all coordinates
	//   c) sufficient function increase (uses ALF)
	private double relTolx = 1e-7;
	private double absTolx = 1e-4; // tolerance on absolute value difference
	final double ALF = 1e-4;

	public void setObj(ProjectedGradientValue obj){
		function = obj;
	}
	
	/** 
	 * Sets the tolerance of relative diff in function value.
	 *  Line search converges if <tt>abs(delta x / x) < tolx</tt>
	 *  for all coordinates. */
	public void setRelTolx (double tolx) { relTolx = tolx; }        

	/** 
	 * Sets the tolerance of absolute diff in function value.
	 *  Line search converges if <tt>abs(delta x) < tolx</tt>
	 *  for all coordinates. */
	public void setAbsTolx (double tolx) { absTolx = tolx; }        

	// initialStep is ignored.  This is b/c if the initial step is not 1.0,
	//   it sometimes confuses the backtracking for reasons I don't 
	//   understand.  (That is, the jump gets LARGER on iteration 1.)

	// returns fraction of step size (alam) if found a good step
	// returns 0.0 if could not step in direction
	public double optimize (double[] line, double initialStep)
	{
		double[] g, x, oldParameters;
		double slope, newSlope, temp, test, alamin, alam, alam2, tmplam;
		double rhs1, rhs2, a, b, disc, oldAlam;
		double f, fold, f2;
		g = new double[function.getNumParameters()]; // gradient
		x = new double[function.getNumParameters()]; // parameters
		oldParameters = new double[function.getNumParameters()];
		function.getParameters (x);
		System.arraycopy (x, 0, oldParameters, 0, x.length);
		function.getValueGradient (g);
		alam2 = tmplam = 0.0; 
		f2 = fold = function.getValue();
		
		assert (!MatrixOps.isNaN(g));
		double sum = MatrixOps.twoNorm(line);
		if(sum > stpmax) {			
			MatrixOps.timesEquals(line, stpmax/sum);
		}

		newSlope = slope = MatrixOps.dotProduct (g, line);

		if (slope<0) 
			throw new InvalidOptimizableException ("Slope = " + slope + " is negative");
		if (slope == 0)
			throw new InvalidOptimizableException ("Slope = " + slope + " is zero");

		// find maximum lambda
		// converge when (delta x) / x < REL_TOLX for all coordinates.
		//  the largest step size that triggers this threshold is
		//  precomputed and saved in alamin
		test = 0.0;
		for(int i=0; i<oldParameters.length; i++) {
			temp = Math.abs(line[i]) /
			Math.max(Math.abs(oldParameters[i]), 1.0);
			if(temp > test) test = temp;
		}

		alamin = relTolx/test;
		alam  = 1.0;
		oldAlam = 0.0;
		int iteration = 0;
		// look for step size in direction given by "line"
		for(iteration=0; iteration < maxIterations; iteration++) {
			// x = oldParameters + alam*line
			// initially, alam = 1.0, i.e. take full Newton step
			assert(alam != oldAlam) : "alam == oldAlam";
			MatrixOps.plusEquals(x, line, alam - oldAlam); // step

			// check for convergence 
			//convergence on delta x
			if ((alam < alamin) || smallAbsDiff (oldParameters, x)) {
				function.setParameters(oldParameters);
				f = function.getValue();
				return 0.0;
			}

			function.setParameters(x);
			oldAlam = alam;
			f = function.getValue();

			// sufficient function increase (Wolf condition)
			if(f >= fold+ALF*alam*slope) { 
				if (f<fold) 
					throw new IllegalStateException("Function did not increase: f=" + f + " < " + fold + "=fold");				
				return alam;
			}
			// if value is infinite, i.e. we've
			// jumped to unstable territory, then scale down jump
			else if(Double.isInfinite(f) || Double.isInfinite(f2)) {
				tmplam = .2 * alam;					
				if(alam < alamin) { //convergence on delta x
					function.setParameters(oldParameters);
					f = function.getValue();
					return 0.0;
				}
			}
			else { // backtrack
				if(alam == 1.0) // first time through
					tmplam = -slope/(2.0*(f-fold-slope));
				else {
					rhs1 = f-fold-alam*slope;
					rhs2 = f2-fold-alam2*slope;
					assert((alam - alam2) != 0): "FAILURE: dividing by alam-alam2. alam="+alam;
					a = (rhs1/(alam*alam)-rhs2/(alam2*alam2))/(alam-alam2);
					b = (-alam2*rhs1/(alam*alam)+alam*rhs2/(alam2*alam2))/(alam-alam2);
					if(a == 0.0) 
						tmplam = -slope/(2.0*b);
					else {
						disc = b*b-3.0*a*slope;
						if(disc < 0.0) {
							tmplam = .5 * alam;
						}
						else if (b <= 0.0)
							tmplam = (-b+Math.sqrt(disc))/(3.0*a);
						else tmplam = -slope/(b+Math.sqrt(disc));
					}
					if (tmplam > .5*alam)
						tmplam = .5*alam;    // lambda <= .5 lambda_1
				}
			}
			alam2 = alam;
			f2 = f;
			alam = Math.max(tmplam, .1*alam);  // lambda >= .1*Lambda_1						
		}

		return 0.0;
	}

	// returns true iff we've converged based on absolute x difference 
	private boolean smallAbsDiff (double[] x, double[] xold)
	{
		for (int i = 0; i < x.length; i++) {
			if (Math.abs (x[i] - xold[i]) > absTolx) {
				return false;
			}
		}
		return true;
	}
}
