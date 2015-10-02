package org.archive.rms.advanced;

import java.util.ArrayList;
import java.util.Random;

import org.archive.access.feature.IAccessor;
import org.archive.rms.data.TQuery;
import org.archive.rms.data.TUser;
import org.archive.rms.data.TQuery.MarStyle;
import org.archive.rms.data.TUrl;

import cc.mallet.types.MatrixOps;
import optimizer.LBFGS;
import optimizer.LBFGS.ExceptionWithIflag;

/**
 * The basic structure required within the marginal utility based framework
 * 
 * Underlying association:
 * (1) Premise: for the input of "ArrayList<TUser> userList", all the required rele-features and mar-features are loaded 
 * **/

public class MClickModel extends USMFrame{
	/**
	 * represents 
	 * e.g., (1) weight vector for relevance (utility); as well as the relevance function
	 * (2) weight vector for marginal relevance (marginal utility); as well as the marginal utility function
	 * (3) compute the session likelihood
	 * (4) load the query log data
	 * **/
	/**
	 * 1. optimise(); //调用lbfg, 学习最优参数；
	 * 
	 * 2. train();    //调用optimise(), 完成训练；
	 * 
	 * 3. evaluation();
	 * 
	 * **/	
	
	
	public static MarStyle _defaultMarStyle = MarStyle.MIN;
	
	//weight vector
	//w.r.t. utility
	//private double[] _r_weights;
	//private double[] _optimal_r_weights;
	//w.r.t. marginal utility
	//private double[] _m_weights;
	//private double[] _optimal_m_weights;
	
	//
	//int _releFeatureLength;
	//int _marFeatureLength;
	//combined parameter vector corresponding to both utility and marginal utility
	//i.e., _rele_mar_weights.length = _releFeatureLength + _marFeatureLength;
	private double [] _rele_mar_weights;		
	
//	//ArrayList<TUser> _userList;
//	
//	MClickModel(ArrayList<TUser> userList){
//		this._userList = userList;
//	}
	
	MClickModel(double testRatio){
		super(testRatio);
	}
	//////////
	//Part: Optimization
	//////////	
	/**
	 * initialize the parameters, i.e., the weights w.r.t. each feature.
	 * **/
	@Override
	protected void ini(){
		//1 as for features
		for(TQuery tQuery: this._QSessionList){
			String qKey = tQuery.getKey()+":"+tQuery.getQueryText();
			tQuery.setMarTensor(key2MarFeatureMap.get(qKey));
			
			//context information
			tQuery.calContextInfor();			
			
			for(TUrl tUrl: tQuery.getUrlList()){
				String urlKey = tUrl.getDocNo()+":"+tQuery.getQueryText();
				
				ArrayList<Double> releFeatureVec = new ArrayList<>();
				
				////former part
				ArrayList<Double> partialReleFeatures = key2ReleFeatureMap.get(urlKey);
				for(Double parF: partialReleFeatures){
					releFeatureVec.add(parF);
				}
				
				////later part
				//context information
				//1 rankPosition
				int ctxt_RPos = tUrl.getRankPosition();
				releFeatureVec.add((double)ctxt_RPos);
				//2 number of prior clicks
				int ctxt_PriorClicks = tUrl.getPriorClicks();
				releFeatureVec.add((double)ctxt_PriorClicks);
				//3 distance to prior click
				double ctxt_DisToLastClick = tUrl.getDisToLastClick();
				releFeatureVec.add(ctxt_DisToLastClick);
				
				tUrl.setReleFeatureVector(toDArray(releFeatureVec));
			}				
		}
		
		for(int i=0; i<this._QSessionList.size(); i++){
			TQuery tQuery = this._QSessionList.get(i);
			//should be called ahead of tQuery.calMarFeatureList() since the context features will used subsequently						
			tQuery.calMarFeatureList();
		}
		
		////2 normalizing features
		normalize();

		////3
		iniWeightVector();		
	}
	@Override
	protected void iniWeightVector(){
		
		_rele_mar_weights =new double[IAccessor._releFeatureLength+IAccessor._marFeatureLength];
		
		double defaultScale = 50;
		
		Random rand = new Random();
		for(int i=0; i<_rele_mar_weights.length; i++){
			_rele_mar_weights [i] = (2*rand.nextDouble()-1)/defaultScale;
		}
	}
			
	@Override
	protected void optimize(int maxIter) throws ExceptionWithIflag{
		
		_lbfgsOptimizer = new LBFGS();
		
		int[] iprint = {-1, 0}, iflag = {0};
		
		//gradient w.r.t. the function
		double[] g = new double[_rele_mar_weights.length], diag = new double[_rele_mar_weights.length];

		int iter = 0;
		//objVal
		double f=0;	
				
		do {			
			
			if (iflag[0]==0 || iflag[0]==1){
				refresh();
				
				//function value based on the posterior graph inference! 
				f = calObjFunctionValue();
				
				System.out.println("Iter-"+iter+":\t"+f);
				
				calFunctionGradient(g);
				MatrixOps.timesEquals(g, -1);
			}
			
			//if (iflag[0]==0 || iflag[0]==2){//if we want to estimate the diagonals by ourselves
			//	getDiagnoal(diag, iflag[0]==2);
			//}
			
			try{
				
				_lbfgsOptimizer.lbfgs(_rele_mar_weights.length, 5, _rele_mar_weights, -f, g, false, diag, iprint, 1e-3, 1e-3, iflag);

			} catch (ExceptionWithIflag ex){
				System.err.println("[Warning]M-step cannot proceed!");
			}	
			
			System.out.println("..."+iter);
			
		}while(iflag[0]>0 && ++iter<maxIter);
	}
	/**
	 * get the gradient for optimization
	 * needs refresh
	 * **/
	private void calFunctionGradient(double[] g){		
		//rele part
		double [] total_rele_parGradient = new double[IAccessor._releFeatureLength];
		
		for(int k=0; k<this._trainNum; k++){
			TQuery tQuery = this._QSessionList.get(k);
			
			double [] rele_parGradient = tQuery.calRelePartialGradient();
			//System.out.println("L:\t"+rele_parGradient.length);
			for(int i=0; i<IAccessor._releFeatureLength; i++){
				total_rele_parGradient[i] += rele_parGradient[i];
			}
		}		
		
		for(int i=0; i<IAccessor._releFeatureLength; i++){
			g[i] = total_rele_parGradient[i];
		}
		
		//mar part
		double [] total_mar_parGradient = new double[IAccessor._marFeatureLength];
		
		for(int k=0; k<this._trainNum; k++){
			TQuery tQuery = this._QSessionList.get(k);
			
			double [] mar_parGradient = tQuery.calMarPartialGradient();
			
			for(int j=0; j<IAccessor._marFeatureLength; j++){
				total_mar_parGradient[j] += mar_parGradient[j];
			}
		}
		
		//context part
		
		for(int j=0; j<IAccessor._marFeatureLength; j++){
			g[IAccessor._releFeatureLength+j] = total_mar_parGradient[j];
		}		
	}

	public double getSessionProb(TQuery tQuery, boolean onlyClicks){
		tQuery.calMarFeatureList();
		
		tQuery.calQSessionPro();
		
		return tQuery.getQSessionPro();
	}
	
	public double getTestCorpusProb(boolean onlyClick) {
		for(int i=this._trainNum; i<this._QSessionList.size(); i++){
			TQuery tQuery = this._QSessionList.get(i);
			//should be called ahead of tQuery.calMarFeatureList() since the context features will used subsequently						
			tQuery.calMarFeatureList();
		}
		
		if(!onlyClick){
			System.err.println("USM-similar models only use clicks!");
		}
		
		for(int k=this._testNum; k<this._QSessionList.size(); k++){
			TQuery tQuery = this._QSessionList.get(k);
			calQSessionSatPros(tQuery);
			
			tQuery.calQSessionPro();
		}
		
		double corpusLikelihood = 0.0;
		
		for(int k=this._testNum; k<this._QSessionList.size(); k++){
			TQuery tQuery = this._QSessionList.get(k);
			double sessionPro = tQuery.getQSessionPro();
			corpusLikelihood += Math.log(sessionPro);
		}
		//the higher the better
		return corpusLikelihood;	
	}
	////////
	//
	////////
	/**
	 * Exponential function based relevance function 
	 * **/
	@Override
	protected double calReleVal(TUrl tUrl){
		double [] releParas = new double [IAccessor._releFeatureLength];
		for(int i=0; i<IAccessor._releFeatureLength; i++){
			releParas[i] = _rele_mar_weights[i];
		}
	
		double [] releFreatureVec = tUrl.getReleFeatures();
		
		double dotProVal = dotProduct(releFreatureVec, releParas);
		double releVal = Math.exp(dotProVal);
		
		tUrl.setReleVal(releVal);		
		return releVal;
	}
	/**
	 * true: success w.r.t. QSessions including at least one click
	 * false: input QSession includes no click
	 * **/
	private boolean calGTruthBasedMarVals(TQuery tQuery){
		int firstC = tQuery.getFirstClickPosition();		
		if(firstC <= 0){
			return false;
		}
		
		//
		for(TUrl tUrl: tQuery.getUrlList()){
			calReleVal(tUrl);
		}
		
		ArrayList<Double> gTruthBasedMarValList = new ArrayList<>();		
		ArrayList<Boolean> gTruthClickSeq = tQuery.getGTruthClickSequence();		
		//documents above the first clicked document are assumed to be irrelevant
		for(int iRank=1; iRank<firstC; iRank++){			
			gTruthBasedMarValList.add(0.0);
		}
		
		//firstC utility=marginal utility
		gTruthBasedMarValList.add(tQuery.getUrlList().get(firstC-1).getReleValue());
		
		double []marParas = new double [IAccessor._marFeatureLength];
		for(int i=0; i<IAccessor._marFeatureLength; i++){
			marParas[i] = _rele_mar_weights[IAccessor._releFeatureLength+i];
		}
		
		//url-specific context information
		//tQuery.calContextInfor();
		
		for(int kRank=firstC+1; kRank<=gTruthClickSeq.size(); kRank++){
			if(gTruthClickSeq.get(kRank-1)){
				//without context information
				//double [] marFeatureVector_part1 = tQuery.getMarFeature(kRank);
				double [] marFeatureVector = tQuery.getMarFeature(kRank);

				/*
//				double [] marFeatureVector = new double [IAccessor._marFeatureLength];
//				for(int i=0; i<marFeatureVector_part1.length; i++){
//					marFeatureVector [i] = marFeatureVector_part1[i];
//				}
//				
//				TUrl tUrl = tQuery.getUrlList().get(kRank-1);
//				int st = marFeatureVector_part1.length;
//				marFeatureVector [st] = tUrl.getRankPosition();
//				marFeatureVector [st+1] = tUrl.getPriorClicks();
//				marFeatureVector [st+2] = tUrl.getDisToLastClick();
				*/
				
				double dotProVal = dotProduct(marFeatureVector, marParas);
				double marVal = Math.exp(dotProVal);
				gTruthBasedMarValList.add(marVal);
			}else{
				gTruthBasedMarValList.add(0.0);
			}
		}
		
		tQuery.setGTruthBasedMarValues(gTruthBasedMarValList);
		return true;
	}
	/**
	 * 
	 * **/
	protected void calGTruthBasedCumuVals(TQuery tQuery){
		calGTruthBasedMarVals(tQuery);
		
		ArrayList<Double> gTruthBasedCumuValList = new ArrayList<>();
		
		ArrayList<Boolean> gTruthClickSeq = tQuery.getGTruthClickSequence();
		for(int iRank=1; iRank<=gTruthClickSeq.size(); iRank++){
			double cumuUtilityVal = tQuery.calCumulativeUtility(iRank, true);
			gTruthBasedCumuValList.add(cumuUtilityVal);
		}
		
		tQuery.setCumuVals(gTruthBasedCumuValList);
	}
		
	//
	protected void outputParas(){
		for(double w: _rele_mar_weights){
			System.out.print(w+"\t");
		}
		System.out.println();
	}
	
	protected void getReleFeatureMeanStdVariance(double[] mean, double[] stdVar){
		double count=0, value;
		double[] featureVec;
		
		//mean
		for(TQuery tQuery: this._QSessionList){			
			int firstC = tQuery.getFirstClickPosition();
			TUrl tUrl = tQuery.getUrlList().get(firstC-1);
			
			featureVec = tUrl.getReleFeatures();
			
			for(int i=0; i<3; i++){
				value = Math.log(featureVec[i]);
				mean[i] += value;					
			}
			for(int i=3; i<featureVec.length; i++){
				value = featureVec[i];
				mean[i] += value;
			}
							
			count ++;
		}
		for(int i=0; i<mean.length; i++){
			mean[i] /= count;
		}
		
		//std variance
		for(TQuery tQuery: this._QSessionList){
			int firstC = tQuery.getFirstClickPosition();
			TUrl tUrl = tQuery.getUrlList().get(firstC-1);
			
			featureVec = tUrl.getReleFeatures();
			
			for(int i=0; i<3; i++){
				value = Math.log(featureVec[i]);
				stdVar [i] += Math.pow((value-mean[i]), 2);									
			}
			
			for(int i=3; i<featureVec.length; i++){
				value = featureVec[i];
				stdVar [i] += Math.pow((value-mean[i]), 2);
			}
		}
		for(int i=0; i<stdVar.length; i++){
			stdVar [i] = Math.sqrt(stdVar[i]/(count-1));
		}
	}
	
	protected void getMarFeatureMeanStdVariance(double[] marMean, double[] marStdVar){
		double count=0, value;
		double[] marFeatureVec;
		
		//mean
		for(TQuery tQuery: this._QSessionList){
			int firstC = tQuery.getFirstClickPosition();
			for(int rank=firstC+1; rank<=tQuery.getUrlList().size(); rank++){
				TUrl tUrl = tQuery.getUrlList().get(rank-1);
				if(tUrl.getGTruthClick() > 0){
					marFeatureVec = tQuery.getMarFeature(rank);
					
					for(int i=0; i<marFeatureVec.length; i++){
						value = marFeatureVec[i];
						marMean[i] += value;
					}
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
				TUrl tUrl = tQuery.getUrlList().get(rank-1);
				if(tUrl.getGTruthClick() > 0){
					marFeatureVec = tQuery.getMarFeature(rank);
					
					for(int i=0; i<marFeatureVec.length; i++){
						value = marFeatureVec[i];
						marStdVar [i] += Math.pow((value-marMean[i]), 2);
					}
				}
			}
		}
		for(int i=0; i<marStdVar.length; i++){
			marStdVar [i] = Math.sqrt(marStdVar[i]/(count-1));
		}
	}
	
	protected void normalize(){
		//rele part
		double[] releMean = new double [IAccessor._releFeatureLength];
		double[] releStdVar = new double [releMean.length];
		
		getReleFeatureMeanStdVariance(releMean, releStdVar);
		
		for(TQuery tQuery: this._QSessionList){
			int firstC = tQuery.getFirstClickPosition();
			TUrl tUrl = tQuery.getUrlList().get(firstC-1);
			tUrl.releNormalize(releMean, releStdVar);
		}
		
		//mar part
		double[] marMean = new double [IAccessor._marFeatureLength];
		double[] marStdVar = new double [marMean.length];
		
		getMarFeatureMeanStdVariance(marMean, marStdVar);
		
		for(TQuery tQuery: this._QSessionList){			
			tQuery.marNormalize(marMean, marStdVar);
		}
	}
	
	///////
	//
	///////
	public static void main(String []args){
		//1
		MClickModel mClickModel = new MClickModel(0.75);
		mClickModel.train();
		//0-7
		//before normalizing -5092.38721036584
		System.out.println(mClickModel.getTestCorpusProb(true));
	}
}
