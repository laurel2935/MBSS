package org.archive.rms.data;

import java.util.ArrayList;

import org.archive.access.feature.IAccessor;
import org.archive.access.utility.SimpleTensor;
import org.archive.rms.MClickModel;
import org.ejml.simple.SimpleMatrix;

/**
 * Current assumption:
 * 1 non-clicked documents that locate below the clicked documents have zero marginal utility
 * **/

public class TQuery {
	//
	public enum MarStyle {MIN, AVG, MAX};
	
	//serial number of acceptedSessions; userid ; rguid
	String _key;
	String _UserID;
	String _queryText;
	
	ArrayList<TUrl> _urlList;
	double _sessionPro;
	
	//
	ArrayList<Boolean> _gTruthClickSequence;
	//
	ArrayList<Double>  _gTruthBasedMarValList;
	//corresponding to each clicked position
	ArrayList<Double>  _gTruthBasedCumuUtilityList;
	ArrayList<Double>  _gTruthBasedSatProList;
	ArrayList<ArrayList<Double>> _gTruthBasedMarFeatureList;
	
	
	SimpleTensor _marTensor;
	
	public TQuery(BingQSession1 bingQSession){
		this._urlList = new ArrayList<>();
		this._gTruthClickSequence = new ArrayList<>();
		
		//ini
		this._key = bingQSession.getKey();
		String [] keyParts = this._key.split("\t");
		this._UserID = keyParts[1];
		
		this._queryText = bingQSession.getQuery();
		
		ArrayList<Boolean> clickVector = bingQSession.getClickVector();
		ArrayList<String> displayedUrlList = bingQSession.getDisplayedUrlList();
		for(int i=0; i<clickVector.size(); i++){
			String urlStr = displayedUrlList.get(i);
			int rankPosition = i+1;
			int gTruthClick = clickVector.get(i)?1:0;
			boolean htmlAvailable = DataAccessor.isAccetped_HtmlAccessible_TextAva(urlStr);
			
			TUrl tUrl = new TUrl(urlStr, rankPosition, gTruthClick, htmlAvailable);
			this._urlList.add(tUrl);
			
			this._gTruthClickSequence.add(gTruthClick>0?true:false);
		}		
	}
		
	public void adjustUrlList(ArrayList<TUrl> adjustedUrlList){
		this._urlList = adjustedUrlList;
	}
	
	/**
	 * compute context information for each TUrl, the adjustment should be finished if needed
	 * **/
	public void calContextInfor(){
		for(int rankI=1; rankI<=this._urlList.size(); rankI++){
			TUrl tUrl = this._urlList.get(rankI-1);
			
			int priorClicks = this.getPriorClicks(rankI).size();
			int priorClickPos = this.getPriorClickPosition(rankI);
			
			double disToPriorClick;
			if(0 == priorClickPos){
				disToPriorClick = MClickModel.EPSILON;
			}else {
				disToPriorClick = rankI-priorClickPos;
			}
			
			tUrl.setContextInfor(priorClicks, disToPriorClick);
		}
	}
	
	/**
	 * 
	 * **/
	public void calMarFeatureList(){
		this._gTruthBasedMarFeatureList = new ArrayList<>();
		
		int firstC = getFirstClickPosition();
		int secondC = getSubsequentClickPosition(firstC);
		for(int rank=1; rank<secondC; rank++){
			_gTruthBasedMarFeatureList.add(null);
		}
		
		for(int rank=secondC; rank<=_urlList.size(); rank++){
			if(_gTruthClickSequence.get(rank-1)){
				ArrayList<Double> marFeatureVec = new ArrayList<>();
				
				//marginal features based on 3-dimension tensor
				double [] partialMarFeatureVector = calPartialMarFeature(rank, MClickModel._defaultMarStyle);
				for(int i=0; i<partialMarFeatureVector.length; i++){
					marFeatureVec.add(partialMarFeatureVector[i]);
				}
				
				//marginal features based on context information				
				TUrl tUrl = _urlList.get(rank-1);
				//rankPosition
				int ctxt_RPos = tUrl.getRankPosition();
				marFeatureVec.add((double)ctxt_RPos);
				//number of prior clicks
				int ctxt_PriorClicks = tUrl.getPriorClicks();
				marFeatureVec.add((double)ctxt_PriorClicks);
				//distance to prior click
				double ctxt_DisToLastClick = tUrl.getDisToLastClick();
				marFeatureVec.add(ctxt_DisToLastClick);				
				
				_gTruthBasedMarFeatureList.add(marFeatureVec);				
			}else {
				_gTruthBasedMarFeatureList.add(null);
			}
		}		
	}
	
	/**
	 * 
	 * **/
	private double [] calPartialMarFeature(int kRank, MarStyle marStyle) {
		ArrayList<Integer> priorClicks = getPriorClicks(kRank);
		if(marStyle == MarStyle.MIN){
			return calMarFeature_Min(kRank, priorClicks);
		}else if(marStyle == MarStyle.AVG){
			return calMarFeature_Avg(kRank, priorClicks);
		}else {
			return calMarFeature_Max(kRank, priorClicks);
		}		
	}
	private double [] calMarFeature_Min(int kRank, ArrayList<Integer> priorClicks){
		int sliceNum = _marTensor.numSlices();
		double [] marFeatureVector = new double [sliceNum];
		
		for(int s=0; s<sliceNum; s++){
			SimpleMatrix fMatrix = _marTensor.getSlice(s);
			
			marFeatureVector[s] = Double.MAX_VALUE;
			
			for(Integer iRank: priorClicks){
				double i_k_s_MarFVal = fMatrix.get(iRank-1, kRank-1);
				if(i_k_s_MarFVal < marFeatureVector[s]){
					marFeatureVector[s] = i_k_s_MarFVal;
				}
			}
		}
		
		return marFeatureVector;
	}
	private double [] calMarFeature_Avg(int kRank, ArrayList<Integer> priorClicks){
		int sliceNum = _marTensor.numSlices();
		double [] marFeatureVector = new double [sliceNum];
		
		for(int s=0; s<sliceNum; s++){
			SimpleMatrix fMatrix = _marTensor.getSlice(s);
			double sSum = 0.0;
			
			for(Integer iRank: priorClicks){
				double i_k_s_MarFVal = fMatrix.get(iRank-1, kRank-1);
				sSum += i_k_s_MarFVal;
			}
			
			marFeatureVector[s] = sSum/priorClicks.size();			
		}
		
		return marFeatureVector;
	}
	private double [] calMarFeature_Max(int kRank, ArrayList<Integer> priorClicks){
		int sliceNum = _marTensor.numSlices();
		double [] marFeatureVector = new double [sliceNum];
		
		for(int s=0; s<sliceNum; s++){
			SimpleMatrix fMatrix = _marTensor.getSlice(s);
			
			marFeatureVector[s] = Double.MIN_VALUE;
			
			for(Integer iRank: priorClicks){
				double i_k_s_MarFVal = fMatrix.get(iRank-1, kRank-1);
				if(i_k_s_MarFVal > marFeatureVector[s]){
					marFeatureVector[s] = i_k_s_MarFVal;
				}
			}
		}
		
		return marFeatureVector;
	}
	public void setGTruthBasedMarValues(ArrayList<Double> gTruthBasedMarValList){
		this._gTruthBasedMarValList = gTruthBasedMarValList;
	}
	/**
	 * cumulative utility w.r.t. the given rank position
	 * **/
	public double calCumulativeUtility(int kRank){
		double cumuVal = 0.0;
		for(int iRank=1; iRank<=kRank; iRank++){
			cumuVal += this._gTruthBasedMarValList.get(iRank-1);
		}
		return cumuVal;		
	}
	//
	public boolean calQSessionPro(){
		int firstC = getFirstClickPosition();
		if(firstC <= 0){
			return false;
		}else{
			int lastC = getLastClickPosition();
			if(firstC == lastC){
				this._sessionPro = this._gTruthBasedSatProList.get(firstC-1);
				return true;
			}else{
				double satPro = 1.0;
				for(int kRank=firstC; kRank<lastC; kRank++){
					if(this._gTruthClickSequence.get(kRank-1)){					
						satPro *= (1-this._gTruthBasedSatProList.get(kRank-1));
					}
				}
				satPro *= this._gTruthBasedSatProList.get(lastC-1);
				
				this._sessionPro = satPro;
				return true;
			}
		}			
	}
	
	/**
	 * 
	 * **/
	public double [] calRelePartialGradient(){
		
		int firstC = getFirstClickPosition();
		int lastC =  getLastClickPosition();
		
		if(firstC < lastC){//at least two clicks
			int len = this._urlList.get(0).getReleFeatures().length;
			
			//w.r.t. first click			
			double [] part1 = new double [len];
			
			double firstC_releVal = this._urlList.get(firstC-1).getReleValue();
			double p1_seg_1 = 1/(1-_gTruthBasedSatProList.get(firstC-1));
			double p1_seg_2 = -1.0;
			double p1_seg_3 = Math.exp(MClickModel.EPSILON+firstC_releVal)/Math.pow(1+Math.exp(MClickModel.EPSILON+firstC_releVal), 2);
			double p1_seg_4 = firstC_releVal;
			for(int i=0; i<len; i++){
				double [] firstCFeVector = this._urlList.get(firstC-1).getReleFeatures();
				double p1_seg_5 = firstCFeVector[i];
				part1[i] = p1_seg_1*p1_seg_2*p1_seg_3*p1_seg_4*p1_seg_5;
			}			
			//2nd-click - (lastC-1)-click
			double [] part2 = new double [len];			
			int secondClick   = getSubsequentClickPosition(firstC);
			int clickAboveLast= getPriorClickPosition(lastC);
			
			for(int cRank=secondClick; cRank<=clickAboveLast; cRank=getSubsequentClickPosition(cRank)){				
				double p2_seg_1 = 1/(1-_gTruthBasedSatProList.get(cRank-1));
				double p2_seg_2 = -1.0;
				double p2_seg_3 = Math.exp(MClickModel.EPSILON+_gTruthBasedCumuUtilityList.get(cRank-1))/Math.pow(1+Math.exp(MClickModel.EPSILON+_gTruthBasedCumuUtilityList.get(cRank-1)), 2);
				double p2_seg_4 = firstC_releVal;
				for(int i=0; i<len; i++){
					double [] firstCFeVector = this._urlList.get(firstC-1).getReleFeatures();
					double p2_seg_5 = firstCFeVector[i];
					part2[i] += p2_seg_1*p2_seg_2*p2_seg_3*p2_seg_4*p2_seg_5;
				}				
			}			
			//last click
			double [] part3 = new double[len];
			double p3_seg_1 = 1/_gTruthBasedSatProList.get(lastC-1);
			double p3_seg_2 = Math.exp(MClickModel.EPSILON+_gTruthBasedCumuUtilityList.get(lastC-1))/Math.pow(1+Math.exp(MClickModel.EPSILON+_gTruthBasedCumuUtilityList.get(lastC-1)), 2);
			double p3_seg_3 = firstC_releVal;
			for(int i=0; i<len; i++){
				double [] firstCFeVector = this._urlList.get(firstC-1).getReleFeatures();
				double p3_seg_4 = firstCFeVector[i];
				part3[i] = p3_seg_1*p3_seg_2*p3_seg_3*p3_seg_4;
			}
			
			//combined partial gradient
			double [] rele_parGradient = new double[len];
			for(int i=0; i<len; i++){
				rele_parGradient[i] = part1[i]+part2[i]+part3[i];
			}
			
			return rele_parGradient;			
		}else {
			return null;
		}		
	}
	/**
	 * 
	 * **/
	public double [] calMarPartialGradient(){
		int firstC = getFirstClickPosition();
		int lastC =  getLastClickPosition();
		
		if(firstC < lastC){
			//int len = this._marTensor.numSlices();
			int len = IAccessor._marFeatureLength;
			
			double firstC_releVal = this._urlList.get(firstC-1).getReleValue();
			//part-1
			double [] mar_part1 = new double [len];
			int secondClick   = getSubsequentClickPosition(firstC);
			int clickAboveLast= getPriorClickPosition(lastC);
			
			for(int cRank=secondClick; cRank<=clickAboveLast; cRank=getSubsequentClickPosition(cRank)){
				double mar_p1_seg_1 = 1/(1-_gTruthBasedSatProList.get(cRank-1));
				double mar_p1_seg_2 = -1.0;
				double mar_p1_seg_3 = Math.exp(MClickModel.EPSILON+_gTruthBasedCumuUtilityList.get(cRank-1))/Math.pow(1+Math.exp(MClickModel.EPSILON+_gTruthBasedCumuUtilityList.get(cRank-1)), 2);
				double mar_p1_seg_4 = _gTruthBasedCumuUtilityList.get(cRank-1)-firstC_releVal;	
				double [] marFeatureVec = getMarFeature(cRank);
				for(int i=0; i<len; i++){					
					double mar_p1_seg_5 = marFeatureVec[i];
					mar_part1[i] += mar_p1_seg_1*mar_p1_seg_2*mar_p1_seg_3*mar_p1_seg_4*mar_p1_seg_5;					
				}
			}
			//part-2
			double [] mar_part2 = new double [len];
			double mar_p2_seg_1 = 1/(_gTruthBasedSatProList.get(lastC-1));
			double mar_p2_seg_2 = Math.exp(MClickModel.EPSILON+_gTruthBasedCumuUtilityList.get(lastC-1))/Math.pow(1+Math.exp(MClickModel.EPSILON+_gTruthBasedCumuUtilityList.get(lastC-1)), 2);
			double mar_p2_seg_3 = _gTruthBasedCumuUtilityList.get(lastC-1)-firstC_releVal;	
			double [] marFeatureVec = getMarFeature(lastC);
			for(int i=0; i<len; i++){
				double mar_p2_seg_4 = marFeatureVec[i];
				mar_part2[i] = mar_p2_seg_1*mar_p2_seg_2*mar_p2_seg_3*mar_p2_seg_4;
			}
			
			//combined partial gradient
			double [] mar_parGradient = new double[len];
			for(int i=0; i<len; i++){
				mar_parGradient[i] = mar_part1[i]+mar_part2[i];
			}
			
			return mar_parGradient;
		}else{
			return null;
		}		
	}
	
	//
	public void setGTruthClickSequence(ArrayList<Boolean> gTruthClickSequence){
		this._gTruthClickSequence = gTruthClickSequence;
	}
	//
	public void setCumuVals(ArrayList<Double>  gTruthBasedCumuUtilityList){
		this._gTruthBasedCumuUtilityList = gTruthBasedCumuUtilityList;
	}
	//
	public void setGTruthBasedSatPros(ArrayList<Double> gTruthBasedSatProList){
		this._gTruthBasedSatProList = gTruthBasedSatProList;
	}
	//
	public void setMarTensor(SimpleTensor marTensor){
		this._marTensor = marTensor;
	}
	
	public String getUserID(){
		return this._UserID;
	}
	public ArrayList<TUrl> getUrlList(){
		return this._urlList;
	}
	public String getQueryText(){
		return this._queryText;
	}
	public String getKey(){
		return this._key;
	}
	public ArrayList<Boolean> getGTruthClickSequence(){
		return this._gTruthClickSequence;
	}
	
	/**
	 * zero means no click
	 * **/
	public int getFirstClickPosition(){
		int cRank=1;
		for(; cRank<=_urlList.size(); cRank++){
			if(_urlList.get(cRank-1).getGTruthClick() > 0){
				return cRank;
			}
		}
		return 0;
	}
	/**
	 * zero means no click
	 * **/
	public int getSubsequentClickPosition(int refRank){
		for(int cRank=refRank+1; cRank<=_urlList.size(); cRank++){
			if(_urlList.get(cRank-1).getGTruthClick() > 0){
				return cRank;
			}
		}
		return 0;
	}
	/**
	 * the rank position of previous click
	 * **/
	public int getPriorClickPosition(int refRank){
		for(int cRank=refRank-1; cRank>=1; cRank--){
			if(_urlList.get(cRank-1).getGTruthClick() > 0){
				return cRank;
			}
		}
		return 0;
	}
	/**
	 * zero means no click
	 * **/
	public int getLastClickPosition(){
		int c=_urlList.size();
		for(; c>=1; c--){
			if(_urlList.get(c-1).getGTruthClick() > 0){
				return c;
			}
		}
		return 0;
	}
	/**
	 * get the set of rank positions w.r.t. clicked documents above a specific rank position 
	 * **/
	public ArrayList<Integer> getPriorClicks(int kRank){
		ArrayList<Integer> priorClicks = new ArrayList<>();
		for(int iRank=1; iRank<kRank; iRank++){
			if(_gTruthClickSequence.get(iRank-1)){
				priorClicks.add(iRank);
			}
		}
		return priorClicks;
	}
	//
	public ArrayList<Double> getCumuVals(){
		return this._gTruthBasedCumuUtilityList;
	}	
	//
	public double [] getMarFeature(int cRank){
		ArrayList<Double> marFeatureVec = this._gTruthBasedMarFeatureList.get(cRank-1);
		double [] marFeatureVector = new double[marFeatureVec.size()];
		for(int i=0; i<marFeatureVec.size(); i++){
			marFeatureVector[i] = marFeatureVec.get(i);
		}
		return marFeatureVector;
	}
	
	public double getQSessionPro(){
		return this._sessionPro;
	}
	
}
