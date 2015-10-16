package org.archive.rms.advanced;

import java.io.BufferedWriter;
import java.util.ArrayList;
import java.util.Random;

import optimizer.LBFGS;
import optimizer.LBFGS.ExceptionWithIflag;

import org.archive.access.feature.FRoot;
import org.archive.access.feature.IAccessor;
import org.archive.rms.clickmodels.T_Evaluation;
import org.archive.rms.data.TQuery;
import org.archive.rms.data.TUrl;
import org.archive.util.io.IOText;

import cc.mallet.types.MatrixOps;

/**
 * Extended version of USM click model, i.e., the utility of a document is expressed with the logistic regression model, rather than the original {query-specific Normal distribution with priors}
 * "Dupret, Georges, and Ciya Liao. "A model to estimate intrinsic document relevance from the clickthrough logs of a web search engine." WSDM. ACM, 2010."
 * **/

public class EX_USM extends USMFrame implements T_Evaluation{
	
	private static int  _releLength = IAccessor._releFeatureLength;
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
		//1 prepare features
		iniFeatures();
		
		//normalize features
		//normalizeFeatures();
		
		//2
		iniWeightVector(true);		
	}
	
	protected void iniFeatures(){
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
	}
	
	@Override
	protected void iniWeightVector(boolean useCurrentOptimal){
		
		_rele_context_weights =new double[_releLength];
		
		if(useCurrentOptimal){
			double [] currentOptParas = loadCurrentOptimalParas();
			
			for(int i=0; i<currentOptParas.length; i++){
				_rele_context_weights[i] = currentOptParas[i];
			}
		}else{
			double weightScale = _defaultWeightScale;
			
			Random rand = new Random();
			for(int i=0; i<_rele_context_weights.length; i++){
				_rele_context_weights [i] = (2*rand.nextDouble()-1)/weightScale;
			}
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
				
				System.out.println("Iter-"+iter+":\t"+f);
				
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
			
		}while(iflag[0]>0 && ++iter<maxIter);
	}
	
	/**
	 * get the gradient for optimization
	 * needs refresh
	 * **/
	private void calFunctionGradient(double[] g){		
		//rele part
		double [] total_rele_parGradient = new double[_releLength];		
		for(int k=0; k<this._trainNum; k++){
			TQuery tQuery = this._QSessionList.get(k);
			
			double [] rele_parGradient = tQuery.calRelePartialGradient_NoMarginal();			
			for(double releF: rele_parGradient){
				if(Double.isNaN(releF)){
					//!!!
					System.out.println("NaN rele gradient error!");
					System.out.println(releF);
					System.exit(0);
				}
			}			
			
			for(int i=0; i<_releLength; i++){
				total_rele_parGradient[i] += rele_parGradient[i];
			}
		}		
		
		for(int i=0; i<_releLength; i++){
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
		
		for(int k=this._trainNum; k<this._QSessionList.size(); k++){
			TQuery tQuery = this._QSessionList.get(k);
			calQSessionSatPros(tQuery);
			
			tQuery.calQSessionPro();
		}
		
		double corpusLikelihood = 0.0;
		
		double [] testArray = getTestArray();
		
		int count = 0;
		for(int k=this._trainNum; k<this._QSessionList.size(); k++){
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
		
		updateOptimalParas(true, corpusLikelihood);
		
		outputTestArray(testArray);
		
		System.out.println(count+" of "+_testNum);
		System.out.println(("Direct Product:\t"
					+_testNum+"*"+USMFrame._MIN
					+"="+_testNum*USMFrame._MIN));
		System.out.println(("Comparison w.r.t. avgSessionPro{0.1} :\t"
				+_testNum+"*"+Math.log(0.1)
				+"="+_testNum*Math.log(0.1)));
		
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
		double [] releParas = new double [_releLength];
		for(int i=0; i<_releLength; i++){
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
	
	protected void bufferParas(){
		String targetFile = FRoot._bufferParaDir+"Parameter_OnlyRele.txt";
		try {
			BufferedWriter writer = IOText.getBufferedWriter_UTF8(targetFile);
			
			for(double w: _rele_context_weights){
				writer.write(w+"\t");
				writer.newLine();
			}
			
			writer.flush();
			writer.close();			
		} catch (Exception e) {
			// TODO: handle exception
			e.printStackTrace();
		}		
	}
	
	protected void updateOptimalParas(boolean updateOptimalParas, double value){
		String optimalParaFile = FRoot._bufferParaDir+"Parameter_OnlyRele_Optimal.txt";
		ArrayList<String> lineList = IOText.getLinesAsAList_UTF8(optimalParaFile);
		
		double oldOptimalVale = Double.parseDouble(lineList.get(0));
		
		if(value > oldOptimalVale){
			try {
				BufferedWriter writer = IOText.getBufferedWriter_UTF8(optimalParaFile);
				
				writer.write(Double.toString(value));
				writer.newLine();
				
				for(double w: _rele_context_weights){
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
	
	protected double [] loadCurrentOptimalParas(){
		String optimalParaFile = FRoot._bufferParaDir+"Parameter_OnlyRele_Optimal.txt";
		ArrayList<String> lineList = IOText.getLinesAsAList_UTF8(optimalParaFile);
		
		double [] currentOptParas = new double [lineList.size()-1];
		for(int i=0; i<currentOptParas.length; i++){
			currentOptParas[i] = Double.parseDouble(lineList.get(i+1));
		}
		
		return currentOptParas;
	}
		
	protected void getReleFeatureMeanStdVariance(double[] mean, double[] stdVar){
		double count=0, value;
		double[] featureVec;
		
		//mean
		for(TQuery tQuery: this._QSessionList){
			for(TUrl tUrl: tQuery.getUrlList()){
				if(tUrl.getGTruthClick() > 0){
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
			}
		}
		for(int i=0; i<mean.length; i++){
			mean[i] /= count;
		}
		
		//std variance
		for(TQuery tQuery: this._QSessionList){
			for(TUrl tUrl: tQuery.getUrlList()){
				if(tUrl.getGTruthClick() > 0){
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
			}
		}
		for(int i=0; i<stdVar.length; i++){
			stdVar [i] = Math.sqrt(stdVar[i]/(count-1));
		}
	}
	
	protected void normalizeFeatures(){
		double[] mean = new double [_releLength];
		double[] stdVar = new double [mean.length];
		
		getReleFeatureMeanStdVariance(mean, stdVar);
		
		for(TQuery tQuery: this._QSessionList){					
			for(TUrl tUrl: tQuery.getUrlList()){
				if(tUrl.getGTruthClick() > 0){
					tUrl.releNormalize(mean, stdVar);
				}				
			}
		}		
	}
	///////
	//
	///////
	public static void main(String []args){
		//1
		EX_USM ex_USM = new EX_USM(0.25);
		ex_USM.train();
		//0-15
		//before normalizing features; -5092.389142761225
		System.out.println(ex_USM.getTestCorpusProb(true));
	}
}
