package org.archive.rms.data;

import org.archive.access.feature.IAccessor;
import org.archive.rms.advanced.USMFrame;
import org.archive.rms.advanced.USMFrame.FunctionType;

public class TUrl {
	
	String _urlStr;
	String _docNo;
	//adjust
	int _rankPostion;
	////context information
	//number of prior clicks
	int _priorClicks;
	//distance to last click
	//type-1: using a tiny value to indicate being the first click
	double _disToLastClick_tiny;
	//type-2: ubm-style, i.e., no zero distance, if no prior click, then it is the rank position
	int _disToLastClick_UBM;
	
	int _gTruthClick;
	String _clickTime;
	
	boolean _htmlAvailable;
	
	double [] _rFeatureVector;
	double _releValue;
	
	
	////
	//the likelihood of being relevant
	double _relePro;
	//the likelihood of being marginally relevant
	public double _marRelePro;
	
	////
	public double _postMarRelePro;
	
	TUrl(String urlStr, int rankPosition, int gTruthClick, boolean htmlAvailable){
		this._urlStr = urlStr;
		this._rankPostion = rankPosition;
		this._gTruthClick = gTruthClick;
		this._htmlAvailable = htmlAvailable;
		//
		this._docNo = IAccessor.getDocNo(urlStr);		
	}
	
	public boolean isHtmlAvailable(){
		return this._htmlAvailable;
	}
	
	public int getGTruthClick(){
		return this._gTruthClick;
	}
	
	public String getDocNo(){
		return this._docNo;
	}
	
	public double [] getReleFeatures(){
		return this._rFeatureVector;
	}
	
	public double getReleValue(){
		return this._releValue;
	}
	
	public double calRelePro(double [] rele_weights){
		double releVal = USMFrame.calFunctionVal(_rFeatureVector, rele_weights, FunctionType.LINEAR);
		double relePro = USMFrame.logistic(releVal);
		
		this._relePro = relePro;
		return this._relePro;
	}
	
	public int getRankPosition(){
		return this._rankPostion;
	}
	
	public void adjustRankPosition(int adjustedRankPosition){
		this._rankPostion = adjustedRankPosition;
	}
	
	public void setReleVal(double releVal){
		this._releValue = releVal;
	}
	
	public void setContextInfor(int priorClicks, double disToLastClick_tiny, int disToLastClick_UBM){
		this._priorClicks = priorClicks;
		this._disToLastClick_tiny = disToLastClick_tiny;
		this._disToLastClick_UBM  = disToLastClick_UBM;
	}
	
	public void setReleFeatureVector(double [] rFeatureVector){
		this._rFeatureVector = rFeatureVector;
	}
	
	public void releNormalize(double[] mean, double[] stdVar){
		for(int i=0; i<3; i++){//frequency
			//_rFeatureVector[i] = Math.log(Math.abs(_rFeatureVector[i]));
			
			if(0 != stdVar[i]){
				_rFeatureVector[i] = (_rFeatureVector[i]-mean[i])/stdVar[i];
			}else{
				_rFeatureVector[i] = 0d;
			}
		}
		
		for(int i=3; i<_rFeatureVector.length; i++){
			if(0 != stdVar[i]){
				_rFeatureVector[i] = (_rFeatureVector[i]-mean[i])/stdVar[i];
			}else{
				_rFeatureVector[i] = 0d;
			}
		}
	}
	
	public void releNormalize(double[] maxFeatureVec){				
		for(int i=0; i<_rFeatureVector.length; i++){
			if(0 != maxFeatureVec[i]){
				_rFeatureVector[i] = _rFeatureVector[i]/maxFeatureVec[i];
			}else{
				_rFeatureVector[i] = 0d;
			}
		}
	}
	
	public int getPriorClicks(){
		return this._priorClicks;
	}
	
	public double getDistanceToLastClick_Tiny(){
		return this._disToLastClick_tiny;
	}
	
	public int getDistanceToLastClick_UBM(){
		return this._disToLastClick_UBM;
	}
	
	public String getUrl(){
		return this._urlStr;
	}
}
