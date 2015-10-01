package org.archive.rms;

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
	
	MClickModel(double testRatio, ArrayList<TQuery> QSessionList){
		super(testRatio, QSessionList);
	}
	//////////
	//Part: Optimization
	//////////	
	/**
	 * initialize the parameters, i.e., the weights w.r.t. each feature.
	 * **/
	@Override
	protected void ini(){
		//1
		for(int i=0; i<this._trainNum; i++){
			TQuery tQuery = this._QSessionList.get(i);
			//should be called ahead of tQuery.calMarFeatureList() since the context features will used subsequently
			tQuery.calContextInfor();			
			tQuery.calMarFeatureList();
		}

		//2
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
	////
	
	
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
		
		double [] releFreatureVec = new double [IAccessor._releFeatureLength];
		
		double [] partialReleFreatureVector = tUrl.getReleFeatures();
		int k=0;
		for(; k<partialReleFreatureVector.length; k++){
			releFreatureVec[k] = partialReleFreatureVector[k];
		}
		//context information
		//rankPosition
		int ctxt_RPos = tUrl.getRankPosition();
		releFreatureVec[k++] = (double)ctxt_RPos;
		//number of prior clicks
		int ctxt_PriorClicks = tUrl.getPriorClicks();
		releFreatureVec[k++] = (double)ctxt_PriorClicks;
		//distance to prior click
		double ctxt_DisToLastClick = tUrl.getDisToLastClick();
		releFreatureVec[k++] = ctxt_DisToLastClick;
		
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
	
}
