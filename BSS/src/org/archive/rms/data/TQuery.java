package org.archive.rms.data;

import java.util.ArrayList;
import java.util.HashMap;

import org.archive.access.utility.SimpleTensor;
import org.archive.rms.advanced.MAnalyzer;
import org.archive.rms.advanced.MClickModel;
import org.archive.rms.advanced.USMFrame;
import org.archive.rms.advanced.USMFrame.FunctionType;
import org.ejml.simple.SimpleMatrix;



public class TQuery {
	//
	public enum MarStyle {MIN, AVG, MAX};
	
	//serial number of acceptedSessions; userid ; rguid
	String _key;
	String _UserID;
	String _queryText;
	
	
	ArrayList<TUrl> _urlList;
	public ArrayList<Boolean> _gTruthClickSequence;
	
	//the likelihood of being observed
	double _sessionPro;
	
	//without marginal concept, just the relevance based utility
	ArrayList<Double>  _gTruthBasedReleValList;
	//for marginal utility based cumulative utility
	public ArrayList<Double>  _gTruthBasedMarValList;
	//corresponding to each clicked position
	//
	ArrayList<Double>  _gTruthBasedCumuUtilityList;
	ArrayList<Double>  _gTruthBasedSatProList;
	
	//without marginal utility
	//ArrayList<ArrayList<Double>> _gTruthBasedReleFeatureList; 
	//w.r.t. marginal utility
	ArrayList<ArrayList<Double>> _gTruthBasedMarFeatureList;
	
	
	SimpleTensor _marTensor;
	
	////for extending UBM & DBN
	ArrayList<Double> _gTruthBasedMarReleProList;
	
	
	public TQuery(BingQSession1 bingQSession){
		this._urlList = new ArrayList<>();
		this._gTruthClickSequence = new ArrayList<>();
		
		this._gTruthBasedMarReleProList = new ArrayList<>();
		
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
	
	
	public void calMarFeatureList(Boolean totalMar, boolean plusContextInfor){
		if(totalMar){
			calMarFeatureList_Total(plusContextInfor);
		}else{
			calMarFeatureList_Partial(plusContextInfor);
		}
	}
	
	/**
	 * do not calculate marginally relevant features considering the skipped document
	 * ever used for testing cumulative utility based methods
	 * **/
	private void calMarFeatureList_Partial(boolean plusContextInfor){
		this._gTruthBasedMarFeatureList = new ArrayList<>();
		
		int firstC = getFirstClickPosition();
		int secondC = getSubsequentClickPosition(firstC);
		
		for(int rank=1; rank<secondC; rank++){
			_gTruthBasedMarFeatureList.add(null);
		}
		
		if(plusContextInfor){
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
		}else{
			for(int rank=secondC; rank<=_urlList.size(); rank++){
				if(_gTruthClickSequence.get(rank-1)){
					ArrayList<Double> marFeatureVec = new ArrayList<>();
					
					//marginal features based on 3-dimension tensor
					double [] partialMarFeatureVector = calPartialMarFeature(rank, MClickModel._defaultMarStyle);
					for(int i=0; i<partialMarFeatureVector.length; i++){					
						marFeatureVec.add(partialMarFeatureVector[i]);
					}	
								
					_gTruthBasedMarFeatureList.add(marFeatureVec);				
				}else {
					_gTruthBasedMarFeatureList.add(null);
				}
			}
		}				
	}
	/**
	 * calculate marginally relevant features for skipped documents
	 * **/
	private void calMarFeatureList_Total(boolean plusContextInfor){
		this._gTruthBasedMarFeatureList = new ArrayList<>();
		
		int firstC = getFirstClickPosition();
		
		if(plusContextInfor){
			for(int rank=firstC+1; rank<=_urlList.size(); rank++){
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
			}
		}else{
			for(int rank=firstC+1; rank<=_urlList.size(); rank++){
				ArrayList<Double> marFeatureVec = new ArrayList<>();
				
				//marginal features based on 3-dimension tensor
				double [] partialMarFeatureVector = calPartialMarFeature(rank, MClickModel._defaultMarStyle);
				for(int i=0; i<partialMarFeatureVector.length; i++){					
					marFeatureVec.add(partialMarFeatureVector[i]);
				}	
							
				_gTruthBasedMarFeatureList.add(marFeatureVec);
			}
		}				
	}
	
	//mainly for framework without marginal concept
	
	public void calReleFeature(HashMap<String, ArrayList<Double>> key2ReleFeatureMap, boolean plusContextInfor){
		
		for(int rank=1; rank<=_urlList.size(); rank++){
			
			TUrl tUrl = _urlList.get(rank-1);			
			String urlKey = tUrl.getDocNo()+":"+ _queryText;
			
			ArrayList<Double> releFeatureList = new ArrayList<>();
			ArrayList<Double> releFeatureVec = key2ReleFeatureMap.get(urlKey);
			for(Double parF: releFeatureVec){
				releFeatureList.add(parF);
			}
			
			if(plusContextInfor){
				//context information
				//1 rankPosition
				int ctxt_RPos = tUrl.getRankPosition();
				releFeatureList.add((double)ctxt_RPos);
				//2 number of prior clicks
				int ctxt_PriorClicks = tUrl.getPriorClicks();
				releFeatureList.add((double)ctxt_PriorClicks);
				//3 distance to prior click
				double ctxt_DisToLastClick = tUrl.getDisToLastClick();
				releFeatureList.add(ctxt_DisToLastClick);
			}
			
			double [] releFeatures = MAnalyzer.toDArray(releFeatureList);
			
			tUrl.setReleFeatureVector(releFeatures);			
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
			
			marFeatureVector[s] = USMFrame._MAX;
			
			for(Integer iRank: priorClicks){
				double i_k_s_MarFVal = fMatrix.get(iRank-1, kRank-1);
				if(i_k_s_MarFVal < marFeatureVector[s]){
					marFeatureVector[s] = i_k_s_MarFVal;
				}
			}
			
			if(USMFrame._MAX == marFeatureVector[s]){
				marFeatureVector[s] = 0;
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

	/**
	 * cumulative utility w.r.t. the given rank position
	 * **/
	public double calCumulativeUtility(int kRank, boolean isMarginal){
		double cumuVal = 0.0;
		
		if(isMarginal){
			for(int iRank=1; iRank<=kRank; iRank++){
				cumuVal += this._gTruthBasedMarValList.get(iRank-1);
			}
		}else{
			for(int iRank=1; iRank<=kRank; iRank++){
				cumuVal += this._gTruthBasedReleValList.get(iRank-1);
			}
		}
		
		return cumuVal;		
	}
	//
	public boolean calQSessionPro(){
		int firstC = getFirstClickPosition();
		if(firstC <= 0){
			System.err.println("No more than 1 click Error!");
			System.exit(0);
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
	
	////////
	//Gradient
	///////
	/**
	 * 
	 * **/
	private double [] calRelePartialGradient_Exp(){
		
		int firstC = getFirstClickPosition();
		int lastC =  getLastClickPosition();
		
		if(firstC < lastC){//at least two clicks
			int len = this._urlList.get(0).getReleFeatures().length;
			
			////w.r.t. first click			
			double [] part1 = new double [len];			
			double firstC_releVal = this._urlList.get(firstC-1).getReleValue();
			double p1_seg_1 = 1/(1-_gTruthBasedSatProList.get(firstC-1));
			//being infinity
			if(USMFrame.isInfinityOptVal(p1_seg_1) && USMFrame.acceptInfinityOptVal()){
				p1_seg_1 = USMFrame._MAX;
			}
			double p1_seg_2 = -1.0;
			double p1_seg_3 = Math.exp(MClickModel.EPSILON+firstC_releVal)/Math.pow(1+Math.exp(MClickModel.EPSILON+firstC_releVal), 2);
			//being NaN
			if(USMFrame.isNaNOptVal(p1_seg_3) && USMFrame.acceptNaNOptVal()){
				p1_seg_3 = 0;
			}
			
			double p1_seg_4 = firstC_releVal;
			for(int i=0; i<len; i++){
				double [] firstCFeVector = this._urlList.get(firstC-1).getReleFeatures();
				double p1_seg_5 = firstCFeVector[i];
				//being NaN feature				
				if(USMFrame.isNaNFeature(p1_seg_5) && USMFrame.acceptNaNFeature()){
					p1_seg_5 = 0;
				}				
				part1[i] = p1_seg_1*p1_seg_2*p1_seg_3*p1_seg_4*p1_seg_5;
			}		
			
			////2nd-click - (lastC-1)-click
			double [] part2 = new double [len];			
			int secondClick   = getSubsequentClickPosition(firstC);
			int clickAboveLast= getPriorClickPosition(lastC);
			
			for(int cRank=secondClick; cRank<=clickAboveLast; cRank=getSubsequentClickPosition(cRank)){				
				double p2_seg_1 = 1/(1-_gTruthBasedSatProList.get(cRank-1));
				//being infinity
				if(USMFrame.isInfinityOptVal(p2_seg_1) && USMFrame.acceptInfinityOptVal()){
					p2_seg_1 = USMFrame._MAX;
				}
				
				double p2_seg_2 = -1.0;
				double p2_seg_3 = Math.exp(MClickModel.EPSILON+_gTruthBasedCumuUtilityList.get(cRank-1))/Math.pow(1+Math.exp(MClickModel.EPSILON+_gTruthBasedCumuUtilityList.get(cRank-1)), 2);
				//being NaN
				if(USMFrame.isNaNOptVal(p2_seg_3) && USMFrame.acceptNaNOptVal()){
					p2_seg_3 = 0;
				}
				
				double p2_seg_4 = firstC_releVal;
				for(int i=0; i<len; i++){
					double [] firstCFeVector = this._urlList.get(firstC-1).getReleFeatures();
					double p2_seg_5 = firstCFeVector[i];
					//being NaN feature
					if(USMFrame.isNaNFeature(p2_seg_5) && USMFrame.acceptNaNFeature()){
						p2_seg_5 = 0;
					}					
					part2[i] += p2_seg_1*p2_seg_2*p2_seg_3*p2_seg_4*p2_seg_5;
				}				
			}			
			
			////last click
			double [] part3 = new double[len];
			double p3_seg_1 = 1/_gTruthBasedSatProList.get(lastC-1);			
			if(USMFrame.isInfinityOptVal(p3_seg_1) && USMFrame.acceptInfinityOptVal()){
				p3_seg_1 = USMFrame._MAX;
			}			
			
			double p3_seg_2 = Math.exp(MClickModel.EPSILON+_gTruthBasedCumuUtilityList.get(lastC-1))/Math.pow(1+Math.exp(MClickModel.EPSILON+_gTruthBasedCumuUtilityList.get(lastC-1)), 2);
			//being NaN
			if(USMFrame.isNaNOptVal(p3_seg_2) && USMFrame.acceptNaNOptVal()){
				p3_seg_2 = 0;
			}
			
			double p3_seg_3 = firstC_releVal;
			for(int i=0; i<len; i++){
				double [] firstCFeVector = this._urlList.get(firstC-1).getReleFeatures();
				double p3_seg_4 = firstCFeVector[i];
				//being NaN feature
				if(USMFrame.isNaNFeature(p3_seg_4) && USMFrame.acceptNaNFeature()){
					p3_seg_4 = 0;
				}				
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
	//
	private double [] calRelePartialGradient_Linear(){
		
		int firstC = getFirstClickPosition();
		int lastC =  getLastClickPosition();
		
		if(firstC < lastC){//at least two clicks
			int len = this._urlList.get(0).getReleFeatures().length;
			
			////w.r.t. first click			
			double [] part1 = new double [len];			
			double firstC_releVal = this._urlList.get(firstC-1).getReleValue();
			double p1_seg_1 = 1/(1-_gTruthBasedSatProList.get(firstC-1));
			//being infinity
			if(USMFrame.isInfinityOptVal(p1_seg_1) && USMFrame.acceptInfinityOptVal()){
				p1_seg_1 = USMFrame._MAX;
			}
			double p1_seg_2 = -1.0;
			double p1_seg_3 = Math.exp(MClickModel.EPSILON+firstC_releVal)/Math.pow(1+Math.exp(MClickModel.EPSILON+firstC_releVal), 2);
			//being NaN
			if(USMFrame.isNaNOptVal(p1_seg_3) && USMFrame.acceptNaNOptVal()){
				p1_seg_3 = 0;
			}
			
			//double p1_seg_4 = firstC_releVal;
			for(int i=0; i<len; i++){
				double [] firstCFeVector = this._urlList.get(firstC-1).getReleFeatures();
				double p1_seg_5 = firstCFeVector[i];
				//being NaN feature				
				if(USMFrame.isNaNFeature(p1_seg_5) && USMFrame.acceptNaNFeature()){
					p1_seg_5 = 0;
				}				
				//part1[i] = p1_seg_1*p1_seg_2*p1_seg_3*p1_seg_4*p1_seg_5;
				part1[i] = p1_seg_1*p1_seg_2*p1_seg_3*p1_seg_5;
			}		
			
			////2nd-click - (lastC-1)-click
			double [] part2 = new double [len];			
			int secondClick   = getSubsequentClickPosition(firstC);
			int clickAboveLast= getPriorClickPosition(lastC);
			
			for(int cRank=secondClick; cRank<=clickAboveLast; cRank=getSubsequentClickPosition(cRank)){				
				double p2_seg_1 = 1/(1-_gTruthBasedSatProList.get(cRank-1));
				//being infinity
				if(USMFrame.isInfinityOptVal(p2_seg_1) && USMFrame.acceptInfinityOptVal()){
					p2_seg_1 = USMFrame._MAX;
				}
				
				double p2_seg_2 = -1.0;
				double p2_seg_3 = Math.exp(MClickModel.EPSILON+_gTruthBasedCumuUtilityList.get(cRank-1))/Math.pow(1+Math.exp(MClickModel.EPSILON+_gTruthBasedCumuUtilityList.get(cRank-1)), 2);
				//being NaN
				if(USMFrame.isNaNOptVal(p2_seg_3) && USMFrame.acceptNaNOptVal()){
					p2_seg_3 = 0;
				}
				
				//double p2_seg_4 = firstC_releVal;
				for(int i=0; i<len; i++){
					double [] firstCFeVector = this._urlList.get(firstC-1).getReleFeatures();
					double p2_seg_5 = firstCFeVector[i];
					//being NaN feature
					if(USMFrame.isNaNFeature(p2_seg_5) && USMFrame.acceptNaNFeature()){
						p2_seg_5 = 0;
					}					
					//part2[i] += p2_seg_1*p2_seg_2*p2_seg_3*p2_seg_4*p2_seg_5;
					part2[i] += p2_seg_1*p2_seg_2*p2_seg_3*p2_seg_5;
				}				
			}			
			
			////last click
			double [] part3 = new double[len];
			double p3_seg_1 = 1/_gTruthBasedSatProList.get(lastC-1);			
			if(USMFrame.isInfinityOptVal(p3_seg_1) && USMFrame.acceptInfinityOptVal()){
				p3_seg_1 = USMFrame._MAX;
			}			
			
			double p3_seg_2 = Math.exp(MClickModel.EPSILON+_gTruthBasedCumuUtilityList.get(lastC-1))/Math.pow(1+Math.exp(MClickModel.EPSILON+_gTruthBasedCumuUtilityList.get(lastC-1)), 2);
			//being NaN
			if(USMFrame.isNaNOptVal(p3_seg_2) && USMFrame.acceptNaNOptVal()){
				p3_seg_2 = 0;
			}
			
			//double p3_seg_3 = firstC_releVal;
			for(int i=0; i<len; i++){
				double [] firstCFeVector = this._urlList.get(firstC-1).getReleFeatures();
				double p3_seg_4 = firstCFeVector[i];
				//being NaN feature
				if(USMFrame.isNaNFeature(p3_seg_4) && USMFrame.acceptNaNFeature()){
					p3_seg_4 = 0;
				}				
				//part3[i] = p3_seg_1*p3_seg_2*p3_seg_3*p3_seg_4;
				part3[i] = p3_seg_1*p3_seg_2*p3_seg_4;
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
	
	public double [] calRelePartialGradient(FunctionType fType){
		if(fType.equals(FunctionType.EXP)){
			return calRelePartialGradient_Exp();
		}else if(fType.equals(FunctionType.LINEAR)){
			return calRelePartialGradient_Linear();
		}else{
			System.out.println("Unaccepted FunctionType Error!");
			System.exit(0);
			return null;
		}
	}
	
	/**
	 * 
	 * **/
	private double [] calMarPartialGradient_Exp(){
		int firstC = getFirstClickPosition();
		int lastC =  getLastClickPosition();
		
		if(firstC < lastC){
			//int len = this._marTensor.numSlices();
			int len = MClickModel.version_1_marLength;
						
			////part-1
			double [] mar_part1 = new double [len];
			int secondClick   = getSubsequentClickPosition(firstC);
			int clickAboveLast= getPriorClickPosition(lastC);
			
			for(int cRank=secondClick; cRank<=clickAboveLast; cRank=getSubsequentClickPosition(cRank)){
				double mar_p1_seg_1 = 1/(1-_gTruthBasedSatProList.get(cRank-1));
				//being infinity
				if(USMFrame.isInfinityOptVal(mar_p1_seg_1) && USMFrame.acceptInfinityOptVal()){
					mar_p1_seg_1 = USMFrame._MAX;
				}				
				
				double mar_p1_seg_2 = -1.0;
				double mar_p1_seg_3 = Math.exp(MClickModel.EPSILON+_gTruthBasedCumuUtilityList.get(cRank-1))/Math.pow(1+Math.exp(MClickModel.EPSILON+_gTruthBasedCumuUtilityList.get(cRank-1)), 2);
				//being NaN
				if(USMFrame.isNaNOptVal(mar_p1_seg_3) && USMFrame.acceptNaNOptVal()){
					mar_p1_seg_3 = 0;
				}
				
				//double mar_p1_seg_4 = _gTruthBasedCumuUtilityList.get(cRank-1)-firstC_releVal;	
				double [] mar_p1_seg_4 = new double [len];				
				ArrayList<Integer> priorRanks = getPriorClicks(cRank);
				for(int k=1; k<priorRanks.size(); k++){
					Integer pos = priorRanks.get(k);
					
					double [] marFeatureVec = getMarFeature_Join(pos);					
					
					for(int i=0; i<len; i++){						
						double marFeaVal = marFeatureVec[i];
						//being NaN feature
						if(USMFrame.isNaNFeature(marFeaVal) && USMFrame.acceptNaNFeature()){
							marFeaVal = 0;
						}
						
						mar_p1_seg_4[i] += (this._gTruthBasedMarValList.get(pos-1)*marFeaVal);
					}
				}								
				//
				for(int i=0; i<len; i++){					
					mar_part1[i] += mar_p1_seg_1*mar_p1_seg_2*mar_p1_seg_3*mar_p1_seg_4[i];					
				}
			}
			
			////part-2
			double [] mar_part2 = new double [len];
			double mar_p2_seg_1 = 1/(_gTruthBasedSatProList.get(lastC-1));
			//being infinity
			if(USMFrame.isInfinityOptVal(mar_p2_seg_1) && USMFrame.acceptInfinityOptVal()){
				mar_p2_seg_1 = USMFrame._MAX;
			}
			
			double mar_p2_seg_2 = Math.exp(MClickModel.EPSILON+_gTruthBasedCumuUtilityList.get(lastC-1))/Math.pow(1+Math.exp(MClickModel.EPSILON+_gTruthBasedCumuUtilityList.get(lastC-1)), 2);
			//being NaN
			if(USMFrame.isNaNOptVal(mar_p2_seg_2) && USMFrame.acceptNaNOptVal()){
				mar_p2_seg_2 = 0;
			}
			
			//double mar_p2_seg_3 = _gTruthBasedCumuUtilityList.get(lastC-1)-firstC_releVal;
			double [] mar_p2_seg_3 = new double [len];
			ArrayList<Integer> priorRanks = getPriorClicks(lastC);
			for(int k=1; k<priorRanks.size(); k++){
				Integer pos = priorRanks.get(k);
				
				double [] marFeatureVec = getMarFeature_Join(pos);
				
				for(int i=0; i<len; i++){					
					double marFeaVal = marFeatureVec[i];
					//being NaN feature
					if(USMFrame.isNaNFeature(marFeaVal) && USMFrame.acceptNaNFeature()){
						marFeaVal = 0;
					}
					
					mar_p2_seg_3[i] += (this._gTruthBasedMarValList.get(pos-1)*marFeaVal);
				}
			}			
			
			for(int i=0; i<len; i++){
				mar_part2[i] = mar_p2_seg_1*mar_p2_seg_2*mar_p2_seg_3[i];
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
	
	private double [] calMarPartialGradient_Linear(){
		int firstC = getFirstClickPosition();
		int lastC =  getLastClickPosition();
		
		if(firstC < lastC){
			//int len = this._marTensor.numSlices();
			int len = MClickModel.version_1_marLength;
						
			////part-1
			double [] mar_part1 = new double [len];
			int secondClick   = getSubsequentClickPosition(firstC);
			int clickAboveLast= getPriorClickPosition(lastC);
			
			for(int cRank=secondClick; cRank<=clickAboveLast; cRank=getSubsequentClickPosition(cRank)){
				double mar_p1_seg_1 = 1/(1-_gTruthBasedSatProList.get(cRank-1));
				//being infinity
				if(USMFrame.isInfinityOptVal(mar_p1_seg_1) && USMFrame.acceptInfinityOptVal()){
					mar_p1_seg_1 = USMFrame._MAX;
				}				
				
				double mar_p1_seg_2 = -1.0;
				double mar_p1_seg_3 = Math.exp(MClickModel.EPSILON+_gTruthBasedCumuUtilityList.get(cRank-1))/Math.pow(1+Math.exp(MClickModel.EPSILON+_gTruthBasedCumuUtilityList.get(cRank-1)), 2);
				//being NaN
				if(USMFrame.isNaNOptVal(mar_p1_seg_3) && USMFrame.acceptNaNOptVal()){
					mar_p1_seg_3 = 0;
				}
				
				//double mar_p1_seg_4 = _gTruthBasedCumuUtilityList.get(cRank-1)-firstC_releVal;	
				double [] mar_p1_seg_4 = new double [len];				
				ArrayList<Integer> priorRanks = getPriorClicks(cRank);
				for(int k=1; k<priorRanks.size(); k++){
					Integer pos = priorRanks.get(k);
					
					double [] marFeatureVec = getMarFeature_Join(pos);					
					
					for(int i=0; i<len; i++){						
						double marFeaVal = marFeatureVec[i];
						//being NaN feature
						if(USMFrame.isNaNFeature(marFeaVal) && USMFrame.acceptNaNFeature()){
							marFeaVal = 0;
						}
						
						mar_p1_seg_4[i] += (this._gTruthBasedMarValList.get(pos-1)*marFeaVal);
					}
				}								
				//
				for(int i=0; i<len; i++){					
					mar_part1[i] += mar_p1_seg_1*mar_p1_seg_2*mar_p1_seg_3*mar_p1_seg_4[i];					
				}
			}
			
			////part-2
			double [] mar_part2 = new double [len];
			double mar_p2_seg_1 = 1/(_gTruthBasedSatProList.get(lastC-1));
			//being infinity
			if(USMFrame.isInfinityOptVal(mar_p2_seg_1) && USMFrame.acceptInfinityOptVal()){
				mar_p2_seg_1 = USMFrame._MAX;
			}
			
			double mar_p2_seg_2 = Math.exp(MClickModel.EPSILON+_gTruthBasedCumuUtilityList.get(lastC-1))/Math.pow(1+Math.exp(MClickModel.EPSILON+_gTruthBasedCumuUtilityList.get(lastC-1)), 2);
			//being NaN
			if(USMFrame.isNaNOptVal(mar_p2_seg_2) && USMFrame.acceptNaNOptVal()){
				mar_p2_seg_2 = 0;
			}
			
			//double mar_p2_seg_3 = _gTruthBasedCumuUtilityList.get(lastC-1)-firstC_releVal;
			double [] mar_p2_seg_3 = new double [len];
			ArrayList<Integer> priorRanks = getPriorClicks(lastC);
			for(int k=1; k<priorRanks.size(); k++){
				Integer pos = priorRanks.get(k);
				
				double [] marFeatureVec = getMarFeature_Join(pos);
				
				for(int i=0; i<len; i++){					
					double marFeaVal = marFeatureVec[i];
					//being NaN feature
					if(USMFrame.isNaNFeature(marFeaVal) && USMFrame.acceptNaNFeature()){
						marFeaVal = 0;
					}
					
					mar_p2_seg_3[i] += (this._gTruthBasedMarValList.get(pos-1)*marFeaVal);
				}
			}			
			
			for(int i=0; i<len; i++){
				mar_part2[i] = mar_p2_seg_1*mar_p2_seg_2*mar_p2_seg_3[i];
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
	
	public double [] calMarPartialGradient(FunctionType fType){
		if(fType.equals(FunctionType.EXP)){
			
			return calMarPartialGradient_Exp();
			
		}else if(fType.equals(FunctionType.LINEAR)){
			
			return calMarPartialGradient_Linear();
			
		}else{
			System.out.println("Unaccepted FunctionType Error!");
			System.exit(0);
			return null;
		}
	}
	//
	public double [] calRelePartialGradient_NoMarginal(){		
		int firstC = getFirstClickPosition();
		int lastC =  getLastClickPosition();
		
		if(firstC < lastC){//at least two clicks
			int len = this._urlList.get(0).getReleFeatures().length;
			
			///w.r.t. first click			
			double [] part1 = new double [len];			
			double firstC_releVal = this._urlList.get(firstC-1).getReleValue();
			double p1_seg_1 = 1/(1-_gTruthBasedSatProList.get(firstC-1));
			if(USMFrame.isInfinityOptVal(p1_seg_1) && USMFrame.acceptInfinityOptVal()){
				p1_seg_1 = USMFrame._MAX;
			}
			
			double p1_seg_2 = -1.0;
			double p1_seg_3 = Math.exp(MClickModel.EPSILON+firstC_releVal)/Math.pow(1+Math.exp(MClickModel.EPSILON+firstC_releVal), 2);
			//being NaN
			if(USMFrame.isNaNOptVal(p1_seg_3) && USMFrame.acceptNaNOptVal()){
				p1_seg_3 = 0;
			}
			
			double p1_seg_4 = firstC_releVal;
			for(int i=0; i<len; i++){
				double [] firstCFeVector = this._urlList.get(firstC-1).getReleFeatures();
				double p1_seg_5 = firstCFeVector[i];
				//being NaN feature				
				if(USMFrame.isNaNFeature(p1_seg_5) && USMFrame.acceptNaNFeature()){
					p1_seg_5 = 0;
				}
				
				part1[i] = p1_seg_1*p1_seg_2*p1_seg_3*p1_seg_4*p1_seg_5;
			}			
			
			////2nd-click - (lastC-1)-click
			double [] part2 = new double [len];			
			int secondClick   = getSubsequentClickPosition(firstC);
			int clickAboveLast= getPriorClickPosition(lastC);
						
			for(int cRank=secondClick; cRank<=clickAboveLast; cRank=getSubsequentClickPosition(cRank)){				
				double p2_seg_1 = 1/(1-_gTruthBasedSatProList.get(cRank-1));				
				if(USMFrame.isInfinityOptVal(p2_seg_1) && USMFrame.acceptInfinityOptVal()){
					p2_seg_1 = USMFrame._MAX;
				}
				
				double p2_seg_2 = -1.0;
				double p2_seg_3 = Math.exp(MClickModel.EPSILON+_gTruthBasedCumuUtilityList.get(cRank-1))/Math.pow(1+Math.exp(MClickModel.EPSILON+_gTruthBasedCumuUtilityList.get(cRank-1)), 2);
				//being NaN
				if(USMFrame.isNaNOptVal(p2_seg_3) && USMFrame.acceptNaNOptVal()){
					p2_seg_3 = 0;
				}				
				//
				double [] p2_seg_4 = new double [len];
				ArrayList<Integer> priorRanks = getPriorClicks(cRank);
				for(Integer priorRank: priorRanks){
					for(int i=0; i<len; i++){						
						double p2_seg_5 = this._urlList.get(priorRank-1).getReleFeatures()[i];
						//being NaN feature				
						if(USMFrame.isNaNFeature(p2_seg_5) && USMFrame.acceptNaNFeature()){
							p2_seg_5 = 0;
						}
						
						p2_seg_4[i] += (this._gTruthBasedReleValList.get(priorRank-1)*p2_seg_5);
					}					
				}				
				//
				for(int i=0; i<len; i++){
					part2[i] += p2_seg_1*p2_seg_2*p2_seg_3*p2_seg_4[i];
				}				
			}			
			
			////last click
			double [] part3 = new double[len];
			double p3_seg_1 = 1/_gTruthBasedSatProList.get(lastC-1);
			if(USMFrame.isInfinityOptVal(p3_seg_1) && USMFrame.acceptInfinityOptVal()){
				p3_seg_1 = USMFrame._MAX;
			}
						
			double p3_seg_2 = Math.exp(MClickModel.EPSILON+_gTruthBasedCumuUtilityList.get(lastC-1))/Math.pow(1+Math.exp(MClickModel.EPSILON+_gTruthBasedCumuUtilityList.get(lastC-1)), 2);
			//being NaN
			if(USMFrame.isNaNOptVal(p3_seg_2) && USMFrame.acceptNaNOptVal()){
				p3_seg_2 = 0;
			}
			
			//double p3_seg_3 = firstC_releVal;
			double [] p3_seg_3 = new double [len];
			ArrayList<Integer> priorRanks = getPriorClicks(lastC);
			for(Integer priorRank: priorRanks){
				for(int i=0; i<len; i++){
					double p3_seg_4 = this._urlList.get(priorRank-1).getReleFeatures()[i];
					//being NaN feature				
					if(USMFrame.isNaNFeature(p3_seg_4) && USMFrame.acceptNaNFeature()){
						p3_seg_4 = 0;
					}
					
					p3_seg_3[i] += (this._gTruthBasedReleValList.get(priorRank-1)*p3_seg_4);					
				}
			}
			//
			for(int i=0; i<len; i++){				
				part3[i] = p3_seg_1*p3_seg_2*p3_seg_3[i];				
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
	public double calMarginalRele(int rankPosition, double [] mar_weights){
		ArrayList<Double> marFeatureList = this._gTruthBasedMarFeatureList.get(rankPosition-1);
		double [] marFeatureArray = MAnalyzer.toDArray(marFeatureList);
		
		double marReleVal = USMFrame.calFunctionVal(marFeatureArray, mar_weights, FunctionType.LINEAR);
		double marRelePro = USMFrame.logistic(marReleVal);
		
		this._gTruthBasedMarReleProList.set(rankPosition-1, marRelePro);		
		return marRelePro;	
	}
	
	public double calMarReleVal(int rankPosition, double [] mar_weights){
		ArrayList<Double> marFeatureList = this._gTruthBasedMarFeatureList.get(rankPosition-1);
		double [] marFeatureArray = MAnalyzer.toDArray(marFeatureList);
		
		double marReleVal = USMFrame.calFunctionVal(marFeatureArray, mar_weights, FunctionType.LINEAR);	
		return marReleVal;
	}
	
	public double calMarRelePro(int rankPosition, double [] mar_weights){
		ArrayList<Double> marFeatureList = this._gTruthBasedMarFeatureList.get(rankPosition-1);
		double [] marFeatureArray = MAnalyzer.toDArray(marFeatureList);
		
		double marReleVal = USMFrame.calFunctionVal(marFeatureArray, mar_weights, FunctionType.LINEAR);
		double marRelePro = USMFrame.logistic(marReleVal);
		return marRelePro;	
	}
	
	
	//
	public void marNormalize(double[] marMean, double[] marStdVar){
		ArrayList<ArrayList<Double>> new_gTruthBasedMarFeatureList = new ArrayList<>();
		
		int firstC = getFirstClickPosition();
		int secondC = getSubsequentClickPosition(firstC);
		for(int rank=1; rank<secondC; rank++){
			new_gTruthBasedMarFeatureList.add(null);
		}
		
		for(int rank=secondC; rank<=_urlList.size(); rank++){
			if(_gTruthClickSequence.get(rank-1)){
				ArrayList<Double> new_marFeatureVec = new ArrayList<>();
				
				ArrayList<Double> old_marFeatureVec = this._gTruthBasedMarFeatureList.get(rank-1);
				
				for(int i=0; i<old_marFeatureVec.size(); i++){
					double old_value = old_marFeatureVec.get(i);
					
					double new_value;
					if(0 != marStdVar[i]){
						new_value = (old_value-marMean[i])/marStdVar[i];
					}else{
						new_value = 0d;
					}					
					new_marFeatureVec.add(new_value);
				}
				
				new_gTruthBasedMarFeatureList.add(new_marFeatureVec);
				
			}else {
				new_gTruthBasedMarFeatureList.add(null);
			}
		}
		
		this._gTruthBasedMarFeatureList = new_gTruthBasedMarFeatureList;
	}
	
	//
	public void setGTruthClickSequence(ArrayList<Boolean> gTruthClickSequence){
		this._gTruthClickSequence = gTruthClickSequence;
	}
	//
	public void setGTruthBasedReleValues(ArrayList<Double> gTruthBasedReleValList){
		this._gTruthBasedReleValList = gTruthBasedReleValList;
	}
	//	
	public void setGTruthBasedMarValues(ArrayList<Double> gTruthBasedMarValList){
		this._gTruthBasedMarValList = gTruthBasedMarValList;
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
	
	public int getClickCount(){
		int cnt = 0;
		for(Boolean c: this._gTruthClickSequence){
			if(c){
				cnt++;
			}
		}
		
		return cnt;
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
	public double [] getMarFeature_Join(int cRank){
		ArrayList<Double> marFeatureVec = this._gTruthBasedMarFeatureList.get(cRank-1);
		
		for(Double f: marFeatureVec){
			if(Double.isNaN(f)){
				System.err.println("---ini mar feature error!");
				System.exit(0);
			}
		}
		
		
		double [] marFeatureVector = new double[marFeatureVec.size()];
		for(int i=0; i<marFeatureVec.size(); i++){
			marFeatureVector[i] = marFeatureVec.get(i);
		}
		
		double [] releFeatureVec = this._urlList.get(cRank-1).getReleFeatures();
		double [] allFeatures = USMFrame.combineArray(releFeatureVec, marFeatureVector);
		
		return allFeatures;
	}
	public double [] getPureMarFeature(int cRank){
		ArrayList<Double> marFeatureVec = this._gTruthBasedMarFeatureList.get(cRank-1);
		
		return MAnalyzer.toDArray(marFeatureVec);
	}
	
	public double getQSessionPro(){
		return this._sessionPro;
	}
	
}
