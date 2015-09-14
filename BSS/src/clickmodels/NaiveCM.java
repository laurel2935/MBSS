/**
 * 
 */
package clickmodels;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map.Entry;

import session.Query;
import session.URL;
import session.User;
import analyzer.Analyzer;

/**
 * @author Hongning
 * Naive counting based click model (baseline)
 */
public class NaiveCM extends Analyzer {

	class UrlStat {
		double m_c;
		int m_total;
		
		public UrlStat(boolean c){
			m_c = c?1:0;
			m_total = 1;
		}
	}
	
	class QueryStat {
		double m_clicks; // num of clicks for this query
		int m_sum; // total (q,u) size
		HashMap<String, UrlStat> m_urls;
		
		public QueryStat(){
			m_sum = 0;
			m_clicks = 0;
			m_urls = new HashMap<String, UrlStat>();
		}
		
		public void addUrl(String url, boolean c){
			//overall statics
			if (c)
				m_clicks++;
			m_sum ++;
			
			//URL specific statistics
			if (m_urls.containsKey(url)){
				UrlStat s = m_urls.get(url);
				if (c)
					s.m_c++;
				s.m_total++;
			} else 
				m_urls.put(url, new UrlStat(c));
		}
	}
	
	HashMap<String, QueryStat> m_model;
	double m_prior;//overall click probability
	
	public NaiveCM() {
		super(0);
		m_model = new HashMap<String, QueryStat>();
		m_prior = 0;
	}
	
	public void doTrain() {
		QueryStat qs = null;
		for(User user:m_userlist){
			for(Query query:user.m_queries){
				if (m_model.containsKey(query.m_query))
					qs = m_model.get(query.m_query);
				else{
					qs = new QueryStat();
					m_model.put(query.m_query, qs);
				}
				
				for(URL url:query.m_urls)
					qs.addUrl(url.m_URL, url.m_c>0);
			}
		}
	}
	
	@Override
	public void initialize() {
		if (m_prior>0)
			return;
		
		QueryStat qs;
		UrlStat us;
		double total_click = 0, total_pairs = 0;
		
		Iterator<Entry<String, QueryStat>> qit = m_model.entrySet().iterator();
		Iterator<Entry<String, UrlStat>> uit;
	    while (qit.hasNext()) {//for each query
	    	Entry<String, QueryStat> qu_pair = qit.next();
	    	qs = qu_pair.getValue();
	    	total_click += qs.m_clicks;
	    	total_pairs += qs.m_sum;
	    	
	    	//click probability for this query
	    	qs.m_clicks /= qs.m_sum;
	    	
	    	uit = qs.m_urls.entrySet().iterator();
	    	while(uit.hasNext()){
	    		Entry<String, UrlStat> urls = uit.next();
	    		us = urls.getValue();
	    		us.m_c /= us.m_total;//posterior for this (q,u)
	    	}
	    }
	    m_prior = total_click/total_pairs;
	    System.out.println("[Info]Global click probability " + m_prior);
	}

	@Override
	public double getClickProb(Query q, URL u) {
		if (m_model.containsKey(q.m_query)){
			QueryStat qs = m_model.get(q.m_query);
			if (qs.m_urls.containsKey(u.m_URL))
				return qs.m_urls.get(u.m_URL).m_c;
			else
				return qs.m_clicks + 0.5*m_rand.nextDouble();
		} else
			return m_prior + 0.5*m_rand.nextDouble();
	}
	
	public static void main(String[] args) {
		if (args[0].equals("ntest") && args.length!=6){
			System.err.println("[Usage]ntest trainset maxUser testset results isBucket");
			return;
		}
		
		NaiveCM ncm = new NaiveCM();
		ncm.LoadLogs(args[1], Integer.valueOf(args[2]));		
		ncm.doTrain();		
		
		ncm.LoadLogs(args[3]);
		ncm.doTest(args[4], Boolean.valueOf(args[5]));
	}
	
//	public static void main(String[] args) {
//		NaiveCM ncm = new NaiveCM();
//		ncm.LoadLogs("Data/Logs/urls_huge_train.dat");
//		ncm.doTrain();
//		ncm.doTest("Data/Results/UBM_train", false);
//		//ubm.SaveAlpha("Data/Models/model_ubm.table");
//		
//		ncm.LoadLogs("Data/Logs/urls_huge_test.dat");
//		ncm.doTest("Data/Results/UBM_test", false);
//		
//		ncm.LoadLogs("Data/Bucket/urls.dat");
//		ncm.doTest("Data/Results/UBM_bucket", true);
//	}
}
