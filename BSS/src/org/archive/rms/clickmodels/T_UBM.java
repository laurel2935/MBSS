package org.archive.rms.clickmodels;

import java.io.BufferedWriter;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.archive.rms.MAnalyzer;
import org.archive.rms.data.TQuery;
import org.archive.rms.data.TUrl;


/**
 * 
 * Implementation of User Browsing Modeling proposed in 
 * "Dupret, Georges E., and Benjamin Piwowarski. "A user browsing model to predict search engine click data from past observations." SIGIR. ACM, 2008."
 */
public class T_UBM extends MAnalyzer implements T_Evaluation {
	
	class _stat{
		//indicate whether a url is click {0,1} or skipped {0,1}
		int m_click, m_skip;
		//r:rank d:distance w.r.t. last click
		int[][] m_click_rd, m_skip_rd;
		
		_stat(){
			m_click = 0;
			m_skip = 0;
			
			//for all positions
			m_click_rd = new int[10][11];
			m_skip_rd = new int[10][11];
		}
	}
	
	//query-specific \mu
	class _pair{
		double m_mu;
		int m_count;
		double m_stat;
		
		_pair(){
			m_mu = m_mu_init;
			m_count = 1;
			m_stat = 0;
		}
	}

	//model parameters
	double[][] m_gamma; //position and distance 10 * 10
	HashMap<String, Double> m_alpha; //query-document-specific parameters
	HashMap<String, _pair> m_mu; //query-specific parameters
	
	//corpus statistics
	HashMap<String, _stat> m_uq_stat;//qu -> stat
	
	double m_alpha_init, m_gamma_init, m_mu_init;
	
	T_UBM(double testRatio, double alpha, double gamma, double mu){
		super(testRatio);
		
		m_alpha_init = alpha;
		m_gamma_init = gamma;
		m_mu_init = mu;
	}
	
	_stat getStat(String query, String url, boolean add4miss){
		String key = query + "@" + url;
		if (m_uq_stat.containsKey(key))
			return m_uq_stat.get(key);
		else if (add4miss) {
			_stat s = new _stat();
			m_uq_stat.put(key, s);
			return s;
		} else
			return null;
	}
	
	double getAlpha(String query, String url, boolean add4miss){
		String key = query + "@" + url;
		if (m_alpha.containsKey(key))
			return m_alpha.get(key);
		else if (add4miss){
			m_alpha.put(key, new Double(m_alpha_init));
			return m_alpha_init;
		} 
		else 
			return m_alpha_init + m_rand.nextDouble()/10.0;//for non-existing key
	}
	
	double getMu(String query, boolean add4miss){
		if (m_mu.containsKey(query)){
			_pair p = m_mu.get(query);
			if (add4miss)
				p.m_count ++;
			return p.m_mu;
		}
		else if (add4miss){
			m_mu.put(query, new _pair());
			return m_mu_init;
		} 
		else 
			return -1;//for non-existing key
	}
	
	private void getStats(){
		_stat st;
		TUrl url;
		//initialize gamma
		m_gamma = new double[10][11];
		for(int i=0; i<m_gamma.length; i++)
			Arrays.fill(m_gamma[i], m_gamma_init);
		
		//initialize structures
		m_alpha = new HashMap<String, Double>();
		m_mu = new HashMap<String, _pair>();
		m_uq_stat = new HashMap<String, _stat>();
		
		//--
		/*
		for(User user:m_userlist){
			for(Query query:user.m_queries){
				if (query.m_query.contains("@"))
					query.m_query = query.m_query.replace("@", "_");
				
				int lastclick = 0;
				for(int i=0; i<query.m_urls.size(); i++){
					getMu(query.m_query, true);//register the query
					url = query.m_urls.get(i);
					
					//initialize parameters
					getAlpha(query.m_query, url.m_URL, true);
					
					//get statistics
					st = getStat(query.m_query, url.m_URL, true);
					if (url.m_click>0){
						st.m_click_rd[url.m_pos-1][url.m_pos-lastclick] ++;						
						st.m_click ++;
						
						lastclick = url.m_pos;//update the last click
					}
					else{
						st.m_skip ++;
						st.m_skip_rd[url.m_pos-1][url.m_pos-lastclick] ++;
					}
				}
			}
		}
		*/
		//--
		for(TQuery tQuery : _QSessionList){
			String qText = tQuery.getQueryText();
			//if (query.m_query.contains("@"))
			//	query.m_query = query.m_query.replace("@", "_");
			
			int lastclick = 0;
			ArrayList<TUrl> urlList = tQuery.getUrlList();
			for(int i=0; i<urlList.size(); i++){
				getMu(qText, true);//register the query
				url = urlList.get(i);
				
				//initialize parameters
				getAlpha(qText, url.getDocNo(), true);
				
				//get statistics
				st = getStat(qText, url.getDocNo(), true);
				int rankPos = url.getRankPosition();
				if (url.getGTruthClick()>0){
					st.m_click_rd[rankPos-1][rankPos-lastclick] ++;						
					st.m_click ++;
					
					lastclick = rankPos;//update the last click
				}
				else{
					st.m_skip ++;
					st.m_skip_rd[rankPos-1][rankPos-lastclick] ++;
				}
			}
		}
	}
	
	public void EM(int iter, double tol){
		//first step is to collect statistics from the corpus
		getStats();
				
		Iterator<Map.Entry<String, Double>> it;
		Iterator<Map.Entry<String, _pair>> mu_iter;
		_stat st;
		_pair par;
		double a, a_old, mu, gamma, diff=1;
		double[][][] gamma_new = new double[10][11][2];
		String query;
		int step = 0;
		
		while(step++<iter && diff>tol){
			diff = 0;
			
			//update alpha
			it = m_alpha.entrySet().iterator();
		    while (it.hasNext()) {
		        Map.Entry<String, Double> alpha = it.next();
		        query = alpha.getKey().split("@")[0];
		        st = m_uq_stat.get(alpha.getKey());
		        
		        a_old = alpha.getValue();
		        mu = getMu(query, false);
		        a = 0;
		        
		        for(int i=0; i<m_gamma.length; i++)
		        	for(int j=1; j<=i+1; j++)//no zero distance
		        		if (st.m_skip_rd[i][j]>0)
		        			a += st.m_skip_rd[i][j]*a_old*(1.0-mu*m_gamma[i][j])/(1.0-a_old*mu*m_gamma[i][j]);
		        
		        a = (a+st.m_click)/(st.m_click+st.m_skip);
		        diff += (a-a_old)*(a-a_old);
		        if (Double.isNaN(diff))
		        	System.err.println("[ERROR]Encounter NaN for updating alpha!");
		        m_alpha.put(alpha.getKey(), a);
		    }
	
		    //update gamma
		    it = m_alpha.entrySet().iterator();
		    while (it.hasNext()) {
		        Map.Entry<String, Double> alpha = it.next();
		        query = alpha.getKey().split("@")[0];
		        st = m_uq_stat.get(alpha.getKey());
		        
		        a = alpha.getValue();
		        mu = getMu(query, false);
		        
		        for(int i=0; i<m_gamma.length; i++){
		        	for(int j=1; j<=i+1; j++){//no zero distance
		        		if ((st.m_click_rd[i][j] + st.m_skip_rd[i][j])>0)
			        	{
			        		gamma_new[i][j][0] += st.m_click_rd[i][j] + st.m_skip_rd[i][j] * (1-a)*mu*m_gamma[i][j] / (1.0-a*mu*m_gamma[i][j]);
			        		gamma_new[i][j][1] += st.m_click_rd[i][j] + st.m_skip_rd[i][j] * mu*(1-a*m_gamma[i][j]) / (1.0-a*mu*m_gamma[i][j]);
			        	}
		        	}
		        }
		    }
		    
		    //take the special case
		    for(int i=0; i<m_gamma.length; i++){
	        	for(int j=1; j<=i+1; j++){//no zero distance
		    		if (gamma_new[i][j][1]>0)
		    			gamma = gamma_new[i][j][0]/gamma_new[i][j][1];
		    		else
		    			gamma = m_gamma_init;//using the default
		    		
	        		diff += (gamma-m_gamma[i][j])*(gamma-m_gamma[i][j]);
	        		if (Double.isNaN(diff))
			        	System.err.println("[ERROR]Encounter NaN for updating gamma!");
	        		m_gamma[i][j] = gamma;
	        		
	        		gamma_new[i][j][0] = 0;
	        		gamma_new[i][j][1] = 0;
	        	}
		    }
		    
		    //update mu
		    it = m_alpha.entrySet().iterator();
		    while (it.hasNext()) {
		        Map.Entry<String, Double> alpha = it.next();
		        query = alpha.getKey().split("@")[0];
		        a = alpha.getValue();
		        
		        st = m_uq_stat.get(alpha.getKey());	        
		        par = m_mu.get(query);
		       
				try{ 
			        for(int i=0; i<m_gamma.length; i++)
			        	for(int j=1; j<=i+1; j++)//no zero distance
			        		if ((st.m_click_rd[i][j] + st.m_skip_rd[i][j])>0) 
			        			par.m_stat += st.m_skip_rd[i][j] * (1-a*m_gamma[i][j]) / (1-a*par.m_mu*m_gamma[i][j]) + st.m_click_rd[i][j]/par.m_mu;
				}catch(Exception ex){
					System.out.println(alpha.getKey() + "\t" + query);
					System.exit(-1);
				}
		    }
		    
		    mu_iter = m_mu.entrySet().iterator();
		    while(mu_iter.hasNext()){
		    	par = mu_iter.next().getValue();
		    	mu = par.m_mu * par.m_stat / par.m_count;
		    	diff += (mu-par.m_mu) * (mu-par.m_mu);
		    	if (Double.isNaN(diff))
		        	System.err.println("ERROR for updating mu");
		    	
		    	par.m_mu = mu;
		    	par.m_stat = 0;
		    }
		    diff /= (m_alpha.size() + 45 + m_mu.size());//parameter size
		    System.out.println("[Info]EM step " + step + ", diff:" + diff);
		}
		System.out.println("[Info]Processed " + m_alpha.size() + " (q,u) pairs...");
	}
	
	@Override
	public double getClickProb(TQuery tQuery, TUrl tUrl) {
		return getAlpha(tQuery.getQueryText(), tUrl.getDocNo(), false);
		//return 1.0/u.m_pos;//test the original performance
	}
	
	public void SaveAlpha(String filename){
		try {
			BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(filename)));
			Iterator<Map.Entry<String, Double> > it = m_alpha.entrySet().iterator();
			String qu;
			_stat st;
			int qu_size = 0;
		    while (it.hasNext()) {
		        Map.Entry<String, Double> pairs = (Map.Entry<String, Double>)it.next();
		        qu = pairs.getKey();
		        st = m_uq_stat.get(qu);
		        if (st.m_click + st.m_skip<=1)
		        	continue;
		        
		        writer.write(qu + "\t" +pairs.getValue() + "\n");
		        qu_size ++;
		    }
			writer.close();
			System.out.println("[Info]Total " + qu_size + " (q,u) pairs saved!");
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
		
//	public static void main(String[] args) {		
//		UBM ubm = new UBM(0, 0.2, 0.5, 0.5);
//		ubm.LoadLogs("Data/Bucket/urls_large_tmp.dat");
//		ubm.EM(20, 1e-4);
//		ubm.doTest("Data/Results/UBM_train", false);
//		//ubm.SaveAlpha("Data/Models/model_ubm.table");
//		
//		ubm.LoadLogs("Data/Logs/urls_huge_test.dat");
//		ubm.doTest("Data/Results/UBM_test", false);
//		
//		ubm.LoadLogs("Data/Bucket/urls.dat");
//		ubm.doTest("Data/Results/UBM_bucket", true);
//		
//		//ubm.set4Pred(false);
//		//ubm.Save4MLR("Data/Vectors/logs_upred", "Data/Heads/fv_head.dat", true, false);
//	}
	
	/*
	public static void main(String[] args) {
		if (args[0].equals("utest") && args.length!=6){
			System.err.println("[Usage]utest trainset maxUser testset results isBucket");
			return;
		} else if (args[0].equals("upred") && args.length!=9){
			System.err.println("[Usage]upred trainset maxUser testset maxUser fv_stat resultfile isBucket asFeature");
			return;
		} 
		
		T_UBM ubm = new T_UBM(0, 0.2, 0.5, 0.5);
		ubm.LoadLogs(args[1], Integer.valueOf(args[2]));		
		ubm.EM(10, 1e-3);
		ubm.SaveAlpha("model_UBM.table");
		
		if (args[0].equals("utest")){
			ubm.LoadLogs(args[3]);
			ubm.doTest(args[4], Boolean.valueOf(args[5]));
		} else if (args[0].equals("upred")){
			if (args[5].equals("null")==false)
				ubm.LoadFeatureMeanSD(args[5]);
			
			ubm.LoadLogs(args[3], Integer.valueOf(args[4]));
			ubm.set4Pred(Boolean.valueOf(args[8]));
			ubm.Save4MLR(args[6], Boolean.valueOf(args[7]));
		}
	}
	*/
}
