package org.archive.rms.advanced;

import java.io.BufferedWriter;
import java.util.ArrayList;
import java.util.Random;

import org.archive.access.feature.FRoot;
import org.archive.access.feature.IAccessor;
import org.archive.rms.data.TQuery;
import org.archive.rms.data.TQuery.MarStyle;
import org.archive.rms.data.TUrl;
import org.archive.util.io.IOText;

import cc.mallet.types.MatrixOps;
import optimizer.LBFGS;
import optimizer.LBFGS.ExceptionWithIflag;

/**
 * The basic structure required within the marginal utility based framework
 * 
 * Underlying association:
 * (1) Premise: for the input of "ArrayList<TUser> userList", all the required rele-features and mar-features are loaded 
 * 
 * Current assumption:
 * 1 non-clicked documents that locate below the clicked documents have zero marginal utility
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
	
	private static final FunctionType _funType = FunctionType.EXP;
		
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
	
	//? use exponential function or not? need comparison to be done
	 
	/**
	 * version-1
	 * differentiating the first clicked document 	{the feature vector for computing utility are the relevance features}
	 * and subsequent clicked documents 			{the feature vector for computing marginal utility are the relevance features plus marginal features}
	 * **/ 
	//the part of {IAccessor._releFeatureLength}							 	for the first clicked document
	//the part of {IAccessor._releFeatureLength +IAccessor.marFeatureLength}	for subsequent clicked documents
	private static int version_1_releLength = IAccessor._releFeatureLength;
	public static int  version_1_marLength = IAccessor._releFeatureLength +IAccessor._marFeatureLength+3;
	private static int  version_1_featureLength =  version_1_releLength +  version_1_marLength;
	private double [] mar_weights;		
	
//	//ArrayList<TUser> _userList;
//	
//	MClickModel(ArrayList<TUser> userList){
//		this._userList = userList;
//	}
	
	MClickModel(double testRatio, int maxQSessionSize, int minQFre){
		super(testRatio, maxQSessionSize, minQFre);
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
		iniFeatures();
		
		////2 normalizing features
		normalizeFeatures();

		////3
		iniWeightVector(true);
		//iniWeightVector(false);	
	}
	
	protected void iniFeatures(){
		
		for(TQuery tQuery: this._QSessionList){			
			//context information
			tQuery.calContextInfor();	
			
			tQuery.calReleFeature(key2ReleFeatureMap, true);				
		}
		
		for(int i=0; i<this._QSessionList.size(); i++){
			TQuery tQuery = this._QSessionList.get(i);
			
			String qKey = tQuery.getKey()+":"+tQuery.getQueryText();
			tQuery.setMarTensor(key2MarFeatureMap.get(qKey));
			
			//should be called ahead of tQuery.calMarFeatureList() since the context features will used subsequently						
			tQuery.calMarFeatureList(false, true);			
		}
	}
	
	@Override
	protected void iniWeightVector(boolean useCurrentOptimal){		
		mar_weights =new double[version_1_featureLength];
		
		if(useCurrentOptimal){
			double [] currentOptParas = loadCurrentOptimalParas();
			
			for(int i=0; i<currentOptParas.length; i++){
				mar_weights[i] = currentOptParas[i];
			}
			
		}else{
			double weightScale = _defaultWeightScale;		
			Random rand = new Random();
			for(int i=0; i<mar_weights.length; i++){
				mar_weights [i] = (2*rand.nextDouble()-1)%weightScale;
			}
		}		
	}
			
	@Override
	protected void optimize(int maxIter) throws ExceptionWithIflag{
		
		_lbfgsOptimizer = new LBFGS();
		
		int[] iprint = {-1, 0}, iflag = {0};
		
		//gradient w.r.t. the function
		double[] g = new double[mar_weights.length], diag = new double[mar_weights.length];

		int iter = 0;
		//objVal
		double f=0;	
				
		do {			
			
			if (iflag[0]==0 || iflag[0]==1){
				refresh();
				
				//function value based on the posterior graph inference! 
				f = calObjFunctionValue();
				
				if(f > USMFrame._MAX){
					f = USMFrame._MAX;
				}

				System.out.println("Iter-"+iter+":\tObjValue: "+f);
				
				calFunctionGradient(g);					
				
				MatrixOps.timesEquals(g, -1);
			}
			
			//if (iflag[0]==0 || iflag[0]==2){//if we want to estimate the diagonals by ourselves
			//	getDiagnoal(diag, iflag[0]==2);
			//}
			
			try{
				
				_lbfgsOptimizer.lbfgs(mar_weights.length, 5, mar_weights, -f, g, false, diag, iprint, 1e-3, 1e-3, iflag);

			} catch (ExceptionWithIflag ex){
				System.err.println("[Warning]M-step cannot proceed!");
			}	
			
			//outputParas();
			//System.out.println();
			
		}while(iflag[0]>0 && ++iter<maxIter);
	}
	/**
	 * get the gradient for optimization
	 * needs refresh
	 * **/
	private void calFunctionGradient(double[] g){		
		////rele part
		double [] total_rele_parGradient = new double[version_1_releLength];		
		for(int k=0; k<this._trainCnt; k++){
			TQuery tQuery = this._QSessionList.get(k);
			
			double [] rele_parGradient = tQuery.calRelePartialGradient(_funType);
			for(int i=0; i<version_1_releLength; i++){
				//!!!
				if(Double.isNaN(rele_parGradient[i])){
					System.out.println("NaN rele gradient:\t");
					System.exit(0);
				}
				
				total_rele_parGradient[i] += rele_parGradient[i];
			}
		}		
		
		for(int i=0; i<version_1_releLength; i++){
			g[i] = total_rele_parGradient[i];
		}
		
		////mar part
		double [] total_mar_parGradient = new double[version_1_marLength];		
		for(int k=0; k<this._trainCnt; k++){
			TQuery tQuery = this._QSessionList.get(k);
			
			double [] mar_parGradient = tQuery.calMarPartialGradient(_funType);			
			for(int j=0; j<version_1_marLength; j++){
				//!!!
				if(Double.isNaN(mar_parGradient[j])){
					System.out.println("NaN  mar gradient:\t");
					System.exit(0);
				}
				
				total_mar_parGradient[j] += mar_parGradient[j];
			}
		}
				
		for(int j=0; j<version_1_marLength; j++){
			g[version_1_releLength+j] = total_mar_parGradient[j];
		}		
	}

	public double getSessionProb(TQuery tQuery, boolean onlyClicks){
		tQuery.calMarFeatureList(false, true);
		
		tQuery.calQSessionPro();
		
		return tQuery.getQSessionPro();
	}
	
	public double getTestCorpusProb(boolean onlyClick, boolean uniformCmp) {
				
		if(!onlyClick){
			System.err.println("USM-similar models only use clicks!");
		}
		//
		for(int k=this._trainCnt; k<this._QSessionList.size(); k++){
			TQuery tQuery = this._QSessionList.get(k);
			calQSessionSatPros(tQuery);
			
			tQuery.calQSessionPro();
		}
		
		double corpusLikelihood = 0.0;
		
		double [] testArray = getTestArray();
		
		int count = 0;
		for(int k=this._trainCnt; k<this._QSessionList.size(); k++){
			TQuery tQuery = this._QSessionList.get(k);
			double sessionPro = tQuery.getQSessionPro();
			
			if(Double.isNaN(sessionPro)){
				count++;
			}
			
			double logVal;
			if(0 == sessionPro){
				logVal = USMFrame._MIN;
			}else{
				logVal = Math.log(sessionPro);
			}
			
			testArray[tQuery.getClickCount()-2] += logVal;
			
			corpusLikelihood += logVal;
		}
		
		//
		updateOptimalParas(true, corpusLikelihood);
		
		System.out.println(count+" of "+_testCnt);
		System.out.println(("Direct Product:\t"
					+_testCnt+"*"+USMFrame._MIN
					+"="+_testCnt*USMFrame._MIN));
		System.out.println(("Comparison w.r.t. avgSessionPro{0.1} :\t"
				+_testCnt+"*"+Math.log(0.1)
				+"="+_testCnt*Math.log(0.1)));
		
		outputTestArray(testArray);
		
		//the higher the better
		return corpusLikelihood;	
	}
	
	public void train(int searchK){
		
		double minObj = Double.MIN_VALUE;
		
		for(int k=0; k<searchK; k++){
			ini();
			
			int defaultMaxItr = 100;
			
			//System.out.println("Parameters Before Optimizing:");
			//outputParas();
			
			try {
				
				optimize(defaultMaxItr);

			} catch (Exception e) {
				// TODO: handle exception
				e.printStackTrace();
			}
			
			//System.out.println("Parameters After Optimizing:");
			//outputParas();
			
			double obj = calObjFunctionValue();
			if(obj > minObj){
				minObj = obj;
				bufferParas();
			}			
		}		
	}
	
	
	////////
	//
	////////
	/**
	 * Exponential function based relevance function, without considering the effect of previously clicked documents
	 * **/
	@Override
	protected double calReleVal(TUrl tUrl){
		double [] releParas = new double [version_1_releLength];
		
		for(int i=0; i<version_1_releLength; i++){
			releParas[i] = mar_weights[i];
		}
	
		double [] releFeatureVec = tUrl.getReleFeatures();
		
		//double dotProVal = dotProduct(releFeatureVec, releParas);
		//double releVal = Math.exp(dotProVal);
		
		double val = calFunctionVal(releFeatureVec, releParas, _funType);
		
		tUrl.setReleVal(val);		
		return val;
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
		
		////releVal w.r.t. firstC
		for(TUrl tUrl: tQuery.getUrlList()){
			if(tUrl.getGTruthClick() > 0){
				calReleVal(tUrl);
			}			
		}
		
		ArrayList<Double> gTruthBasedMarValList = new ArrayList<>();		
		ArrayList<Boolean> gTruthClickSeq = tQuery.getGTruthClickSequence();		
		//documents above the first clicked document are assumed to be irrelevant
		for(int iRank=1; iRank<firstC; iRank++){			
			gTruthBasedMarValList.add(0.0);
		}
		
		//firstC utility=marginal utility
		gTruthBasedMarValList.add(tQuery.getUrlList().get(firstC-1).getReleValue());
		
		double []marParas = new double [version_1_marLength];
		
		for(int i=0; i<version_1_marLength; i++){
			marParas[i] = mar_weights[version_1_releLength+i];
		}
		
		for(int kRank=firstC+1; kRank<=gTruthClickSeq.size(); kRank++){
			if(gTruthClickSeq.get(kRank-1)){				
				double [] allFeature = tQuery.getMarFeature_Join(kRank);
								
				//double dotProVal = dotProduct(allFeature, marParas);
				//double marVal = Math.exp(dotProVal);
				
				double marVal = calFunctionVal(allFeature, marParas, _funType);
					
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
		System.out.print("Paras::: ");
		for(double w: mar_weights){
			System.out.print(w+"\t");
		}
		System.out.println();
	}
	
	protected void bufferParas(){
		String targetFile = FRoot._bufferParaDir+"Parameter_Mar.txt";
		try {
			BufferedWriter writer = IOText.getBufferedWriter_UTF8(targetFile);
			
			for(double w: mar_weights){
				writer.write(Double.toString(w));
				writer.newLine();
			}
			
			writer.flush();
			writer.close();			
		} catch (Exception e) {
			// TODO: handle exception
			e.printStackTrace();
		}		
	}
	protected double [] loadCurrentOptimalParas(){
		String optimalParaFile = FRoot._bufferParaDir+"Parameter_Mar_Optimal.txt";
		ArrayList<String> lineList = IOText.getLinesAsAList_UTF8(optimalParaFile);
		
		double [] currentOptParas = new double [lineList.size()-1];
		for(int i=0; i<currentOptParas.length; i++){
			currentOptParas[i] = Double.parseDouble(lineList.get(i+1));
		}
		
		return currentOptParas;
	}
	
	protected void updateOptimalParas(boolean updateOptimalParas, double value){
		String optimalParaFile = FRoot._bufferParaDir+"Parameter_Mar_Optimal.txt";
		ArrayList<String> lineList = IOText.getLinesAsAList_UTF8(optimalParaFile);
		
		double oldOptimalVale = Double.parseDouble(lineList.get(0));
		
		if(value > oldOptimalVale){
			try {
				BufferedWriter writer = IOText.getBufferedWriter_UTF8(optimalParaFile);
				
				writer.write(Double.toString(value));
				writer.newLine();
				
				for(double w: mar_weights){
					writer.write(Double.toString(w));
					writer.newLine();
				}
				
				writer.flush();
				writer.close();			
			} catch (Exception e) {
				// TODO: handle exception
				e.printStackTrace();
			}	
		}		
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
				//value = Math.log(featureVec[i]);
				value = featureVec[i];
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
				//value = Math.log(featureVec[i]);
				value = featureVec[i];
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
					marFeatureVec = tQuery.getMarFeature_Join(rank);
					
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
					marFeatureVec = tQuery.getMarFeature_Join(rank);
					
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
	
	protected void normalizeFeatures(){
		//rele part
		double[] releMean = new double [version_1_releLength];
		double[] releStdVar = new double [releMean.length];
		
		getReleFeatureMeanStdVariance(releMean, releStdVar);
		
		for(TQuery tQuery: this._QSessionList){
			int firstC = tQuery.getFirstClickPosition();
			TUrl tUrl = tQuery.getUrlList().get(firstC-1);
			tUrl.releNormalize(releMean, releStdVar);
		}
		
		//mar part
		double[] marMean = new double [version_1_marLength];
		double[] marStdVar = new double [marMean.length];
		
		getMarFeatureMeanStdVariance(marMean, marStdVar);
		
		for(TQuery tQuery: this._QSessionList){			
			tQuery.marNormalize_Total(marMean, marStdVar);
		}
	}
	
	public double getTestCorpusAvgPerplexity(boolean uniformCmp){
		return Double.NaN;
	}
	
	///////
	//
	///////
	public static void main(String []args){
		//1
		int maxQSessionSize = 10;
		int minQFre = 1;
		MClickModel mClickModel = new MClickModel(0.25, maxQSessionSize, minQFre);
		mClickModel.train();
		//0-7
		//before normalizing -5092.38721036584
		System.out.println(mClickModel.getTestCorpusProb(false, true));
	}
}
