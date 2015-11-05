package org.archive.rms.clickmodels;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Random;

import optimizer.LBFGS;
import optimizer.LBFGS.ExceptionWithIflag;

import org.archive.access.feature.FRoot;
import org.archive.rms.advanced.MAnalyzer;
import org.archive.rms.advanced.USMFrame.FunctionType;
import org.archive.rms.clickmodels.T_Evaluation.Mode;
import org.archive.rms.data.TQuery;
import org.archive.rms.data.TUrl;
import org.archive.util.io.IOText;

public abstract class FeatureModel extends MAnalyzer {
	/**
	 * For extending user models
	 * **/
	////----
	protected Mode _mode;	
	////feature-relevant
	//case-1: content-aware:
	//case-2: marginalUtility-aware
	protected HashMap<String, ArrayList<Double>> _featureMap;
	
	//w.r.t. independent relevance
	protected double []  _naiveReleWeights;
	protected static int _naiveReleFeaLen = 13;
	
	//w.r.t. marginal utility, consisting of two parts: (1) documents >= first clicked documents; (2) documents below first clicked document
	protected double []  _twinWeights;
	//i.e., when computing marginal utility, only use marginal features, which seems to be counter-intuitive without relevance features
	protected static int _twinFeatureLen_v_1 = 13 + 6;
	//i.e., when computing marginal utility, both marginal features and relevance features are used.
	protected static int _twinFeatureLen_v_2 = 13 + 13 + 6;
	
	public enum MarFeaVersion {V1, V2};
	protected MarFeaVersion _marFeaVersion = MarFeaVersion.V1;
	
	protected static double _defaultWeightScale = 50;
	
	//the maximum size of a query session that will be considered
	protected static int _defaultQSessionSize = 20;
		
	protected HashSet<String> _seenQUPairSet;
	protected HashSet<String> _unseenQUPairSet;
	
	protected static HashSet<String> _seenInTest = new HashSet<>();
	protected static HashSet<String> _unseenInTest = new HashSet<>();
    ////----
	
	
	FeatureModel(int minQFre, Mode mode, boolean useFeature, double testRatio, int maxQSessionSize){
		super(minQFre, testRatio, useFeature, maxQSessionSize);
		
		_mode = mode;
		_defaultQSessionSize = maxQSessionSize;
	}
	
	//unique identifier w.r.t. a pair of query and url
	protected String getKey(TQuery tQuery, TUrl tUrl){
		//String key = tQuery.getKey()+"@"+ tUrl.getDocNo()+"@"+Integer.toString(tUrl.getRankPosition());
		return tQuery.getQueryText()+"@"+ tUrl.getDocNo();
	}
	
	////weight
	protected void iniWeightVector(boolean useCurrentOptimal){
		if(_mode.equals(Mode.NaiveRele)){
			iniWeightVector_NaiveRele(useCurrentOptimal);
		}else{
			iniWeightVector_MarginalRele(useCurrentOptimal);		
		}
	}
	
	protected void iniWeightVector_NaiveRele(boolean useCurrentOptimal){		
		_naiveReleWeights = new double[_naiveReleFeaLen];
		
		if(useCurrentOptimal){
			double [] currentOptParas = loadCurrentOptimalParas();
			
			for(int i=0; i<currentOptParas.length; i++){
				_naiveReleWeights[i] = currentOptParas[i];
			}
			
		}else{
			double weightScale = _defaultWeightScale;		
			Random rand = new Random();
			for(int i=0; i<_naiveReleWeights.length; i++){
				_naiveReleWeights [i] = (2*rand.nextDouble()-1)%weightScale;
			}
		}		
	}
	
	protected void iniWeightVector_MarginalRele(boolean useCurrentOptimal){
		_twinWeights = new double[getMarFeaLength()];
		
		if(useCurrentOptimal){
			double [] currentOptParas = loadCurrentOptimalParas();			
			for(int i=0; i<currentOptParas.length; i++){
				_twinWeights[i] = currentOptParas[i];
			}			
		}else{
			double weightScale = _defaultWeightScale;		
			Random rand = new Random();
			for(int i=0; i<_twinWeights.length; i++){
				_twinWeights [i] = (2*rand.nextDouble()-1)%weightScale;
			}
		}
	}
	
	protected double [] loadCurrentOptimalParas(){
		String optimalParaFile = FRoot._bufferParaDir+"Parameter_Mar_Optimal.txt";
		ArrayList<String> lineList = IOText.getLinesAsAList_UTF8(optimalParaFile);
		
		double [] currentOptParas = new double [lineList.size()-1];
		for(int i=0; i<currentOptParas.length; i++){
			currentOptParas[i] = Double.parseDouble(lineList.get(i+1));
		}
		
		return currentOptParas;
	}

	////
	protected void initialSteps(boolean useCurrentOptimal) {
		if(!_mode.equals(Mode.Original)){
			iniWeightVector(useCurrentOptimal);
			iniFeatures();
		}
		
		getStats();
		
		getSeenQUPairs();		
	}
	////feature
	protected void iniFeatures(){		
		if(_mode.equals(Mode.NaiveRele)){
			for(TQuery tQuery: this._QSessionList){			
				tQuery.calReleFeature(key2ReleFeatureMap, false);			
			}
			////
			//1
			normalizeFeatureByZScore_NaiveRele();
			//2
			//normalizeFeatureByMax_NaiveRele();
			
		}else if(_mode.equals(Mode.MarginalRele)){
			//1
			for(TQuery tQuery: this._QSessionList){			
				tQuery.calReleFeature(key2ReleFeatureMap, false);			
			}
			//2
			for(int i=0; i<this._QSessionList.size(); i++){
				TQuery tQuery = this._QSessionList.get(i);
				
				String qKey = tQuery.getKey()+":"+tQuery.getQueryText();
				tQuery.setMarTensor(key2MarFeatureMap.get(qKey));
				
				//should be called ahead of tQuery.calMarFeatureList() since the context features will used subsequently						
				tQuery.calMarFeatureList(true, false);			
			}
			//3
			normalizeFeatures_MarginalRele();
		}		
	}
	
	protected void normalizeFeatureByZScore_NaiveRele(){
		double[] mean = new double [_naiveReleFeaLen];
		double[] stdVar = new double [mean.length];
		
		getReleFeatureMeanStdVariance(mean, stdVar);
		
		for(TQuery tQuery: this._QSessionList){				
			for(TUrl tUrl: tQuery.getUrlList()){
				tUrl.releNormalize(mean, stdVar);				
			}
		}		
	}
	
	protected void getReleFeatureMeanStdVariance(double[] mean, double[] stdVar){
		double count=0, value;
		double[] featureVec;
		
		//mean
		for(TQuery tQuery: this._QSessionList){
			for(TUrl tUrl: tQuery.getUrlList()){
				featureVec = tUrl.getReleFeatures();				
				for(int i=0; i<3; i++){
					//value = Math.log(featureVec[i]);
					value = featureVec[i];
					mean[i] += value;					
				}
				for(int i=3; i<featureVec.length; i++){
					value = featureVec[i];
					mean[i] += value;
				}								
				count ++;								
			}
		}
		
		for(int i=0; i<mean.length; i++){
			mean[i] /= count;
		}
		
		//std variance
		for(TQuery tQuery: this._QSessionList){
			for(TUrl tUrl: tQuery.getUrlList()){
				featureVec = tUrl.getReleFeatures();				
				for(int i=0; i<3; i++){
					//value = Math.log(featureVec[i]);
					value = featureVec[i];
					stdVar [i] += Math.pow((value-mean[i]), 2);									
				}				
				for(int i=3; i<featureVec.length; i++){
					value = featureVec[i];
					stdVar [i] += Math.pow((value-mean[i]), 2);
				}				
			}
		}
		
		for(int i=0; i<stdVar.length; i++){
			stdVar [i] = Math.sqrt(stdVar[i]/(count-1));
		}
	}
	
	protected void normalizeFeatureByMax_NaiveRele(){		
		double[] maxFeatureVec = getMaxReleFeature();
		
		for(TQuery tQuery: this._QSessionList){				
			for(TUrl tUrl: tQuery.getUrlList()){
				tUrl.releNormalize(maxFeatureVec);				
			}
		}		
	}
	
	protected double[] getMaxReleFeature(){
		double[] maxFeatureVec = new double[_naiveReleFeaLen];

		for(TQuery tQuery: this._QSessionList){
			for(TUrl tUrl: tQuery.getUrlList()){
				double [] featureVec = tUrl.getReleFeatures();				
				
				for(int i=0; i<featureVec.length; i++){
					if(featureVec[i] > maxFeatureVec[i]){
						maxFeatureVec[i] = featureVec[i];
					}					
				}								
			}
		}
		
		return maxFeatureVec;		
	}
	
	////mar
	protected void normalizeFeatures_MarginalRele(){
		//rele part
		double[] releMean = new double [_naiveReleFeaLen];
		double[] releStdVar = new double [releMean.length];
		
		getReleFeatureMeanStdVariance(releMean, releStdVar);
		
		for(TQuery tQuery: this._QSessionList){
			int firstC = tQuery.getFirstClickPosition();
			TUrl tUrl = tQuery.getUrlList().get(firstC-1);
			tUrl.releNormalize(releMean, releStdVar);
		}
		
		//mar part
		double[] marMean = new double [getMarFeaLength()-_naiveReleFeaLen];
		double[] marStdVar = new double [marMean.length];
		
		getMarFeatureMeanStdVariance(marMean, marStdVar);
		
		for(TQuery tQuery: this._QSessionList){			
			tQuery.marNormalize_Total(marMean, marStdVar);
		}
	}
	
	protected void getMarFeatureMeanStdVariance(double[] marMean, double[] marStdVar){
		double count=0, value;
		double[] marFeatureVec;
		
		//mean
		for(TQuery tQuery: this._QSessionList){
			int firstC = tQuery.getFirstClickPosition();
			for(int rank=firstC+1; rank<=tQuery.getUrlList().size(); rank++){
				marFeatureVec = tQuery.getPureMarFeature(rank);				
				for(int i=0; i<marFeatureVec.length; i++){
					value = marFeatureVec[i];
					marMean[i] += value;
				}
			}
			
			count ++;
		}
		for(int i=0; i<marMean.length; i++){
			marMean[i] /= count;
		}
		
		//std variance
		for(TQuery tQuery: this._QSessionList){
			int firstC = tQuery.getFirstClickPosition();
			for(int rank=firstC+1; rank<=tQuery.getUrlList().size(); rank++){
				marFeatureVec = tQuery.getPureMarFeature(rank);				
				for(int i=0; i<marFeatureVec.length; i++){
					value = marFeatureVec[i];
					marStdVar [i] += Math.pow((value-marMean[i]), 2);
				}
			}
		}
		
		for(int i=0; i<marStdVar.length; i++){
			marStdVar [i] = Math.sqrt(marStdVar[i]/(count-1));
		}
	}
	
	protected double [] getComponentOfMarReleWeight() {
		return Arrays.copyOfRange(_twinWeights, 13, _twinWeights.length);
	}
	
	protected double [] getComponentOfNaiveReleWeight() {
		return Arrays.copyOfRange(_twinWeights, 0, 13);
	}
	
	protected int getMarFeaLength() {
		if(_marFeaVersion.equals(MarFeaVersion.V1)){
			return _twinFeatureLen_v_1;
		}else{
			return _twinFeatureLen_v_2;
		}
	}
	
	////objective function
	/**
	 * get the objective function value (log based version)
	 * needs refresh 
	 * **/
	protected double calMinObjFunctionValue(){
		if(_mode.equals(Mode.NaiveRele)){
			return calMinObjFunctionValue_NaiveRele();
		}else{
			return calMinObjFunctionValue_MarginalRele();		
		}			
	}
	
	protected abstract double calMinObjFunctionValue_NaiveRele();
	protected abstract double calMinObjFunctionValue_MarginalRele();
	
	/**
	 * get the gradient for optimization
	 * needs refresh
	 * **/
	protected void calFunctionGradient(double[] g){
		if(_mode.equals(Mode.NaiveRele)){
			calFunctionGradient_NaiveRele(g);
		}else{
			calFunctionGradient_MarginalRele(g);		
		}	
	}
	
	protected abstract void calFunctionGradient_NaiveRele(double[] g);
	protected abstract void calFunctionGradient_MarginalRele(double[] g);
	
	////optimization
	protected void optimize(int maxIter) throws ExceptionWithIflag{
		
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
				f = calMinObjFunctionValue();
				
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
		
	//
	/**
	 * refresh based on new weights, or preliminary call after initializing the weights
	 * e.g., for consistent refresh w.r.t. both gradient and objectiveFunction
	 * **/
	protected void updateAlpha(){
		if(_mode.equals(Mode.NaiveRele)){
			updateAlpha_NaiveRele();
		}else{
			updateAlpha_MarginalRele();		
		}
	}
	protected abstract void updateAlpha_NaiveRele();
	protected abstract void updateAlpha_MarginalRele();
	//
	protected abstract void estimateParas();
	protected abstract FunctionType getFunctionType();	
	protected void getStats(){
		if(_mode.equals(Mode.MarginalRele)){
			getStats_MarginalRele();
		}else{
			getStats_Static();
		}
	}
	
	protected abstract void getStats_Static();
	protected abstract void getStats_MarginalRele();
	
	public double getTestCorpusProb(boolean onlyClicks, boolean uniformCmp){
		getSeenVsUnseenInfor();
		
		double corpusLikelihood = 0.0;
		
		for(int k=this._trainNum; k<this._QSessionList.size(); k++){
			TQuery tQuery = this._QSessionList.get(k);
			
			if(skipQuerySession(tQuery, uniformCmp)){
				continue;
			}
			
			double sessionPro;
			if(_mode.equals(Mode.MarginalRele)){
				sessionPro = getSessionProb_MarginalRele(tQuery);
			}else{
				sessionPro = getSessionProb(tQuery, onlyClicks); 
			}
			
			corpusLikelihood += Math.log(sessionPro);
		}
		//the higher the better
		return corpusLikelihood;		
	}
	
	public abstract double getSessionProb(TQuery tQuery, boolean onlyClicks);
	public abstract double getSessionProb_MarginalRele(TQuery tQuery);
	
	//
	protected void getSeenQUPairs(){
		this._seenQUPairSet = new HashSet<>();		
		for(int i=0; i<this._trainNum; i++){
			TQuery tQuery = this._QSessionList.get(i);			
			for(TUrl tUrl: tQuery.getUrlList()){						
				String key = getKey(tQuery, tUrl);
				if(!this._seenQUPairSet.contains(key)){
					this._seenQUPairSet.add(key);
				}
			}
		}
	}
	////
	protected void getSeenVsUnseenInfor() {
		System.out.println();
		System.out.println("TrainNum: "+_trainNum+"\tTestNum: "+_testNum);
		
		int unseenQSessionCnt = 0;
		for(int k=this._trainNum; k<this._QSessionList.size(); k++){
			TQuery tQuery = this._QSessionList.get(k);			
			if(includeUnseeUrl(tQuery)){
				unseenQSessionCnt++;
			}
		}		
		System.out.println("seenInTest: "+_seenInTest.size()+"\tunseenInTest: "+_unseenInTest.size()+"\tratio: "+(_unseenInTest.size()*1.0/(_seenInTest.size()+_unseenInTest.size())));
		System.out.println("UnseenQuerySession: "+unseenQSessionCnt+"\tTestQuerySession: "+_testNum+"\tUnseenRation: "+(unseenQSessionCnt*1.0/_testNum));
	}
	
	protected boolean skipQuerySession(TQuery tQuery, boolean uniformaComparison){
		boolean hasUnseenQuery = includeUnseeUrl(tQuery);
		
		if(hasUnseenQuery){
			if(_mode.equals(Mode.Original)){
				return true;
			}else if(uniformaComparison){
				return true;
			}else{
				return false;
			}
		}else{
			return false;
		}
	}
	
	protected boolean includeUnseeUrl(TQuery tQuery){
		int unseenCnt = 0;
		
		for(TUrl tUrl: tQuery.getUrlList()){
			String key = getKey(tQuery, tUrl);
			
			if(this._seenQUPairSet.contains(key)){
				_seenInTest.add(key);
			}else{
				unseenCnt++;
				_unseenInTest.add(key);
			}
		}
		
		return unseenCnt>0;
	}
}
