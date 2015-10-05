package org.archive.rms.advanced;

import java.util.ArrayList;

import optimizer.LBFGS;
import optimizer.LBFGS.ExceptionWithIflag;

import org.archive.rms.clickmodels.T_Evaluation;
import org.archive.rms.data.TQuery;
import org.archive.rms.data.TUrl;
import org.archive.rms.data.TUser;

public abstract class USMFrame extends MAnalyzer implements T_Evaluation{
	//
	public static final double EPSILON = 0.1;
	protected static double _defaultWeightScale = 20;
	
	////
	public static final boolean _acceptError_NaN_Feature = true;
	public static final boolean _acceptError_Opt_NaNVal = true;
	public static final boolean _acceptError_Opt_Infinity = true;
	////bounded integer max & min
	public static final double _MIN = -100000;
	public static final double _MAX = -_MIN;
	////bounded probability min & max
	public static final double _MIN_Pro = 0.00001;
	public static final double _MAX_Pro = 1-_MIN_Pro;	
	
	//optimizer
	protected LBFGS _lbfgsOptimizer;
	
	//protected double _testRatio;
	//i.e., top-trainNum instances
	//protected int _trainNum;
	//i.e., later-testNum instances
	//protected int _testNum;
	
	//ArrayList<TQuery> _QSessionList;
	
	USMFrame(double testRatio){
		super(testRatio, true);
		//this._testRatio = testRatio;
		//this._QSessionList = QSessionList;
		
		//this._testNum = (int)(this._QSessionList.size()*testRatio);
		//this._trainNum = this._QSessionList.size()-this._testNum;
	}
	
	
	//////////
	//
	//////////
	/**
	 * initialize the parameters, i.e., the weights w.r.t. each feature.
	 * **/
	protected abstract void ini();
	protected abstract void iniFeatures();
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
			
			tQuery.calQSessionPro();	
			
			double satPro = tQuery.getQSessionPro();
			
			double logVal;
			if(0 == satPro){
				logVal = this._MIN;
			}else if (Double.isNaN(satPro)) {
				System.out.println("objective function satPro:\t"+satPro);		
				logVal = Double.NaN;
				System.exit(0);
			}else{
				logVal = Math.log(satPro);
			}
			
			objVal += logVal;
		}
		
		return objVal;		
	}
	/**
	 * perform training
	 * **/
	public void train(){
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
		
		for(int k=1; k<=gTruthClickSeq.size(); k++){
			if(gTruthClickSeq.get(k-1) && Double.isNaN(gTruthBasedSatProList.get(k-1))){
				System.out.println(gTruthClickSeq);
				System.out.println(tQuery._gTruthBasedMarValList);
				System.out.println(gTruthBasedCumuValList);
				System.out.println(gTruthBasedSatProList);
				System.err.println("calGTruthBasedSatPros:\t"+k);
				System.exit(0);
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
	////////
	//Feature pre-process
	////////
	//estimating mean and standard deviation
	protected abstract void getReleFeatureMeanStdVariance(double[] mean, double[] stdVar);
	//
	protected abstract void normalizeFeatures();
	//
	public static boolean isNaNFeature(double val){
		return Double.isNaN(val);
	}
	public static boolean acceptNaNFeature(){
		return _acceptError_NaN_Feature;
	}
	public static boolean isNaNOptVal(double optVal){
		return Double.isNaN(optVal);
	}
	public static boolean acceptNaNOptVal(){
		return _acceptError_Opt_NaNVal;
	}
	public static boolean isInfinityOptVal(double optVal){
		return Double.isInfinite(optVal);
	}
	public static boolean acceptInfinityOptVal(){
		return _acceptError_Opt_Infinity;
	}
	
	
}
