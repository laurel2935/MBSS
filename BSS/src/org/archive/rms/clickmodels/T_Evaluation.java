package org.archive.rms.clickmodels;

import org.archive.rms.data.TQuery;
import org.archive.rms.data.TUrl;

//

public interface T_Evaluation {
	//public void initialize();
	
	//public void doPredicton(TQuery tQuery);
	
	public double getClickProb(TQuery tQuery, TUrl tUrl);
	
	//public void setClicks(TQuery tQuery);
	
	public double getSessionProb(TQuery tQuery, boolean onlyClicks);
	
	public double getTestCorpusProb(boolean onlyClicks);
}
