package org.archive.rms;

import java.util.ArrayList;

import optimizer.LBFGS;
import optimizer.LBFGS.ExceptionWithIflag;

import org.archive.rms.clickmodels.T_Evaluation;
import org.archive.rms.data.TQuery;
import org.archive.rms.data.TUrl;
import org.archive.rms.data.TUser;

public abstract class USMFrame implements T_Evaluation{
	//
	public static final double EPSILON = 0.1;
	
	//optimizer
	protected LBFGS _lbfgsOptimizer;
	
	protected double _testRatio;
	//i.e., top-trainNum instances
	protected int _trainNum;
	//i.e., later-testNum instances
	protected int _testNum;
	
	ArrayList<TQuery> _QSessionList;
	
	USMFrame(double testRatio, ArrayList<TQuery> QSessionList){
		this._testRatio = testRatio;
		this._QSessionList = QSessionList;
		
		this._testNum = (int)(this._QSessionList.size()*testRatio);
		this._trainNum = this._QSessionList.size()-this._testNum;
	}
	
	USMFrame(){
		
	}
	
	//////////
	//
	//////////
	/**
	 * initialize the parameters, i.e., the weights w.r.t. each feature.
	 * **/
	protected abstract void ini();
	protected abstract void iniWeightVector();
	/**
	 * refresh based on new weights, or preliminary call after initializing the weights
	 * e.g., for consistent refresh w.r.t. both gradient and objectiveFunction
	 * **/
	protected void refresh(){
		for(int i=0; i<this._trainNum; i++){
			TQuery tQuery = this._QSessionList.get(i);
			calQSessionSatPros(tQuery);
		}		
	}
	
	protected abstract void optimize(int maxIter) throws ExceptionWithIflag;
	
	/**
	 * get the objective function value (log based version)
	 * needs refresh
	 * 
	 * **/
	protected double calObjFunctionValue(){
		double objVal = 0.0;
		
		for(int i=0; i<this._trainNum; i++){
			TQuery tQuery = this._QSessionList.get(i);
			double satPro = tQuery.getQSessionPro();
			objVal += Math.log(satPro);
		}
		
		return objVal;		
	}
	/**
	 * perform training
	 * **/
	protected void train(){
		ini();
		
		int defaultMaxItr = 50;
		
		System.out.println("Parameters Before Optimizing:");
		outputParas();
		
		try {
			
			optimize(defaultMaxItr);

		} catch (Exception e) {
			// TODO: handle exception
			e.printStackTrace();
		}
		
		System.out.println("Parameters After Optimizing:");
		outputParas();
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
	
	public double getSessionProb(TQuery tQuery, boolean onlyClicks){
		tQuery.calQSessionPro();
		
		return tQuery.getQSessionPro();
	}
	
	public double getClickProb(TQuery tQuery, TUrl tUrl){
		return Double.NaN;
	}
	//////////
	//
	//////////
	protected abstract double calReleVal(TUrl tUrl);
	
	protected abstract void calGTruthBasedCumuVals(TQuery tQuery);
	
	/**
	 * 
	 * **/
	protected void calGTruthBasedSatPros(TQuery tQuery){
		calGTruthBasedCumuVals(tQuery);
		
		ArrayList<Double> gTruthBasedSatProList = new ArrayList<>();
		
		ArrayList<Boolean> gTruthClickSeq = tQuery.getGTruthClickSequence();
		ArrayList<Double> gTruthBasedCumuValList = tQuery.getCumuVals();
		
		for(int kRank=1; kRank<=gTruthClickSeq.size(); kRank++){
			if(gTruthClickSeq.get(kRank-1)){
				double cumuUtilityVal = gTruthBasedCumuValList.get(kRank-1);
				gTruthBasedSatProList.add(logistic(EPSILON+cumuUtilityVal));
			}else{
				gTruthBasedSatProList.add(Double.NaN);
			}
		}
		
		tQuery.setGTruthBasedSatPros(gTruthBasedSatProList);
	}
	/**
	 * this process includes all above calculations
	 * **/
	protected void calQSessionSatPros(TQuery tQuery) {
		calGTruthBasedSatPros(tQuery);
	}
	
	//////////
	//basic utilities
	//////////
	protected double dotProduct(double [] dVector_1, double [] dVector_2){
		double sum = 0.0;
		for(int i=0; i<dVector_1.length; i++){
			sum += dVector_1[i]*dVector_2[i];
		}
		return sum;
	}
	//
	protected static double logistic(double a){
		return 1.0 / (1.0+Math.exp(-a));
	}
	//
	protected abstract void outputParas();
}
