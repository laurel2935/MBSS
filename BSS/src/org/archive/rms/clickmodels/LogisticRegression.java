package org.archive.rms.clickmodels;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

import org.archive.access.feature.FRoot;
import org.archive.rms.advanced.USMFrame;
import org.archive.rms.advanced.USMFrame.FunctionType;
import org.archive.rms.data.TQuery;
import org.archive.rms.data.TUrl;

public class LogisticRegression extends FeatureModel implements T_Evaluation {
	private static FunctionType _fType = FunctionType.LINEAR;
	
	HashMap<String, Double> _alpha = new HashMap<>();
	
	public LogisticRegression(int minQFre, Mode mode, boolean useFeature, double testRatio, int maxQSessionSize){
		super(minQFre, mode, useFeature, testRatio, maxQSessionSize);
	}
	
	
	protected double calMinObjFunctionValue_NaiveRele(){
		double objVal = 0.0;
		
		for(int i=0; i<this._trainCnt; i++){
			TQuery tQuery = this._QSessionList.get(i);
			
			for(TUrl tUrl: tQuery.getUrlList()){
		
				double postRelePro = tUrl.getGTruthClick()>0?1:0;
				
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
		
		for(int i=0; i<this._trainCnt; i++){
			TQuery tQuery = this._QSessionList.get(i);
			int firstC = tQuery.getFirstClickPosition();
			//1 <=rFirstClick
			for(int rank=1; rank<=firstC; rank++){
				TUrl tUrl = tQuery.getUrlList().get(rank-1);
				
				double postRelePro = tUrl.getGTruthClick()>0?1:0;
				
				double feaRelePro = tUrl.calRelePro(naiveReleWeights);
				
				double var = Math.pow(feaRelePro-postRelePro, 2);
				
				objVal += var;				
			}
			
			//2 > rFirstClick
			for(int rank=firstC+1; rank<=tQuery.getUrlList().size(); rank++){
				TUrl tUrl = tQuery.getUrlList().get(rank-1);
				
				double postMarRelePro = tUrl.getGTruthClick()>0?1:0;
				
				double marRelePro = tQuery.calMarRelePro_Lambda(naiveReleWeights, rank, marReleWeights, _marFeaVersion);
				
				double var = Math.pow(marRelePro-postMarRelePro, 2);
				
				objVal += var;			
			}
		}
			
		return objVal;
	}
	
	protected void calFunctionGradient_NaiveRele(double[] g){	
		for(int i=0; i<this._trainCnt; i++){
			TQuery tQuery = this._QSessionList.get(i);			
			
			for(TUrl tUrl: tQuery.getUrlList()){	
				double postRelePro = tUrl.getGTruthClick()>0?1:0;								
				double feaRelePro = tUrl.calRelePro(_naiveReleWeights);
				//1
				double firstPart = 2*(feaRelePro-postRelePro);
				//2
				double releVal = USMFrame.calFunctionVal(tUrl.getReleFeatures(), _naiveReleWeights, getFunctionType());
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
		
		for(int i=0; i<this._trainCnt; i++){
			TQuery tQuery = this._QSessionList.get(i);
			int firstC = tQuery.getFirstClickPosition();			
			
			for(int rank=1; rank<=firstC; rank++){
				TUrl tUrl = tQuery.getUrlList().get(rank-1);
				
				double postRelePro = tUrl.getGTruthClick()>0?1:0;							
				double feaRelePro = tUrl.calRelePro(naiveReleWeights);
				//1
				double firstPart = 2*(feaRelePro-postRelePro);
				//2
				double releVal = USMFrame.calFunctionVal(tUrl.getReleFeatures(), naiveReleWeights, getFunctionType());
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
				
				double postMarRelePro = tUrl.getGTruthClick()>0?1:0;
				
				double marRelePro = tQuery.calMarRelePro_Lambda(naiveReleWeights, rank, marReleWeights, _marFeaVersion);
				
				//1
				double firstPart = 2*(marRelePro-postMarRelePro);
				//2
				double releVal = tQuery.calMarReleVal(rank, marReleWeights);
				double expVal = Math.exp(releVal);
				double secondPart = expVal/Math.pow((1+expVal), 2);
				
				//traverse 3
				double [] marReleFeatures;
				if(_marFeaVersion.equals(MarFeaVersion.V1)){
					marReleFeatures = tQuery.getPureMarFeature(rank);
				}else{
					marReleFeatures = tQuery.getMarFeature_Join(rank);
				}
				
				
				for(int k=naiveReleWeights.length; k<_twinWeights.length; k++){
					g[k] += (firstPart*secondPart*marReleFeatures[k-naiveReleWeights.length]);
				}
			}
		}
	}
	
	protected FunctionType getFunctionType() {
		return _fType;
	}
	
	protected void estimateParas() {
		try {
    		optimize(50);
    		bufferParas(_minObjValue);
		} catch (Exception e) {
			e.printStackTrace();
		}
    	//
    	updateAlpha();
	}
	
	protected void updateAlpha_NaiveRele(){
		//avoid duplicate update
		HashSet<String> releKeySet = new HashSet<>();
		
		for(int i=0; i<this._trainCnt; i++){
			TQuery tQuery = this._QSessionList.get(i);			
			
			for(TUrl tUrl: tQuery.getUrlList()){						
				String key = getKey(tQuery, tUrl);
				
				if(releKeySet.contains(key)){
					continue;
				}
				
				double feaRelePro = tUrl.calRelePro(_naiveReleWeights);
				_alpha.put(key, feaRelePro);	
				
				releKeySet.add(key);
			}
		}
	}
	
	protected void getStats_Static(){}
	
	protected void getStats_MarginalRele(){}
	
	protected void updateAlpha_MarginalRele(){
		double [] naiveReleWeights = getComponentOfNaiveReleWeight();
		double [] marReleWeights   = getComponentOfMarReleWeight();
		
		//avoid duplicate update
		HashSet<String> releKeySet = new HashSet<>();
		
		for(int i=0; i<this._trainCnt; i++){
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
				_alpha.put(key, feaRelePro);	
				
				releKeySet.add(key);
			}
			
			for(int r=rFirstClick+1; r<=tQuery.getUrlList().size(); r++){
				TUrl tUrl = urlList.get(r-1);
				tUrl._marRelePro = tQuery.calMarRelePro_Lambda(naiveReleWeights, r, marReleWeights, _marFeaVersion);
			}
		}
		
	}
	
	public double getSessionProb_MarginalRele(TQuery tQuery){
		double [] marReleWeights   = getComponentOfMarReleWeight();
		
		ArrayList<TUrl> urlList = tQuery.getUrlList();
		
		double sessionProb = 1.0;
		int rFirstClick = tQuery.getFirstClickPosition();
		
		//<=rFirstClick
		for(int r=1; r<=rFirstClick; r++){
			TUrl tUrl = urlList.get(r-1);
			
			if(tUrl.getGTruthClick() > 0){
				double alpha_qu = lookupURL(tQuery, tUrl, true);
				sessionProb *= alpha_qu;
				
				if(0 == sessionProb){
					System.out.println("1 alpha_qu:\t"+alpha_qu);
					System.exit(0);
				}					
			}else{
				double alpha_qu = lookupURL(tQuery, tUrl, true);
				if(1 == alpha_qu){
					alpha_qu = 0.9;
				}
				
				sessionProb *= (1-alpha_qu);
				
				if(0 == sessionProb){
					System.out.println("1 =0 alpha_qu:\t"+alpha_qu);
					System.exit(0);
				}					
			}
		}
		
		//>rFirstClick
		for(int r=rFirstClick+1; r<=urlList.size(); r++){
			TUrl tUrl = urlList.get(r-1);
			
			if(tUrl.getGTruthClick() > 0){
				double marRelePro = tQuery.calMarRelePro_Lambda(getComponentOfNaiveReleWeight(), r, marReleWeights, _marFeaVersion);
				
				sessionProb *= marRelePro;
				if(0 == sessionProb || sessionProb<0){
					System.out.println("2 > 0 >rFirstClick alpha_qu:\t"+marRelePro);
					System.exit(0);
				}
			}else{
				double marRelePro = tQuery.calMarRelePro_Lambda(getComponentOfNaiveReleWeight(), r, marReleWeights, _marFeaVersion);				
				
				sessionProb *= (1-marRelePro);
				
				if(0 == sessionProb || sessionProb<0){
					System.out.println("2 =0 >rFirstClick alpha_qu:\t"+marRelePro+"\t"+sessionProb);
					System.out.println(marRelePro);
					System.exit(0);
				}
			}
		}	
		
		return sessionProb;		
	}
	
	public double getSessionGainAtK(TQuery tQuery, int r){				
		double gainAtK;
		
		ArrayList<TUrl> urlList = tQuery.getUrlList();
		TUrl tUrl = urlList.get(r-1);
		
		if(tUrl.getGTruthClick() > 0){
			double alpha_qu = lookupURL(tQuery, tUrl, true);
			gainAtK = (Math.log(alpha_qu)/_log2);				
		}else{
			double alpha_qu = lookupURL(tQuery, tUrl, true);
			if(1 == alpha_qu){
				alpha_qu = 0.9;
			}
			
			gainAtK = (Math.log(1-alpha_qu)/_log2);				
		}
		
		return gainAtK;
	}
	
	public double getSessionGainAtK_MarginalRele(TQuery tQuery, int r){
		int rFirstClick = tQuery.getFirstClickPosition();
		if(r <= rFirstClick){
			return getSessionGainAtK(tQuery, r);
		}else{
						
			double [] marReleWeights   = getComponentOfMarReleWeight();
			ArrayList<TUrl> urlList = tQuery.getUrlList();
			TUrl tUrl = urlList.get(r-1);
			
			double gainAtK;
			
			if(tUrl.getGTruthClick() > 0){
				double marRelePro = tQuery.calMarRelePro_Lambda(getComponentOfNaiveReleWeight(), r, marReleWeights, _marFeaVersion);
				
				gainAtK = (Math.log(marRelePro)/_log2);
			}else{
				double marRelePro = tQuery.calMarRelePro_Lambda(getComponentOfNaiveReleWeight(), r, marReleWeights, _marFeaVersion);				
				
				gainAtK = (Math.log(1-marRelePro)/_log2);				
			}	
			
			return gainAtK;
		}
	}
	
	////train
	public void train(){
		initialSteps(true);
		estimateParas();
		
		System.out.println();
		System.out.println("MinObjValue:\t"+_minObjValue);
	}
	
	public double getClickProb(TQuery tQuery, TUrl tUrl) {
		return lookupURL(tQuery, tUrl, true);
	}
	
	protected String getOptParaFileNameString() {
		String testParaStr = "_"+Integer.toString(_minQFreForTest)+"_"+Integer.toString(_maxQSessionSize);
		String optParaFileName;
		if(_mode == Mode.NaiveRele){
			optParaFileName = FRoot._bufferParaDir+"LogisticRegression_NaiveReleParameter"+testParaStr+".txt";
		}else{
			optParaFileName = FRoot._bufferParaDir+"LogisticRegression_MarReleParameter_"+_marFeaVersion.toString()+testParaStr+".txt";
		}
		
		return optParaFileName;
	}
	
	double lookupURL(TQuery tQuery, TUrl tUrl, boolean testPhase){		
		String key = getKey(tQuery, tUrl);
		
		if (_alpha.containsKey(key))
			return _alpha.get(key);
		else if(testPhase){
			if(_mode.equals(Mode.NaiveRele)){
				double alphaV = tUrl.calRelePro(_naiveReleWeights);
				_alpha.put(key, alphaV);
				return alphaV;
			}else if(_mode.equals(Mode.MarginalRele)){
				int rFirstClick = tQuery.getFirstClickPosition();
				if(tUrl.getRankPosition() <= rFirstClick){
					double alphaV = tUrl.calRelePro(getComponentOfNaiveReleWeight());
					_alpha.put(key, alphaV);
					return alphaV;
				}else{
					double marRelePro = tQuery.calMarRelePro_Lambda(getComponentOfNaiveReleWeight(), tUrl.getRankPosition(), getComponentOfMarReleWeight(), _marFeaVersion);
					return marRelePro;
				}				
			}else{
				System.out.println("Unconsistent mode for test error!");
				System.exit(0);
				return Double.NaN;
			}
		}else{
			System.out.println("Unseen query-url pair search error!");
			return Double.NaN;
		}
	}
	
	public double getSessionProb(TQuery tQuery, boolean onlyClicks){
		ArrayList<TUrl> urlList = tQuery.getUrlList();
		
		double sessionProb = 1.0;
		
		for(int rankPos=1; rankPos<=urlList.size(); rankPos++){
			TUrl tUrl = urlList.get(rankPos-1);
			
			if(tUrl.getGTruthClick() > 0){
				double alpha_qu = lookupURL(tQuery, tUrl, true);
				sessionProb *= alpha_qu;
				
				if(0 == sessionProb){
					System.out.println("1 alpha_qu:\t"+alpha_qu);
					System.exit(0);
				}					
			}else{
				double alpha_qu = lookupURL(tQuery, tUrl, true);
				if(1 == alpha_qu){
					alpha_qu = 0.9;
				}
				
				sessionProb *= (1-alpha_qu);
				
				if(0 == sessionProb){
					System.out.println("1 =0 alpha_qu:\t"+alpha_qu);
					System.exit(0);
				}					
			}			
		}	
		
		return sessionProb;		
	}
	
	
	///////
	public static void main(String []args){
		////1
		//
		double testRatio    = 0.25;
		int maxQSessionSize = 10;
		int minQFreForTest = 1;
	
		//
		Mode mode = Mode.NaiveRele;
		//
		boolean useFeature;		
		if(mode.equals(Mode.Original)){
			useFeature = false;
		}else{
			useFeature = true;
		}		
		
		boolean uniformCmp = true;
		
		
		///UBM and its variations
		LogisticRegression logisticRegression = new LogisticRegression(minQFreForTest, mode, useFeature, testRatio, maxQSessionSize);
		
		logisticRegression.train();
		
		System.out.println();
		System.out.println("----uniform evaluation----");
		System.out.println("Log-likelihood:\t"+logisticRegression.getTestCorpusProb(false, uniformCmp));
		System.out.println();
		logisticRegression.getTestCorpusProb_vsQFreForTest(false, uniformCmp);
		System.out.println();
		System.out.println();
		System.out.println("Avg-perplexity:\t"+logisticRegression.getTestCorpusAvgPerplexity(uniformCmp));
		logisticRegression.getTestCorpusAvgPerplexity_vsQFreForTest(uniformCmp);
		
		System.out.println();
		System.out.println();
		System.out.println("----plus unobserved part evaluation----");
		System.out.println("Log-likelihood:\t"+logisticRegression.getTestCorpusProb(false, !uniformCmp));
		System.out.println();
		System.out.println("Avg-perplexity:\t"+logisticRegression.getTestCorpusAvgPerplexity(!uniformCmp));
	}
}
