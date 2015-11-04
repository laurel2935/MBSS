package org.archive.rms.clickmodels;

import java.io.BufferedWriter;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;

import optimizer.LBFGS;
import optimizer.LBFGS.ExceptionWithIflag;

import org.archive.rms.advanced.USMFrame;
import org.archive.rms.advanced.USMFrame.FunctionType;
import org.archive.rms.data.TQuery;
import org.archive.rms.data.TUrl;



/**
 * 
 * Implementation and extension of User Browsing Modeling proposed in 
 * "Dupret, Georges E., and Benjamin Piwowarski. "A user browsing model to predict search engine click data from past observations." SIGIR. ACM, 2008."
 * 
 * for content-aware, it needs the feature vector of each document
 * for marginal utility, it needs the marginal utility feature vector of each document, i.e., all the document in a session
 * 
 * !a possible shortcoming is: it has pay for the cost of estimating the relevance or marginal utility below the last click.
 */


public class T_UBM extends FeatureModel implements T_Evaluation {
	
	//for estimating the \gama, i.e., the effect of rank position and distance to last click for a pair of query and url
	class _stat{
		//indicate whether a url is click {0,1} or skipped {0,1}
		int m_click, m_skip;
		//r:rank d:distance w.r.t. last click
		int[][] m_click_rd, m_skip_rd;
		//
		int _size;
		
		_stat(int size){
			m_click = 0;
			m_skip = 0;
			
			_size = size;
			
			//for all positions
			//m_click_rd = new int[10][11];
			//m_skip_rd = new int[10][11];
			m_click_rd = new int[_size][_size+1];
			m_skip_rd  = new int[_size][_size+1];
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

	/**
	 * model parameters
	 * **/
	//\gamma matrix, position and distance 10 * 10
	double[][] m_gamma;
	double [][] _gammaSta;
	//\alpha, query-document-specific parameters
	HashMap<String, Double> m_alpha;
	//\mu, query-specific parameters
	HashMap<String, _pair> m_mu;
	
	//corpus statistics
	//qu -> stat
	HashMap<String, _stat> m_uq_stat;
	HashMap<String, _stat> m_uq_stat_mar;
	
	double m_alpha_init, m_gamma_init, m_mu_init;
	//i.e., query-session size
	int _maxSize;
	
	
	T_UBM(int minQFre, Mode mode, boolean useFeature, double testRatio, double alpha, double gamma, double mu, int maxQSessionSize){
		super(minQFre, mode, useFeature, testRatio, maxQSessionSize);		
		
		m_alpha_init = alpha;
		m_gamma_init = gamma;
		m_mu_init = mu;	
	}
	
	_stat getStat_original(String query, String url, boolean add4miss, int size){
		//the problem: the same query and url, but different sizes of result lists
		String key = query + "@" + url;
		if (m_uq_stat.containsKey(key))
			return m_uq_stat.get(key);
		else if (add4miss) {
			//_stat s = new _stat(size);
			_stat s = new _stat(20);
			m_uq_stat.put(key, s);
			return s;
		} else
			return null;
	}
	//
	_stat getStat_ext(TQuery tQuery, TUrl tUrl, boolean add4miss){
		String key = getKey(tQuery, tUrl);
		
		if (m_uq_stat.containsKey(key))
			return m_uq_stat.get(key);
		else if (add4miss) {
			_stat s = new _stat(_defaultQSessionSize);
			m_uq_stat.put(key, s);
			return s;
		}else{
			return null;
		}			
	}
	//
	_stat getRDStat_MarRele(TQuery tQuery, TUrl tUrl, boolean add4miss){
		String key = getKey(tQuery, tUrl);
		
		if (m_uq_stat_mar.containsKey(key)){
			return m_uq_stat_mar.get(key);
		}else if (add4miss) {
			//_stat s = new _stat(size);
			_stat rdStat = new _stat(_defaultQSessionSize);
			m_uq_stat_mar.put(key, rdStat);
			return rdStat;
		}else{
			return null;
		}
	}
	//
	protected void getStats_Mar(){
		int maxSize_NaiveRele = 0;
		int maxSize_MarRele = 0;
		//
		m_alpha = new HashMap<String, Double>();
		//_alphaMap_MarRele   = new HashMap<>();
		//
		m_uq_stat = new HashMap<>();
		m_uq_stat_mar   = new HashMap<>();
		
		//only training part
		for(int qNum=1; qNum<=this._trainNum; qNum++){
			TQuery tQuery = this._QSessionList.get(qNum-1);
			ArrayList<TUrl> urlList = tQuery.getUrlList();	
			
			//part-1
			int rLastclick_Naive = 0;					
			int rFirstClick = tQuery.getFirstClickPosition();
			if(rFirstClick > maxSize_NaiveRele){
				maxSize_NaiveRele = rFirstClick;
			}
			
			for(int r=1; r<=rFirstClick; r++){				
				TUrl tUrl = urlList.get(r-1);				
				//initialize parameters
				getAlpha_Ext(tQuery, tUrl, true, _mode, true);
				
				//get statistics
				_stat rdStat_NaiveRele = getStat_ext(tQuery, tUrl, true);
				
				int rPosition = tUrl.getRankPosition();
				if (tUrl.getGTruthClick()>0){
					rdStat_NaiveRele.m_click_rd[rPosition-1][rPosition-rLastclick_Naive] ++;						
					rdStat_NaiveRele.m_click ++;
					//update the last click
					rLastclick_Naive = rPosition;
				}else{
					rdStat_NaiveRele.m_skip ++;
					rdStat_NaiveRele.m_skip_rd[rPosition-1][rPosition-rLastclick_Naive] ++;
				}
			}
			
			//part-2
			int rLastclick_Mar = rFirstClick+1;
			int remainingSize = urlList.size()-rFirstClick;
			if(remainingSize > maxSize_MarRele){
				maxSize_MarRele = remainingSize;
			}
			for(int r=rFirstClick+1; r<=urlList.size(); r++){
				TUrl tUrl = urlList.get(r-1);				
				
				//get statistics
				_stat rdStat_MarRele = getRDStat_MarRele(tQuery, tUrl, true);
				
				int rPosition = tUrl.getRankPosition();
				if (tUrl.getGTruthClick()>0){
					rdStat_MarRele.m_click_rd[rPosition-1][rPosition-rLastclick_Mar] ++;						
					rdStat_MarRele.m_click ++;
					//update the last click
					rLastclick_Mar = rPosition;
				}else{
					rdStat_MarRele.m_skip ++;
					rdStat_MarRele.m_skip_rd[rPosition-1][rPosition-rLastclick_Mar] ++;
				}
			}
		}
		
		//initialize gamma
		int maxSize = Math.max(maxSize_NaiveRele, maxSize_MarRele);
		_maxSize = maxSize;
		
		m_gamma = new double[maxSize][maxSize+1];
		for(int i=0; i<m_gamma.length; i++){
			Arrays.fill(m_gamma[i], m_gamma_init);
		}		
		
		_gammaSta = new double[maxSize][maxSize+1];
		Iterator<Entry<String, _stat>> rdStatItr;
		
		//1
		rdStatItr = m_uq_stat.entrySet().iterator();
		while(rdStatItr.hasNext()){
			Entry<String, _stat> rdStatEntry = rdStatItr.next();
			_stat rdsStat = rdStatEntry.getValue();
			int size = rdsStat._size;
			
			for(int i=0; i<size; i++){
	        	for(int j=1; j<=i+1; j++){//no zero distance
	        		int cnt = rdsStat.m_click_rd[i][j] + rdsStat.m_skip_rd[i][j];
	        		if (cnt>0){
	        			_gammaSta[i][j] += cnt;
		        	}
	        	}
			}
		}
		//2
		rdStatItr = m_uq_stat_mar.entrySet().iterator();
		while(rdStatItr.hasNext()){
			Entry<String, _stat> rdStatEntry = rdStatItr.next();
			_stat rdsStat = rdStatEntry.getValue();
			int size = rdsStat._size;
			
			for(int i=0; i<size; i++){
	        	for(int j=1; j<=i+1; j++){//no zero distance
	        		int cnt = rdsStat.m_click_rd[i][j] + rdsStat.m_skip_rd[i][j];
	        		if (cnt>0){
	        			_gammaSta[i][j] += cnt;
		        	}
	        	}
			}
		}
		
		getSeenQUPairs();
	}
	//
	double getAlpha_original(String query, String url, boolean add4miss){
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
	//	
	protected double getAlpha_Ext(TQuery tQuery, TUrl tUrl, boolean add4miss, Mode mode, boolean firstTime){
		String key = getKey(tQuery, tUrl);
		
		if (m_alpha.containsKey(key))
			return m_alpha.get(key);
		else if (add4miss){
			if(firstTime){
				m_alpha.put(key, new Double(m_alpha_init));
				return m_alpha_init;
			}else{
				if(mode.equals(Mode.Original)){
					m_alpha.put(key, new Double(m_alpha_init));
					return m_alpha_init;
				}else if(mode.equals(Mode.NaiveRele)){
					double alphaV = tUrl.calRelePro(_naiveReleWeights);
					m_alpha.put(key, alphaV);
					return alphaV;
				}else if(mode.equals(Mode.MarginalRele)){
					double alphaV = tQuery.calMarginalRele(tUrl.getRankPosition(), getComponentOfMarReleWeight());
					m_alpha.put(key, alphaV);
					return alphaV;
				}else{
					System.out.println("Unaccepted model error!");
					System.exit(0);
					return Double.NaN;
				}
			}			
		}else{
			System.out.println("Unseen query-url pair error!");
			return Double.NaN;
		}			
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
	
	protected void getStats(){
		int maxSize = 0;
		//initialize structures
		m_alpha = new HashMap<String, Double>();
		m_mu = new HashMap<String, _pair>();
		m_uq_stat = new HashMap<String, _stat>();
		
		for(int qNum=1; qNum<=this._trainNum; qNum++){
			TQuery tQuery = this._QSessionList.get(qNum-1);
			String qText = tQuery.getQueryText();
			//if (query.m_query.contains("@"))
			//query.m_query = query.m_query.replace("@", "_");
			
			int lastclick = 0;
			ArrayList<TUrl> urlList = tQuery.getUrlList();
			
			int size = urlList.size();	
			if(size > maxSize){
				maxSize = size;
			}			
			
			for(int i=0; i<urlList.size(); i++){
				getMu(qText, true);//register the query
				
				TUrl tUrl = urlList.get(i);
				
				//initialize parameters
				//getAlpha(qText, url.getDocNo(), true);
				getAlpha_Ext(tQuery, tUrl, true, _mode, true);
				
				//get statistics
				//_stat st = getStat(qText, tUrl.getDocNo(), true, size);
				_stat st = getStat_ext(tQuery, tUrl, true);
				
				int rankPos = tUrl.getRankPosition();
				if (tUrl.getGTruthClick()>0){
					st.m_click_rd[rankPos-1][rankPos-lastclick] ++;						
					st.m_click ++;
					
					lastclick = rankPos;//update the last click
				}
				else{
					st.m_skip ++;
					//System.out.println("size:\t"+size);
					//System.out.println(st.m_skip_rd.length);
					//System.out.println(rankPos-1);
					st.m_skip_rd[rankPos-1][rankPos-lastclick] ++;
				}
			}
		}
		
		//initialize gamma
		m_gamma = new double[maxSize][maxSize+1];
		for(int i=0; i<m_gamma.length; i++)
			Arrays.fill(m_gamma[i], m_gamma_init);
		
		//
		_maxSize = maxSize;
		
		getSeenQUPairs();
	}
		
	public void EM_Multiple(int iter, double tol){				
		Iterator<Map.Entry<String, Double>> it;
		Iterator<Map.Entry<String, _pair>> mu_iter;
		_stat st;
		_pair par;
		double a, a_old, mu, gamma, diff=1;
		//double[][][] gamma_new = new double[10][11][2];
		double[][][] gamma_new = new double[_maxSize][_maxSize+1][2];
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
		        int size = st._size;
		        
		        a_old = alpha.getValue();
		        mu = getMu(query, false);
		        a = 0;
		        
		        /*
		        for(int i=0; i<m_gamma.length; i++)
		        	for(int j=1; j<=i+1; j++)//no zero distance
		        		if (st.m_skip_rd[i][j]>0)
		        			a += st.m_skip_rd[i][j]*a_old*(1.0-mu*m_gamma[i][j])/(1.0-a_old*mu*m_gamma[i][j]);
		        */
		        for(int i=0; i<size; i++)
		        	for(int j=1; j<=i+1; j++)//no zero distance
		        		if (st.m_skip_rd[i][j]>0)
		        			a += st.m_skip_rd[i][j]*a_old*(1.0-mu*m_gamma[i][j])/(1.0-a_old*mu*m_gamma[i][j]);
		        
		        a = (a+st.m_click)/(st.m_click+st.m_skip);
		        
		        diff += (a-a_old)*(a-a_old);
		        
		        if (Double.isNaN(diff))
		        	System.err.println("[ERROR]Encounter NaN for updating alpha!");
		        
		        m_alpha.put(alpha.getKey(), a);
		    }
		    
		    if(!_mode.equals(Mode.Original)){
		    	try {
		    		optimize(50);
				} catch (Exception e) {
					e.printStackTrace();
					//System.exit(0);
				}
		    	//
		    	updateAlpha();
		    }
	
		    //update gamma
		    it = m_alpha.entrySet().iterator();
		    while (it.hasNext()) {
		        Map.Entry<String, Double> alpha = it.next();
		        query = alpha.getKey().split("@")[0];
		        
		        st = m_uq_stat.get(alpha.getKey());
		        int size = st._size;
		        
		        a = alpha.getValue();
		        mu = getMu(query, false);
		        
		        //
		        /*
		        for(int i=0; i<m_gamma.length; i++){
		        	for(int j=1; j<=i+1; j++){//no zero distance
		        		if ((st.m_click_rd[i][j] + st.m_skip_rd[i][j])>0)
			        	{
			        		gamma_new[i][j][0] += st.m_click_rd[i][j] + st.m_skip_rd[i][j] * (1-a)*mu*m_gamma[i][j] / (1.0-a*mu*m_gamma[i][j]);
			        		gamma_new[i][j][1] += st.m_click_rd[i][j] + st.m_skip_rd[i][j] * mu*(1-a*m_gamma[i][j]) / (1.0-a*mu*m_gamma[i][j]);
			        	}
		        	}
		        }
		        */
		        
		        for(int i=0; i<size; i++){
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
		        int size = st._size;
		        par = m_mu.get(query);
		       
				try{ 
					/*
			        for(int i=0; i<m_gamma.length; i++)
			        	for(int j=1; j<=i+1; j++)//no zero distance
			        		if ((st.m_click_rd[i][j] + st.m_skip_rd[i][j])>0) 
			        			par.m_stat += st.m_skip_rd[i][j] * (1-a*m_gamma[i][j]) / (1-a*par.m_mu*m_gamma[i][j]) + st.m_click_rd[i][j]/par.m_mu;
			        */
					for(int i=0; i<size; i++)
			        	for(int j=1; j<=i+1; j++)//no zero distance
			        		if ((st.m_click_rd[i][j] + st.m_skip_rd[i][j])>0){
			        			
			        			
			        			double val = st.m_skip_rd[i][j] * (1-a*m_gamma[i][j]) / (1-a*par.m_mu*m_gamma[i][j]) + st.m_click_rd[i][j]/par.m_mu;
			        			
			        			//par.m_stat += st.m_skip_rd[i][j] * (1-a*m_gamma[i][j]) / (1-a*par.m_mu*m_gamma[i][j]) + st.m_click_rd[i][j]/par.m_mu;
			        			if(Double.isNaN(val)){
			        				System.out.println(a);
			        				System.out.println(m_gamma[i][j]);
			        				System.out.println(1-a*par.m_mu*m_gamma[i][j]);
			        				System.out.println(par.m_mu);
			        				System.exit(0);
			        			}
			        			
			        			par.m_stat += val;
			        		}			        			
				}catch(Exception ex){
					System.out.println(alpha.getKey() + "\t" + query);
					System.exit(-1);
				}
		    }
		    
		    mu_iter = m_mu.entrySet().iterator();
		    while(mu_iter.hasNext()){
		    	par = mu_iter.next().getValue();
		    	mu = par.m_mu * par.m_stat / par.m_count;
		    	
		    	if(Double.isNaN(mu)){
		    		System.out.println(par.m_mu);
		    		System.out.println(par.m_stat);
		    		System.out.println(par.m_count);
		    		System.exit(0);
		    	}		    	
		    	
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
	
	public void EM_Single(int iter, double tol){				
		Iterator<Map.Entry<String, Double>> alphaCursor;
		_stat uqStat;
		double a_new, a_old, gamma, diff=1;
		double[][] gamma_new = new double[_maxSize][_maxSize+1];
		double[][] gamma_sta = new double[_maxSize][_maxSize+1];
		
		int step = 0;
		
		while(step++<iter && diff>tol){
			diff = 0;	
			
		    ////update gamma
		    alphaCursor = m_alpha.entrySet().iterator();
		    while (alphaCursor.hasNext()) {
		        Map.Entry<String, Double> alpha = alphaCursor.next();		        
		        uqStat = m_uq_stat.get(alpha.getKey());
		        int size = uqStat._size;		        
		        a_new = alpha.getValue();
		        
		        /*
		        for(int i=0; i<m_gamma.length; i++){
		        	for(int j=1; j<=i+1; j++){//no zero distance
		        		if ((st.m_click_rd[i][j] + st.m_skip_rd[i][j])>0)
			        	{
			        		gamma_new[i][j][0] += st.m_click_rd[i][j] + st.m_skip_rd[i][j] * (1-a)*mu*m_gamma[i][j] / (1.0-a*mu*m_gamma[i][j]);
			        		gamma_new[i][j][1] += st.m_click_rd[i][j] + st.m_skip_rd[i][j] * mu*(1-a*m_gamma[i][j]) / (1.0-a*mu*m_gamma[i][j]);
			        	}
		        	}
		        }
		        */
		        
		        for(int i=0; i<size; i++){
		        	for(int j=1; j<=i+1; j++){//no zero distance
		        		int cnt = uqStat.m_click_rd[i][j] + uqStat.m_skip_rd[i][j];
		        		if (cnt > 0){
		        			gamma_sta[i][j] += cnt;
			        		gamma_new[i][j] += (uqStat.m_click_rd[i][j] + uqStat.m_skip_rd[i][j]*(1-a_new)*m_gamma[i][j]/(1.0-a_new*m_gamma[i][j]));
			        	}
		        	}
		        }
		    }
		    
		    for(int i=0; i<m_gamma.length; i++){
	        	for(int j=1; j<=i+1; j++){
	        		if(gamma_sta[i][j] > 0){
	        			gamma = gamma_new[i][j]/gamma_sta[i][j];
	        		}else{
	        			gamma = m_gamma_init;
	        		}
	        		
	        		m_gamma[i][j] = gamma;
	        		
	        		diff += (gamma-m_gamma[i][j])*(gamma-m_gamma[i][j]);	        		
	        		if (Double.isNaN(diff)){
	        			System.err.println("[ERROR]Encounter NaN for updating gamma!");
	        		}			        	
	        	}
		    }
		    //
			
			////update alpha
			alphaCursor = m_alpha.entrySet().iterator();
		    while (alphaCursor.hasNext()) {
		        Map.Entry<String, Double> alpha = alphaCursor.next();		        
		        uqStat = m_uq_stat.get(alpha.getKey());
		        int size = uqStat._size;
		        
		        a_old = alpha.getValue();
		        a_new = 0;
		        
		        /*
		        for(int i=0; i<m_gamma.length; i++)
		        	for(int j=1; j<=i+1; j++)//no zero distance
		        		if (st.m_skip_rd[i][j]>0)
		        			a += st.m_skip_rd[i][j]*a_old*(1.0-mu*m_gamma[i][j])/(1.0-a_old*mu*m_gamma[i][j]);
		        */
		        for(int i=0; i<size; i++)
		        	for(int j=1; j<=i+1; j++)//no zero distance
		        		if (uqStat.m_skip_rd[i][j]>0)
		        			a_new += uqStat.m_skip_rd[i][j]*a_old*(1.0-m_gamma[i][j])/(1.0-a_old*m_gamma[i][j]);
		        
		        a_new = (a_new+uqStat.m_click)/(uqStat.m_click+uqStat.m_skip);
		        m_alpha.put(alpha.getKey(), a_new);
		        
		        diff += (a_new-a_old)*(a_new-a_old);		        
		        if (Double.isNaN(diff)){
		        	System.err.println("[ERROR]Encounter NaN for updating alpha!");
		        }
		    }
		    
		    ///*
		    if(!_mode.equals(Mode.Original)){
		    	try {
		    		optimize(40);
				} catch (Exception e) {
					e.printStackTrace();
					//System.exit(0);
				}
		    	//
		    	updateAlpha();
		    }
		    //*/
	
		    
		    diff /= (m_alpha.size() + 45 + m_mu.size());//parameter size
		    
		    System.out.println("[Info]EM step " + step + ", diff:" + diff);
		}	
		
		System.out.println("[Info]Processed " + m_alpha.size() + " (q,u) pairs...");
		
		//one-time optimization
		/*
		if(!_mode.equals(Mode.Original)){
	    	try {
	    		optimize(40);
			} catch (Exception e) {
				e.printStackTrace();
				//System.exit(0);
			}
	    	//
	    	updateAlpha();
	    }
		*/
	}
	
	protected void optimize(int maxIter, double[][] gamma_opt) throws ExceptionWithIflag{
		
		LBFGS _lbfgsOptimizer = new LBFGS();
		
		int[] iprint = {-1, 0}, iflag = {0};
		
		//gradient w.r.t. the function
		double[] g, diag;
		
		if(_mode.equals(Mode.NaiveRele)){
			g = new double[_naiveReleWeights.length];
			diag = new double[_naiveReleWeights.length];	
		}else{
			g = new double[_twinWeights.length];
			diag = new double[_twinWeights.length];			
		}
		

		int iter = 0;
		//objVal
		double f=0;	
				
		do {			
			
			if (iflag[0]==0 || iflag[0]==1){
				
				//function value based on the posterior graph inference! 
				f = calMinObjFunctionValue(gamma_opt);
				
				/*
				if(f < USMFrame._MIN){
					f = USMFrame._MIN;
				}
				*/

				//System.out.println("Iter-"+iter+":\tObjValue: "+f);
				
				calFunctionGradient(g);					
				
				//w.r.t. max objective function
				//MatrixOps.timesEquals(g, -1);
			}
			
			//if (iflag[0]==0 || iflag[0]==2){//if we want to estimate the diagonals by ourselves
			//	getDiagnoal(diag, iflag[0]==2);
			//}
			
			try{				
				//_lbfgsOptimizer.lbfgs(mar_weights.length, 5, mar_weights, -f, g, false, diag, iprint, 1e-3, 1e-3, iflag);
				if(_mode.equals(Mode.NaiveRele)){
					_lbfgsOptimizer.lbfgs(_naiveReleWeights.length, 5, _naiveReleWeights, f, g, false, diag, iprint, 1e-3, 1e-3, iflag);
				}else{
					_lbfgsOptimizer.lbfgs(_twinWeights.length, 5, _twinWeights, f, g, false, diag, iprint, 1e-3, 1e-3, iflag);		
				}

			} catch (ExceptionWithIflag ex){
				System.err.println("[Warning]M-step cannot proceed!");
			}	
			
			//outputParas();
			//System.out.println();
			
		}while(iflag[0]>0 && ++iter<maxIter);
	}
	
	protected double calMinObjFunctionValue(double[][] gamma_opt){
		return Double.NaN;
	}
	
	public void EM_MarRele(int iter, double tol){				
		Iterator<Map.Entry<String, Double>> alphaCursor;
		
		_pair par;
		//double a_new, a_old, mu, gamma, diff=1;
		double diff=1;
		//double[][][] gamma_new = new double[10][11][2];
		//double[][][] gamma_new = new double[_maxSize][_maxSize+1][2];
		double [][] _gamma_new = new double [_maxSize][_maxSize+1];
		String query;
		int step = 0;
		
		while(step++<iter && diff>tol){
			diff = 0;
			
			////update \gamma first			
			//(1) w.r.t. <=rFirstClick
			alphaCursor = m_alpha .entrySet().iterator();
		    while (alphaCursor.hasNext()) {
		        Map.Entry<String, Double> alpha = alphaCursor.next();
		        
		        _stat rdStat = m_uq_stat.get(alpha.getKey());
		        int size = rdStat._size;		        
		        double a = alpha.getValue();
		        
		        for(int i=0; i<size; i++){
		        	for(int j=1; j<=i+1; j++){//no zero distance
		        		if ((rdStat.m_click_rd[i][j] + rdStat.m_skip_rd[i][j])>0)
			        	{
		        			_gamma_new[i][j] += rdStat.m_click_rd[i][j] + rdStat.m_skip_rd[i][j] * (1-a)*m_gamma[i][j] / (1.0-a*m_gamma[i][j]);
			        	}
		        	}
		        }
		    }
		    
		    //(2) w.r.t. >rFirstClick		    
		    for(int qNum=1; qNum<=_trainNum; qNum++){
		    	TQuery tQuery = this._QSessionList.get(qNum-1);
		    	
		    	int rFirstClick = tQuery.getFirstClickPosition();
		    	ArrayList<TUrl> urlList = tQuery.getUrlList();
		    	
		    	int rLastClick = rFirstClick+1;
		    	
		    	for(int r=rFirstClick+1; r<=urlList.size(); r++){
		    		TUrl tUrl = urlList.get(r-1);
		    		
		    		if(tUrl.getGTruthClick() > 0){		    			
		    			_gamma_new[r-1][r-rLastClick] += 1;
		    			
		    			rLastClick = tUrl.getRankPosition();
		    		}else{
		    			double marRelePro = tQuery.calMarRelePro(r, getComponentOfMarReleWeight());
		    			_gamma_new[r-1][r-rLastClick] += (1-marRelePro)*m_gamma[r-1][r-rLastClick] / (1.0-marRelePro*m_gamma[r-1][r-rLastClick]);
		    		}
		    	}
		    }
		    
		    ////update gamma
		    for(int i=0; i<_maxSize; i++){
	        	for(int j=1; j<=i+1; j++){
	        		if(_gammaSta[i][j] > 0){
	        			_gamma_new[i][j] /= _gammaSta[i][j];
	        			
	        			diff += Math.pow(_gamma_new[i][j]-m_gamma[i][j], 2);
	        			
	        			m_gamma[i][j] = _gamma_new[i][j];
	        		}
	        	}
		    }		
			
			////update alpha w.r.t. static part, i.e., <=rFirstClick
			alphaCursor = m_alpha.entrySet().iterator();
		    while (alphaCursor.hasNext()) {
		        Map.Entry<String, Double> alpha = alphaCursor.next();
		        
		        _stat rdStat = m_uq_stat.get(alpha.getKey());
		        int size = rdStat._size;
		        
		        double a_old = alpha.getValue();
		        double a_new = 0;
		        
		        //for each rank position
		        for(int r=1; r<=size; r++){
		        	//for each possible distance w.r.t. the last click
		        	for(int d=1; d<=r; d++){
		        		//no zero distance, thus the distance is at least 1
		        		if(rdStat.m_skip_rd[r-1][d] > 0){
		        			a_new += rdStat.m_skip_rd[r-1][d]*a_old*(1.0-m_gamma[r-1][d])/(1.0-a_old*m_gamma[r-1][d]);
		        		}
		        	}
		        }
		        
		        a_new = (a_new+rdStat.m_click)/(rdStat.m_click+rdStat.m_skip);
		        
		        diff += (a_new-a_old)*(a_new-a_old);
		        
		        if (Double.isNaN(diff))
		        	System.err.println("[ERROR]Encounter NaN for updating alpha!");
		        
		        m_alpha.put(alpha.getKey(), a_new);
		    }    
		    
		    //update alpha w.r.t. dynamic part, i.e., >rFirstClick
		    for(int qNum=1; qNum<=_trainNum; qNum++){
		    	TQuery tQuery = this._QSessionList.get(qNum-1);
		    	
		    	int rFirstClick = tQuery.getFirstClickPosition();
		    	ArrayList<TUrl> urlList = tQuery.getUrlList();
		    	
		    	int rLastclick_Mar = rFirstClick+1;
		    	
		    	for(int r=rFirstClick+1; r<=urlList.size(); r++){
		    		TUrl tUrl = urlList.get(r-1);
		    		
		    		if(tUrl.getGTruthClick() > 0){
		    			tUrl._postMarRelePro = 0.5;
		    					
    					rLastclick_Mar = r;
		    		}else{
		    			double a_old = tUrl._marRelePro;
		    			
		    			tUrl._postMarRelePro = a_old*(1.0-m_gamma[r-1][r-rLastclick_Mar])/(1.0-a_old*m_gamma[r-1][r-rLastclick_Mar]);
		    		}
		    	}
		    }
		    
		    if(!_mode.equals(Mode.Original)){
		    	try {
		    		optimize(50);
				} catch (Exception e) {
					e.printStackTrace();
					//System.exit(0);
				}
		    	//
		    	updateAlpha();
		    }    
		    
		    //diff /= (m_alpha.size() + 45 + m_mu.size());//parameter size
		    
		    System.out.println("[Info]EM step " + step + ", diff:" + diff);
		}
		
		//System.out.println("[Info]Processed " + m_alpha.size() + " (q,u) pairs...");
	}
	
	@Override
	public double getClickProb(TQuery tQuery, TUrl tUrl) {
		return getAlpha_Ext(tQuery, tUrl, false, _mode, false);
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
	
	@Override
	public double getSessionProb(TQuery tQuery, boolean onlyClicks){
		//one-time calculation
		//tQuery.calContextInfor();
		ArrayList<TUrl> urlList = tQuery.getUrlList();
		
		double sessionProb = 1.0;
		
		if(onlyClicks){
			for(int rankPos=1; rankPos<=urlList.size(); rankPos++){
				TUrl tUrl = urlList.get(rankPos-1);
				
				if(tUrl.getGTruthClick() > 0){
					if(1 == rankPos){
						double alpha_qu = getAlpha_Ext(tQuery, tUrl, false, _mode, false);
						sessionProb *= alpha_qu;
					}else{
						double alpha_qu = getAlpha_Ext(tQuery, tUrl, false, _mode, false);
						int dis = Math.max(0, (int)tUrl.getDisToLastClick()-1);
						
						double curGamma = m_gamma[rankPos-1][dis];
						if(0 == curGamma){
							curGamma = m_gamma_init;
						}
						
						sessionProb *= (alpha_qu*curGamma);
					}					
				}			
			}
		}else{//plus skips
			for(int rankPos=1; rankPos<=urlList.size(); rankPos++){
				TUrl tUrl = urlList.get(rankPos-1);
				
				if(tUrl.getGTruthClick() > 0){
					if(1 == rankPos){
						double alpha_qu = getAlpha_Ext(tQuery, tUrl, false, _mode, false);
						sessionProb *= alpha_qu;
						
						if(0 == sessionProb){
							System.out.println("1 alpha_qu:\t"+alpha_qu);
							System.exit(0);
						}
					}else{
						double alpha_qu = getAlpha_Ext(tQuery, tUrl, false, _mode, false);
						int dis = Math.max(0, (int)tUrl.getDisToLastClick()-1);
						
						double curGamma = m_gamma[rankPos-1][dis];
						if(0 == curGamma){
							curGamma = m_gamma_init;
						}
						
						sessionProb *= (alpha_qu*curGamma);
						if(0 == sessionProb){
							System.out.println("2 > 0 alpha_qu:\t"+alpha_qu);
							System.exit(0);
						}
					}					
				}else{
					if(1 == rankPos){
						double alpha_qu = getAlpha_Ext(tQuery, tUrl, false, _mode, false);
						if(1 == alpha_qu){
							alpha_qu = 0.9;
						}
						sessionProb *= (1-alpha_qu);
						
						if(0 == sessionProb){
							System.out.println("1 =0 alpha_qu:\t"+alpha_qu);
							System.exit(0);
						}
					}else{
						double alpha_qu = getAlpha_Ext(tQuery, tUrl, false, _mode, false);
						int dis = Math.max(0, (int)tUrl.getDisToLastClick()-1);
						
						double curGamma = m_gamma[rankPos-1][dis];
						if(0 == curGamma){
							curGamma = m_gamma_init;
						}
						
						sessionProb *= (1-alpha_qu*curGamma);
						if(0 == sessionProb){
							System.out.println("2 =0 alpha_qu:\t"+alpha_qu);
							System.exit(0);
						}
					}					
				}			
			}
		}		
		
		return sessionProb;		
	}
	
	protected void  name() {
		
	}
	////	
	protected double calMinObjFunctionValue_NaiveRele(){
		double objVal = 0.0;
		
		for(int i=0; i<this._trainNum; i++){
			TQuery tQuery = this._QSessionList.get(i);
			
			for(TUrl tUrl: tQuery.getUrlList()){
				String key = getKey(tQuery, tUrl);
				
				double postRelePro = m_alpha.get(key);
				
				double feaRelePro = tUrl.calRelePro(_naiveReleWeights);
				
				double var = Math.pow(feaRelePro-postRelePro, 2);
				
				objVal += var;
			}
		}
			
		return objVal;
	}
	
	protected double calMinObjFunctionValue_MarginalRele(){
		double objVal = 0.0;
		
		double [] naiveReleWeights = getComponentOfNaiveReleWeight();
		double [] marReleWeights   = getComponentOfMarReleWeight();
		
		for(int i=0; i<this._trainNum; i++){
			TQuery tQuery = this._QSessionList.get(i);
			int firstC = tQuery.getFirstClickPosition();
			//1
			for(int rank=1; rank<=firstC; rank++){
				TUrl tUrl = tQuery.getUrlList().get(rank-1);
				
				String key = getKey(tQuery, tUrl);				
				double postRelePro = m_alpha.get(key);				
				double feaRelePro = tUrl.calRelePro(naiveReleWeights);
				
				double var = Math.pow(feaRelePro-postRelePro, 2);
				
				objVal += var;				
			}
			//2
			for(int rank=firstC+1; rank<=tQuery.getUrlList().size(); rank++){
				TUrl tUrl = tQuery.getUrlList().get(rank-1);
				
				double postMarRelePro = tUrl._postMarRelePro;
				double marRelePro = tQuery.calMarRelePro(rank, marReleWeights);
				
				double var = Math.pow(marRelePro-postMarRelePro, 2);
				
				objVal += var;			
			}
		}
			
		return objVal;
	}
	////	
	protected void calFunctionGradient_NaiveRele(double[] g){	
		for(int i=0; i<this._trainNum; i++){
			TQuery tQuery = this._QSessionList.get(i);			
			
			for(TUrl tUrl: tQuery.getUrlList()){						
				String key = getKey(tQuery, tUrl);
				
				double postRelePro = m_alpha.get(key);								
				double feaRelePro = tUrl.calRelePro(_naiveReleWeights);
				//1
				double firstPart = 2*(feaRelePro-postRelePro);
				//2
				double releVal = USMFrame.calFunctionVal(tUrl.getReleFeatures(), _naiveReleWeights, FunctionType.LINEAR);
				double expVal = Math.exp(releVal);
				double secondPart = expVal/Math.pow((1+expVal), 2);
				
				//traverse 3
				double [] naiveReleFeatures = tUrl.getReleFeatures();
				for(int k=0; k<naiveReleFeatures.length; k++){
					g[k] += (firstPart*secondPart*naiveReleFeatures[k]);
				}				
			}
		}
	}
	
	protected void calFunctionGradient_MarginalRele(double[] g){		
		double [] naiveReleWeights = getComponentOfNaiveReleWeight();
		double [] marReleWeights   = getComponentOfMarReleWeight();
		
		for(int i=0; i<this._trainNum; i++){
			TQuery tQuery = this._QSessionList.get(i);
			int firstC = tQuery.getFirstClickPosition();			
			
			for(int rank=1; rank<=firstC; rank++){
				TUrl tUrl = tQuery.getUrlList().get(rank-1);				
				String key = getKey(tQuery, tUrl);
				
				double postRelePro = m_alpha.get(key);								
				double feaRelePro = tUrl.calRelePro(naiveReleWeights);
				//1
				double firstPart = 2*(feaRelePro-postRelePro);
				//2
				double releVal = USMFrame.calFunctionVal(tUrl.getReleFeatures(), naiveReleWeights, FunctionType.LINEAR);
				double expVal = Math.exp(releVal);
				double secondPart = expVal/Math.pow((1+expVal), 2);
				
				//traverse 3
				double [] naiveReleFeatures = tUrl.getReleFeatures();
				for(int k=0; k<naiveReleFeatures.length; k++){
					g[k] += (firstPart*secondPart*naiveReleFeatures[k]);
				}				
			}
			
			for(int rank=firstC+1; rank<=tQuery.getUrlList().size(); rank++){
				TUrl tUrl = tQuery.getUrlList().get(rank-1);
				
				double postRelePro = tUrl._postMarRelePro;
				double marRelePro = tQuery.calMarRelePro(rank, marReleWeights);
				
				//1
				double firstPart = 2*(marRelePro-postRelePro);
				//2
				double releVal = tQuery.calMarReleVal(rank, marReleWeights);
				double expVal = Math.exp(releVal);
				double secondPart = expVal/Math.pow((1+expVal), 2);
				
				//traverse 3
				double [] marReleFeatures = tQuery.getPureMarFeature(rank);
				for(int k=naiveReleWeights.length; k<_twinWeights.length; k++){
					g[k] += (firstPart*secondPart*marReleFeatures[k]);
				}
			}
		}
	}
	
	protected void updateAlpha_NaiveRele(){
		//avoid duplicate update
		HashSet<String> releKeySet = new HashSet<>();
		
		for(int i=0; i<this._trainNum; i++){
			TQuery tQuery = this._QSessionList.get(i);			
			
			for(TUrl tUrl: tQuery.getUrlList()){						
				String key = getKey(tQuery, tUrl);
				
				if(releKeySet.contains(key)){
					continue;
				}
				
				double feaRelePro = tUrl.calRelePro(_naiveReleWeights);
				//double emValue = m_alpha.get(key);
				/*
				if(Math.abs(emValue-feaRelePro) > 0.5){
					System.out.println("before:\t"+emValue);
					System.out.println("after:\t"+feaRelePro);
				}
				*/
				m_alpha.put(key, feaRelePro);	
				
				releKeySet.add(key);
			}
		}
	}
	
	protected void updateAlpha_MarginalRele(){
		double [] naiveReleWeights = getComponentOfNaiveReleWeight();
		double [] marReleWeights   = getComponentOfMarReleWeight();
		
		//avoid duplicate update
		HashSet<String> releKeySet = new HashSet<>();
		
		for(int i=0; i<this._trainNum; i++){
			TQuery tQuery = this._QSessionList.get(i);			
			
			int rFirstClick = tQuery.getFirstClickPosition();
			ArrayList<TUrl> urlList = tQuery.getUrlList(); 
			
			for(int r=1; r<=rFirstClick; r++){
				TUrl tUrl = urlList.get(r-1);				
				String key = getKey(tQuery, tUrl);
				
				if(releKeySet.contains(key)){
					continue;
				}
				
				double feaRelePro = tUrl.calRelePro(naiveReleWeights);
				//double emValue = m_alpha.get(key);
				/*
				if(Math.abs(emValue-feaRelePro) > 0.5){
					System.out.println("before:\t"+emValue);
					System.out.println("after:\t"+feaRelePro);
				}
				*/
				m_alpha.put(key, feaRelePro);	
				
				releKeySet.add(key);
			}
			
			for(int r=rFirstClick+1; r<=tQuery.getUrlList().size(); r++){
				TUrl tUrl = urlList.get(r-1);
				tUrl._marRelePro = tQuery.calMarRelePro(r, marReleWeights);
			}
		}
	}	
	
	////train
	public void train(){
		initialSteps(false);
		estimateParas();
	}
	////
	protected void estimateParas() {
		//EM_Multiple(70, 1e-8);
		
		EM_Single(40, 1e-8);
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
	
	public static void main(String []args){
		//1
		double testRatio = 0.25;
		int maxQSessionSize = 10;
		int minQFre = 2;

		Mode mode = Mode.Original;
		
		boolean useFeature;
		
		if(mode.equals(Mode.Original)){
			useFeature = false;
		}else{
			useFeature = true;
		}		
		
		T_UBM t_UBM = new T_UBM(minQFre, mode, useFeature, testRatio, 0.2, 0.5, 0.5, maxQSessionSize);
		
		t_UBM.train();
		
		//-4928.668887248367
		System.out.println(t_UBM.getTestCorpusProb(false, true));
	}
}
