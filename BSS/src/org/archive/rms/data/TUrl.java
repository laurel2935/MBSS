package org.archive.rms.data;

import org.archive.access.feature.IAccessor;

public class TUrl {
	
	String _urlStr;
	String _docNo;
	//adjust
	int _rankPostion;
	////context information
	//number of prior clicks
	int _priorClicks;
	//distance to last click
	int _disToLastClick;
	
	int _gTruthClick;
	String _clickTime;
	
	boolean _htmlAvailable;
	
	double [] _rFeatureVector;
	double _releValue;
	
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
		
	public int getRankPosition(){
		return this._rankPostion;
	}
	
	public void adjustRankPosition(int adjustedRankPosition){
		this._rankPostion = adjustedRankPosition;
	}
	
	public void setReleVal(double releVal){
		this._releValue = releVal;
	}
	
	public void setContextInfor(int priorClicks, int disToLastClick){
		this._priorClicks = priorClicks;
		this._disToLastClick = disToLastClick;
	}
	
	public void setReleFeatureVector(double [] rFeatureVector){
		this._rFeatureVector = rFeatureVector;
	}
	
	public int getPriorClicks(){
		return this._priorClicks;
	}
	
	public int getDisToLastClick(){
		return this._disToLastClick;
	}
}
