package org.archive.rms.clickmodels;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;

import org.archive.rms.advanced.USMFrame;
import org.archive.rms.advanced.USMFrame.FunctionType;
import org.archive.rms.clickmodels.T_Evaluation.Mode;
import org.archive.rms.clickmodels.T_UBM._pair;
import org.archive.rms.clickmodels.T_UBM._stat;
import org.archive.rms.data.TQuery;
import org.archive.rms.data.TUrl;

public class T_UBM_Mar extends FeatureModel {
	
	//Query-Url pair specific statistics
	class rdStat{
		int _sessionSize;
		//indicate whether a url is clicked {0,1} or skipped {0,1}
		int _clickCnt, _skipCnt;
		//r:rank d:distance w.r.t. last click
		int[][] _click_rd, _skip_rd;
		
		rdStat(int sessionSize){
			_sessionSize = sessionSize;
			_clickCnt = 0;
			_skipCnt = 0;	
			
			//for all positions
			_click_rd = new int[sessionSize][sessionSize+1];
			_skip_rd  = new int[sessionSize][sessionSize+1];
		}
	}
		
	////model parameters
	//global \gamma, row: rank position, column: distance to last click
	double [][] _gamma;
	double [][] _gammaSta;
	
	/**
	 * static usage of corpus statistics
	 * **/
	//QU-Pair -> rdStat
	HashMap<String, rdStat> _uqRDStatMap_NaiveRele;
	HashMap<String, rdStat> _uqRDStatMap_MarRele;
	//\alpha, query-document-specific parameters
	HashMap<String, Double> _alphaMap;
	//HashMap<String, Double> _alphaMap_MarRele;
	
	double m_alpha_init, m_gamma_init;
	int _maxSize;
	
	
	protected void getStats_MarRele(){
		int maxSize_NaiveRele = 0;
		int maxSize_MarRele = 0;
		//
		_alphaMap = new HashMap<String, Double>();
		//_alphaMap_MarRele   = new HashMap<>();
		//
		_uqRDStatMap_NaiveRele = new HashMap<String, rdStat>();
		_uqRDStatMap_MarRele   = new HashMap<>();
		
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
				getAlpha_NaiveRele(tQuery, tUrl, true, _mode, true);
				
				//get statistics
				rdStat rdStat_NaiveRele = getRDStat_NaiveRele(tQuery, tUrl, true);
				
				int rPosition = tUrl.getRankPosition();
				if (tUrl.getGTruthClick()>0){
					rdStat_NaiveRele._click_rd[rPosition-1][rPosition-rLastclick_Naive] ++;						
					rdStat_NaiveRele._clickCnt ++;
					//update the last click
					rLastclick_Naive = rPosition;
				}else{
					rdStat_NaiveRele._skipCnt ++;
					rdStat_NaiveRele._skip_rd[rPosition-1][rPosition-rLastclick_Naive] ++;
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
				rdStat rdStat_MarRele = getRDStat_MarRele(tQuery, tUrl, true);
				
				int rPosition = tUrl.getRankPosition();
				if (tUrl.getGTruthClick()>0){
					rdStat_MarRele._click_rd[rPosition-1][rPosition-rLastclick_Mar] ++;						
					rdStat_MarRele._clickCnt ++;
					//update the last click
					rLastclick_Mar = rPosition;
				}else{
					rdStat_MarRele._skipCnt ++;
					rdStat_MarRele._skip_rd[rPosition-1][rPosition-rLastclick_Mar] ++;
				}
			}
		}
		
		//initialize gamma
		int maxSize = Math.max(maxSize_NaiveRele, maxSize_MarRele);
		_maxSize = maxSize;
		
		_gamma = new double[maxSize][maxSize+1];
		for(int i=0; i<_gamma.length; i++){
			Arrays.fill(_gamma[i], m_gamma_init);
		}		
		
		_gammaSta = new double[maxSize][maxSize+1];
		Iterator<Entry<String, rdStat>> rdStatItr;
		
		//1
		rdStatItr = _uqRDStatMap_NaiveRele.entrySet().iterator();
		while(rdStatItr.hasNext()){
			Entry<String, rdStat> rdStatEntry = rdStatItr.next();
			rdStat rdsStat = rdStatEntry.getValue();
			int size = rdsStat._sessionSize;
			
			for(int i=0; i<size; i++){
	        	for(int j=1; j<=i+1; j++){//no zero distance
	        		int cnt = rdsStat._click_rd[i][j] + rdsStat._skip_rd[i][j];
	        		if (cnt>0){
	        			_gammaSta[i][j] += cnt;
		        	}
	        	}
			}
		}
		//2
		rdStatItr = _uqRDStatMap_MarRele.entrySet().iterator();
		while(rdStatItr.hasNext()){
			Entry<String, rdStat> rdStatEntry = rdStatItr.next();
			rdStat rdsStat = rdStatEntry.getValue();
			int size = rdsStat._sessionSize;
			
			for(int i=0; i<size; i++){
	        	for(int j=1; j<=i+1; j++){//no zero distance
	        		int cnt = rdsStat._click_rd[i][j] + rdsStat._skip_rd[i][j];
	        		if (cnt>0){
	        			_gammaSta[i][j] += cnt;
		        	}
	        	}
			}
		}
		
		getSeenQUPairs();
	}
	
	rdStat getRDStat_NaiveRele(TQuery tQuery, TUrl tUrl, boolean add4miss){
		String key = getKey(tQuery, tUrl);
		
		if (_uqRDStatMap_NaiveRele.containsKey(key)){
			return _uqRDStatMap_NaiveRele.get(key);
		}else if (add4miss) {
			//_stat s = new _stat(size);
			rdStat rdStat = new rdStat(_defaultQSessionSize);
			_uqRDStatMap_NaiveRele.put(key, rdStat);
			return rdStat;
		}else{
			return null;
		}
	}
	
	rdStat getRDStat_MarRele(TQuery tQuery, TUrl tUrl, boolean add4miss){
		String key = getKey(tQuery, tUrl);
		
		if (_uqRDStatMap_MarRele.containsKey(key)){
			return _uqRDStatMap_MarRele.get(key);
		}else if (add4miss) {
			//_stat s = new _stat(size);
			rdStat rdStat = new rdStat(_defaultQSessionSize);
			_uqRDStatMap_MarRele.put(key, rdStat);
			return rdStat;
		}else{
			return null;
		}
	}
	
	protected double getAlpha_NaiveRele(TQuery tQuery, TUrl tUrl, boolean add4miss, Mode mode, boolean firstTime){
		String key = getKey(tQuery, tUrl);
		
		if (_alphaMap.containsKey(key))
			return _alphaMap.get(key);
		else if (add4miss){
			if(firstTime){
				_alphaMap.put(key, new Double(m_alpha_init));
				return m_alpha_init;
			}else{
				if(mode.equals(Mode.Original)){
					_alphaMap.put(key, new Double(m_alpha_init));
					return m_alpha_init;
				}else if(mode.equals(Mode.NaiveRele)){
					double alphaV = tUrl.calRelePro(_naiveReleWeights);
					_alphaMap.put(key, alphaV);
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
	
	//
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
			alphaCursor = _alphaMap.entrySet().iterator();
		    while (alphaCursor.hasNext()) {
		        Map.Entry<String, Double> alpha = alphaCursor.next();
		        
		        rdStat rdStat = _uqRDStatMap_NaiveRele.get(alpha.getKey());
		        int size = rdStat._sessionSize;		        
		        double a = alpha.getValue();
		        
		        for(int i=0; i<size; i++){
		        	for(int j=1; j<=i+1; j++){//no zero distance
		        		if ((rdStat._click_rd[i][j] + rdStat._skip_rd[i][j])>0)
			        	{
		        			_gamma_new[i][j] += rdStat._click_rd[i][j] + rdStat._skip_rd[i][j] * (1-a)*_gamma[i][j] / (1.0-a*_gamma[i][j]);
			        	}
		        	}
		        }
		    }
		    
		    //(2) w.r.t. >rFirstClick
		    _uqRDStatMap_NaiveRele.entrySet().iterator();
		    
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
		    			_gamma_new[r-1][r-rLastClick] += (1-marRelePro)*_gamma[r-1][r-rLastClick] / (1.0-marRelePro*_gamma[r-1][r-rLastClick]);
		    		}
		    	}
		    }
		    
		    ////update gamma
		    for(int i=0; i<_maxSize; i++){
	        	for(int j=1; j<=i+1; j++){
	        		if(_gammaSta[i][j] > 0){
	        			_gamma_new[i][j] /= _gammaSta[i][j];
	        			
	        			diff += Math.pow(_gamma_new[i][j]-_gamma[i][j], 2);
	        			
	        			_gamma[i][j] = _gamma_new[i][j];
	        		}
	        	}
		    }		
			
			////update alpha w.r.t. static part, i.e., <=rFirstClick
			alphaCursor = _alphaMap.entrySet().iterator();
		    while (alphaCursor.hasNext()) {
		        Map.Entry<String, Double> alpha = alphaCursor.next();
		        
		        rdStat rdStat = _uqRDStatMap_NaiveRele.get(alpha.getKey());
		        int size = rdStat._sessionSize;
		        
		        double a_old = alpha.getValue();
		        double a_new = 0;
		        
		        //for each rank position
		        for(int r=1; r<=size; r++){
		        	//for each possible distance w.r.t. the last click
		        	for(int d=1; d<=r; d++){
		        		//no zero distance, thus the distance is at least 1
		        		if(rdStat._skip_rd[r-1][d] > 0){
		        			a_new += rdStat._skip_rd[r-1][d]*a_old*(1.0-_gamma[r-1][d])/(1.0-a_old*_gamma[r-1][d]);
		        		}
		        	}
		        }
		        
		        a_new = (a_new+rdStat._clickCnt)/(rdStat._clickCnt+rdStat._skipCnt);
		        
		        diff += (a_new-a_old)*(a_new-a_old);
		        
		        if (Double.isNaN(diff))
		        	System.err.println("[ERROR]Encounter NaN for updating alpha!");
		        
		        _alphaMap.put(alpha.getKey(), a_new);
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
		    			
		    			tUrl._postMarRelePro = a_old*(1.0-_gamma[r-1][r-rLastclick_Mar])/(1.0-a_old*_gamma[r-1][r-rLastclick_Mar]);
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
				double postRelePro = _alphaMap.get(key);				
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
	
	//
	protected void calFunctionGradient_MarginalRele(double[] g){		
		double [] naiveReleWeights = getComponentOfNaiveReleWeight();
		double [] marReleWeights   = getComponentOfMarReleWeight();
		
		for(int i=0; i<this._trainNum; i++){
			TQuery tQuery = this._QSessionList.get(i);
			int firstC = tQuery.getFirstClickPosition();			
			
			for(int rank=1; rank<=firstC; rank++){
				TUrl tUrl = tQuery.getUrlList().get(rank-1);				
				String key = getKey(tQuery, tUrl);
				
				double postRelePro = _alphaMap.get(key);								
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
		System.out.println("Error call w.r.t. updateAlpha_NaiveRele()");
		System.exit(0);		
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
				_alphaMap.put(key, feaRelePro);	
				
				releKeySet.add(key);
			}
			
			for(int r=rFirstClick+1; r<=tQuery.getUrlList().size(); r++){
				TUrl tUrl = urlList.get(r-1);
				tUrl._marRelePro = tQuery.calMarRelePro(r, marReleWeights);
			}
		}
	}	
}
