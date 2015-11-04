/**
 * 
 */
package org.archive.rms.clickmodels;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map.Entry;

import org.archive.rms.advanced.USMFrame;
import org.archive.rms.advanced.USMFrame.FunctionType;
import org.archive.rms.data.TQuery;
import org.archive.rms.data.TUrl;

/**
 * Naive counting based click model (baseline)
 */

public class T_NaiveCM extends FeatureModel implements T_Evaluation {

	class UrlStat {
		//double m_c;
		//int m_total;
		int clickCnt;
		int displayCnt;
		double relePro;
		
		public UrlStat(boolean c){
			clickCnt = c?1:0;
			displayCnt = 1;
		}
	}
	
	class QueryStat {
		// number of clicks for this query
		//double m_clicks; 
		int qClickCnt;
		// total (q,u) size
		int m_sum;
		double qRelePro;
		
		HashMap<String, UrlStat> m_urls;
		
		public QueryStat(){
			m_sum = 0;
			qClickCnt = 0;
			m_urls = new HashMap<String, UrlStat>();
		}
		
		public void addUrl(String url, boolean c){
			//overall statics
			if (c)
				qClickCnt++;
			m_sum ++;
			
			//URL specific statistics
			if (m_urls.containsKey(url)){
				UrlStat s = m_urls.get(url);
				if (c)
					s.clickCnt++;
				s.displayCnt++;
			} else 
				m_urls.put(url, new UrlStat(c));
		}
	}
	
	HashMap<String, QueryStat> m_model;
	//overall click probability
	double m_prior;
	
	public T_NaiveCM(int minQFre, Mode mode, boolean useFeature, double testRatio, int maxQSessionSize) {
		super(minQFre, mode, useFeature, testRatio, maxQSessionSize);
		
		m_model = new HashMap<String, QueryStat>();
		m_prior = 0;
	}
	
	protected void getStats(){
		QueryStat qs = null;
		
		for(TQuery tQuery: _QSessionList){
			String qText = tQuery.getQueryText();
			
			if (m_model.containsKey(qText))
				qs = m_model.get(qText);
			else{
				qs = new QueryStat();
				m_model.put(qText, qs);
			}
			
			ArrayList<TUrl> urlList = tQuery.getUrlList();
			for(TUrl tUrl: urlList)
				qs.addUrl(tUrl.getDocNo(), tUrl.getGTruthClick()>0);
		}
	}
	
	public void initializePriorRelePro() {
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
	    	total_click += qs.qClickCnt;
	    	total_pairs += qs.m_sum;
	    	
	    	//click probability for this query
	    	qs.qRelePro = qs.qClickCnt/qs.m_sum;
	    	
	    	uit = qs.m_urls.entrySet().iterator();
	    	while(uit.hasNext()){
	    		Entry<String, UrlStat> urls = uit.next();
	    		us = urls.getValue();
	    		//posterior for this (q,u)
	    		us.relePro = us.clickCnt/(us.displayCnt+2);
	    	}
	    }
	    
	    m_prior = total_click/total_pairs;
	    System.out.println("[Info]Global click probability " + m_prior);
	}

	protected void estimateParas(){
		if(!_mode.equals(Mode.Original)){
	    	try {
	    		optimize(50);
			} catch (Exception e) {
				e.printStackTrace();
			}
	    }
	}
		
	////
	protected double calMinObjFunctionValue_NaiveRele(){
		double objVal = 0.0;
		
		for(int i=0; i<this._trainNum; i++){
			TQuery tQuery = this._QSessionList.get(i);
			
			for(TUrl tUrl: tQuery.getUrlList()){				
				double postRelePro = getQURelePro(Mode.Original, tQuery, tUrl, false);
				
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
				
				double postRelePro = getQURelePro(Mode.Original, tQuery, tUrl, false);				
				double feaRelePro = tUrl.calRelePro(naiveReleWeights);
				
				double var = Math.pow(feaRelePro-postRelePro, 2);
				
				objVal += var;				
			}
			//2
			for(int rank=firstC+1; rank<=tQuery.getUrlList().size(); rank++){
				TUrl tUrl = tQuery.getUrlList().get(rank-1);
				
				double postRelePro = getQURelePro(Mode.Original, tQuery, tUrl, false);
				double marRelePro = tQuery.calMarRelePro(rank, marReleWeights);
				
				double var = Math.pow(marRelePro-postRelePro, 2);
				
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
				
				double postRelePro = getQURelePro(Mode.Original, tQuery, tUrl, false);							
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
				
				double postRelePro = getQURelePro(Mode.Original, tQuery, tUrl, false);							
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
				
				double postRelePro = getQURelePro(Mode.Original, tQuery, tUrl, false);
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

	
	
	public double getClickProb_original(TQuery tQuery, TUrl tUrl) {
		String qText = tQuery.getQueryText();
		String urlKey = tUrl.getDocNo();
		
		if (m_model.containsKey(qText)){
			QueryStat qs = m_model.get(qText);
			if (qs.m_urls.containsKey(urlKey))
				return qs.m_urls.get(urlKey).relePro;
			else
				return qs.qRelePro;
		} else
			return m_prior;
	}
	
	public double getClickProb(TQuery tQuery, TUrl tUrl) {
		return getQURelePro(_mode, tQuery, tUrl, false);		
	}
	
	private double getQURelePro(Mode mode, TQuery tQuery, TUrl tUrl, boolean add4miss){
		if(mode.equals(Mode.Original)){
			String qText = tQuery.getQueryText();
			String urlKey = tUrl.getDocNo();
			
			if (m_model.containsKey(qText)){
				QueryStat qs = m_model.get(qText);
				if (qs.m_urls.containsKey(urlKey))
					return qs.m_urls.get(urlKey).relePro;
				else if(add4miss){
					return qs.qRelePro;
				}else{					
					System.out.println("Unseen query-url pair error!");
					return Double.NaN;
				}					
			}else if(add4miss){
				return m_prior + 0.5*m_rand.nextDouble();
			}else{
				System.out.println("Unseen query-url pair error!");
				return Double.NaN;
			}				
		}else if(mode.equals(Mode.NaiveRele)){
			double naiveRelePro = tUrl.calRelePro(_naiveReleWeights);
			return naiveRelePro;
		}else if(mode.equals(Mode.MarginalRele)){
			double [] marReleWeights   = getComponentOfMarReleWeight();
			/////???it should be noted that the unknown of click events of prior clicks
			double marRelePro = tQuery.calMarginalRele(tUrl.getRankPosition(), marReleWeights);
			return marRelePro;
		}else{
			System.out.println("Unaccepted model error!");
			System.exit(0);
			return Double.NaN;
		}
	}
	
	public double getSessionProb(TQuery tQuery, boolean onlyClicks){
		ArrayList<TUrl> urlList = tQuery.getUrlList();
		
		double sessionProb = 1.0;	
		
		if(onlyClicks){
			for(int rankPos=1; rankPos<=urlList.size(); rankPos++){
				TUrl tUrl = urlList.get(rankPos-1);
				
				if(tUrl.getGTruthClick() > 0){
					double clickProb = getClickProb(tQuery, tUrl);				
					sessionProb *= clickProb;
				}			
			}
		}else{
			for(int rankPos=1; rankPos<=urlList.size(); rankPos++){
				TUrl tUrl = urlList.get(rankPos-1);
				
				if(tUrl.getGTruthClick() > 0){
					double clickProb = getClickProb(tQuery, tUrl);				
					sessionProb *= clickProb;
				}else{
					double clickProb = getClickProb(tQuery, tUrl);				
					sessionProb *= (1-clickProb);
				}			
			}
		}		
		
		return sessionProb;	
	}
	
	@Override
	public double getTestCorpusProb(boolean onlyClicks, boolean uniformaComparison){
		double corpusLikelihood = 0.0;
		
		for(int k=this._testNum; k<this._QSessionList.size(); k++){
			TQuery tQuery = this._QSessionList.get(k);
			double session = getSessionProb(tQuery, onlyClicks);
			corpusLikelihood += Math.log(session);
		}
		//the higher the better
		return corpusLikelihood;		
	}
	
	protected void updateAlpha_NaiveRele(){
		System.out.println("Error call w.r.t. updateAlpha_NaiveRele()");
		System.exit(0);		
	}
	
	protected void updateAlpha_MarginalRele(){
		System.out.println("Error call w.r.t. updateAlpha_MarginalRele()");
		System.exit(0);
	}
	
	public void train(){	
		initialSteps(false);
		initializePriorRelePro();
		estimateParas();
	}
	
	/*
	public static void main(String[] args) {
		
		if (args[0].equals("ntest") && args.length!=6){
			System.err.println("[Usage]ntest trainset maxUser testset results isBucket");
			return;
		}
		
		
		T_NaiveCM t_NaiveCM = new T_NaiveCM();
		t_NaiveCM.LoadLogs(args[1], Integer.valueOf(args[2]));		
		t_NaiveCM.doTrain();		
		
		t_NaiveCM.LoadLogs(args[3]);
		t_NaiveCM.doTest(args[4], Boolean.valueOf(args[5]));
		
	}
	*/
	
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
	
	public static void main(String[] args) {
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
		// due to the fact of serious sparcity problem!!!!, is it ok to be used as a baseline?
		T_NaiveCM t_NaiveCM = new T_NaiveCM(minQFre, mode, useFeature, testRatio, maxQSessionSize);
		t_NaiveCM.train();
		System.out.println(t_NaiveCM.getTestCorpusProb(false, true));
	}
}
