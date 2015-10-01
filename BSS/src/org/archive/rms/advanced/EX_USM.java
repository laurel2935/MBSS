package org.archive.rms.advanced;

import java.util.ArrayList;
import java.util.Random;

import optimizer.LBFGS;
import optimizer.LBFGS.ExceptionWithIflag;

import org.archive.access.feature.IAccessor;
import org.archive.rms.clickmodels.T_Evaluation;
import org.archive.rms.data.TQuery;
import org.archive.rms.data.TUrl;

import cc.mallet.types.MatrixOps;

/**
 * Extended version of USM click model, i.e., the utility of a document is expressed with the logistic regression model, rather than the original {query-specific Normal distribution with priors}
 * "Dupret, Georges, and Ciya Liao. "A model to estimate intrinsic document relevance from the clickthrough logs of a web search engine." WSDM. ACM, 2010."
 * **/

public class EX_USM extends USMFrame implements T_Evaluation{
	
	private double [] _rele_context_weights;
	
	//optimizer
	protected LBFGS _lbfgsOptimizer;
		
	EX_USM(double testRatio){
		super(testRatio);
	}
		
	
	/**
	 * initialize the parameters, i.e., the weights w.r.t. each feature.
	 * **/
	@Override
	protected void ini(){
		//1
		for(TQuery tQuery: this._QSessionList){		
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
		//2
		iniWeightVector();		
	}
	
	@Override
	protected void iniWeightVector(){
		
		_rele_context_weights =new double[IAccessor._releFeatureLength];
		
		double defaultScale = 50;
		
		Random rand = new Random();
		for(int i=0; i<_rele_context_weights.length; i++){
			_rele_context_weights [i] = (2*rand.nextDouble()-1)/defaultScale;
		}
	}
	
	protected void optimize(int maxIter) throws ExceptionWithIflag{
		_lbfgsOptimizer = new LBFGS();
		
		int[] iprint = {-1, 0}, iflag = {0};
		
		//gradient w.r.t. the function
		double[] g = new double[_rele_context_weights.length], diag = new double[_rele_context_weights.length];

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
				
				_lbfgsOptimizer.lbfgs(_rele_context_weights.length, 5, _rele_context_weights, -f, g, false, diag, iprint, 1e-3, 1e-3, iflag);

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
			
			double [] rele_parGradient = tQuery.calRelePartialGradient_NoMarginal();
			for(int i=0; i<IAccessor._releFeatureLength; i++){
				total_rele_parGradient[i] += rele_parGradient[i];
			}
		}		
		
		for(int i=0; i<IAccessor._releFeatureLength; i++){
			g[i] = total_rele_parGradient[i];
		}	
	}
	
	public double getSessionProb(TQuery tQuery, boolean onlyClicks){
		tQuery.calQSessionPro();
		
		return tQuery.getQSessionPro();
	}
	
	public double getTestCorpusProb(boolean onlyClick) {
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
			releParas[i] = _rele_context_weights[i];
		}
		
		double [] releFreatureVector = tUrl.getReleFeatures();
		double dotProVal = dotProduct(releFreatureVector, releParas);
		double releVal = Math.exp(dotProVal);
		
		tUrl.setReleVal(releVal);	
		
		return releVal;
	}
	
	private void calReleVals(TQuery tQuery){		
		ArrayList<Double> gTruthBasedReleValList = new ArrayList<>();
		
		double releVal;		
		for(TUrl tUrl: tQuery.getUrlList()){
			releVal = calReleVal(tUrl);
			gTruthBasedReleValList.add(releVal);
		}	
		
		tQuery.setGTruthBasedReleValues(gTruthBasedReleValList);
	}
	
	@Override
	protected void calGTruthBasedCumuVals(TQuery tQuery){
		calReleVals(tQuery);
		
		ArrayList<Double> gTruthBasedCumuValList = new ArrayList<>();
		
		ArrayList<Boolean> gTruthClickSeq = tQuery.getGTruthClickSequence();
		for(int iRank=1; iRank<=gTruthClickSeq.size(); iRank++){
			double cumuUtilityVal = tQuery.calCumulativeUtility(iRank, false);
			gTruthBasedCumuValList.add(cumuUtilityVal);
		}
		
		tQuery.setCumuVals(gTruthBasedCumuValList);
	}
	
	protected void outputParas(){
		for(double w: _rele_context_weights){
			System.out.print(w+"\t");
		}
		System.out.println();
	}
	
	///////
	//
	///////
	public static void main(String []args){
		//1
		EX_USM ex_USM = new EX_USM(0.75);
		ex_USM.train();
		System.out.println(ex_USM.getTestCorpusProb(true));
	}
}