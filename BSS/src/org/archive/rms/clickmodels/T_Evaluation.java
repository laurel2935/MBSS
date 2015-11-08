package org.archive.rms.clickmodels;

import org.archive.rms.data.TQuery;
import org.archive.rms.data.TUrl;

//

public interface T_Evaluation {
	public enum Mode {Original, NaiveRele, MarginalRele};
	
	////
	public void train();

	/**
	 * Evaluation-1: log-likelihood of test corpus 
	 * **/
	public double getSessionProb(TQuery tQuery, boolean onlyClicks);
	
	public double getTestCorpusProb(boolean onlyClicks, boolean uniformCmp);
	/**
	 * Evaluation-2: accuracy of predicting clicks below the first click
	 * **/
	public double getClickProb(TQuery tQuery, TUrl tUrl);
	
	public double getTestCorpusAvgPerplexity(boolean uniformCmp);
	////for comparing marginal utility based UBM
	//1 log-likelihood
	//2 perplexity gain w.r.t. positions below first click
	//3 perplexity gain per rank w.r.t. positions below first click
	
}
