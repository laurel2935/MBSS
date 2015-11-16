package org.archive.rms.clickmodels;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

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
		return Double.NaN;
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
		
	}
	
	protected FunctionType getFunctionType() {
		return _fType;
	}
	
	protected void estimateParas() {
		try {
    		optimize(50);
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
	
	protected void updateAlpha_MarginalRele(){}
	
	public double getSessionProb_MarginalRele(TQuery tQuery){
		return Double.NaN;
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
		return Double.NaN;
	}
	
	////train
	public void train(){
		initialSteps(false);
		estimateParas();
		
		System.out.println();
		System.out.println("MinObjValue:\t"+_minObjValue);
	}
	
	public double getClickProb(TQuery tQuery, TUrl tUrl) {
		return lookupURL(tQuery, tUrl, true);
	}
	
	
	
	double lookupURL(TQuery tQuery, TUrl tUrl, boolean testPhase){
		String key = getKey(tQuery, tUrl);
		
		if (_alpha.containsKey(key)){
			return _alpha.get(key);
		}else if(testPhase){
			if(!_mode.equals(Mode.Original)){
				double alphaV = tUrl.calRelePro(_naiveReleWeights);
				_alpha.put(key, alphaV);
				return alphaV;
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
		///UBM and its variations
		LogisticRegression logisticRegression = new LogisticRegression(minQFreForTest, mode, useFeature, testRatio, maxQSessionSize);
		
		logisticRegression.train();
		
		System.out.println("Log-likelihood:\t"+logisticRegression.getTestCorpusProb(false, true));
		System.out.println();
		System.out.println("Avg-perplexity:\t"+logisticRegression.getTestCorpusAvgPerplexity(true));
	}
}
