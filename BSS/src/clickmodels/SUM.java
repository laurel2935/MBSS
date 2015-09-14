package clickmodels;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashMap;

import session.Query;
import session.URL;
import analyzer.Analyzer;

/**
 * 
 * @author hongning
 * Interface for using off-line implementation of 
 * "Dupret, Georges, and Ciya Liao. "A model to estimate intrinsic document relevance from the clickthrough logs of a web search engine." WSDM. ACM, 2010."
 */
public class SUM extends Analyzer {

	HashMap<String, Double> m_utilities;
	
	public SUM(int reserve){
		super(reserve);
		m_utilities = new HashMap<String, Double>();
	}
	
	double getUtility(String query, String url){
		String key = query + "@" + Long.toString(url.hashCode());
		if (m_utilities.containsKey(key))
			return m_utilities.get(key);
		else
			return -1;
	}
	
	public void LoadPrediction(String filename){
		try {
			BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(filename)));
			String tmpTxt;
			String[] container;
			while((tmpTxt=reader.readLine()) != null){
				if (tmpTxt.startsWith("#"))
					continue;
				container = tmpTxt.split("\t");
				if (container.length!=4)
					continue;
				m_utilities.put(container[0]+"@"+container[1], Double.valueOf(container[2]));
			}
			reader.close();
			System.out.println("[Info]Load " + m_utilities.size() + " (query,URL)s...");
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
		
	}
	
	@Override
	public double getClickProb(Query q, URL u) {
		return getUtility(q.m_query, u.m_URL);
	}

	public static void main(String[] args) {
		SUM su = new SUM(0);
		su.LoadPrediction("c:/Projects/Data/User Analysis/Heavy Users/Users/Content/Logs/cum/log_result.dat");
		
		su.LoadLogs("c:/Projects/Data/User Analysis/Heavy Users/Users/Content/Bucket/urls.dat");
		su.doTest("Data/Models/click_bucket", true);
	}
}
