/**
 * 
 */
package session;

import gLearner.ClickModel;

import java.util.Collections;
import java.util.Vector;

/**
 * @author czhai
 * basic structure for query: a list of Queries
 */
public class User {
	public String m_ID;
	public Vector<Query> m_queries;
	public ClickModel m_model;//for personalization purpose
	
	public User(String ID){
		m_ID = ID;
		m_queries = new Vector<Query>();
		m_model = null;
	}
	
	public boolean addQuery(Query q){
		if(q.isLegal()){
			m_queries.add(q);
			return true;
		} else 
			return false;
	}
	
	public int getUrlSize(){
		int size = 0;
		for(Query q:m_queries)
			if (q.clickSize()>0)
				size += q.m_urls.size();
		return size;
	}
	
	public void sortQueries(){
		Collections.sort(m_queries);
	}
}
