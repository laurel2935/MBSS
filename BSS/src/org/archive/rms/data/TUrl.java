package org.archive.rms.data;

import org.archive.access.feature.IAccessor;

public class TUrl {
	
	String _urlStr;
	String _docNo;
	//adjust
	int _rankPostion;
	
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
	
	public void setReleVal(double releVal){
		this._releValue = releVal;
	}
	
	public int getRankPosition(){
		return this._rankPostion;
	}
	
	public void adjustRankPosition(int adjustedRankPosition){
		this._rankPostion = adjustedRankPosition;
	}
}
