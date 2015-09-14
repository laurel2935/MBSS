package analyzer;

import session.Query;
import session.URL;

public interface Evaluation {
	public void initialize();
	
	public void doPredicton(Query q);
	
	public double getClickProb(Query q, URL u);
	
	public void setClicks(Query q);
}
