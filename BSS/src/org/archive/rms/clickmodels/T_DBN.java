package org.archive.rms.clickmodels;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Random;

import org.archive.access.feature.FRoot;
import org.archive.rms.advanced.USMFrame;
import org.archive.rms.advanced.USMFrame.FunctionType;
import org.archive.rms.data.TQuery;
import org.archive.rms.data.TUrl;

/**
 * 
 * Implementation of User Browsing Modeling proposed in 
 * "Chapelle, Olivier, and Ya Zhang. "A dynamic bayesian network click model for web search ranking." WWW. ACM, 2009."
 */

public class T_DBN extends FeatureModel implements T_Evaluation {

	class _param{
		//attractiveness
		double m_a;
		//satisfaction
		double m_s;
		
		double[] m_ss_a;
		double[] m_ss_s;
		
		_param(){
			m_a = (1+m_rand.nextDouble())/50;
			m_s = (1+m_rand.nextDouble())/50;
			
			m_ss_a = new double[2];
			m_ss_s = new double[2];
		}
	}
	
	//regarding the parameters (alpha,beta) for beta distribution 
	double m_gamma, m_alpha_a, m_beta_a, m_alpha_s, m_beta_s;
	
	HashMap<String, _param> m_urlTable;//query-document specific parameters
	Random m_rand;
	private static FunctionType _fType = FunctionType.LINEAR;
	
	public T_DBN(int maxQSessionSize, Mode mode, boolean useFeature, int minQFre, double testRatio,
			double gamma, double alpha_a, double beta_a, double alpha_s, double beta_s){
		//
		//super(minQFre, testRatio, useFeature, maxQSessionSize);
		super(minQFre, mode, useFeature, testRatio, maxQSessionSize);
		
		m_gamma = gamma;		
		//beta distribution w.r.t. event:A
		m_alpha_a = alpha_a;
		m_beta_a = beta_a;		
		//beta distribution w.r.t. event:S
		m_alpha_s = alpha_s;
		m_beta_s = beta_s;
		//
		m_rand = new Random();
		m_urlTable = new HashMap<String, _param>();
	}
	
	_param lookupURL(TQuery tQuery, TUrl tUrl, boolean add4miss, boolean testPhase){
		//String key = query + "@" + URL;
		String key = getKey(tQuery, tUrl);
		
		if (m_urlTable.containsKey(key))
			return m_urlTable.get(key);
		else if (add4miss){
			_param p = new _param();
			m_urlTable.put(key, p);
			return p;
		}else if(testPhase){
			if(!_mode.equals(Mode.Original)){
				_param p = new _param();
				
				if(_mode.equals(Mode.NaiveRele)){
					double alphaV = tUrl.calRelePro(_naiveReleWeights);
					p.m_a = alphaV;
					m_urlTable.put(key, p);
					return p;
				}else{
					System.out.println("Unimplemented error w.r.t. mar!");
					System.exit(0);
					return null;
				}
			}else{
				System.out.println("Unconsistent mode for test error!");
				System.exit(0);
				return null;
			}
		}else{
			System.out.println("Unseen query-url pair search error!");
			return null;
		}			
	}
	
	public void EM(int iter, double tol){
		//treiler for forward and backward
		double[][] alpha = new double[11][2], beta = new double[11][2];
		
		alpha[0][0] = 0.0; alpha[0][1] = 1.0; 
		
		int uSize, c, step=0;
		_param p;
		double diff = 1.0;
		
		while(step++<iter && diff>tol){
			//E-step
			//training parts
			for(int qNum=1; qNum<=this._trainCnt; qNum++){
				TQuery tQuery = this._QSessionList.get(qNum-1);
				ArrayList<TUrl> urlList = tQuery.getUrlList();
				
				uSize = urlList.size();
				
				//prior beta distribution with parameters (1,1)
				beta[uSize][0] = 1.0; beta[uSize][1] = 1.0;//in case we have varying length of sessions
				
				////alpha
				for(int i=0; i<uSize; i++){//alpha update
					//c = query.m_urls.get(i).m_click>0?1:0;
					TUrl url = urlList.get(i);
					c = url.getGTruthClick()>0?1:0;
					//p = lookupURL(query.m_query, query.m_urls.get(i).m_URL, true);
					p = lookupURL(tQuery, url, true, false);
					
					if (c==0){
						//corresponds to equation page_10 in the paper appendix: e.g., one can easily derive the recursion formula:
						//c=0, e=0, thus (1-p.m_a):c_(i+1)=0 (1-m_gamma):e_(i+1)=0
						//In particular, the 1st part: e'=0, the 2nd part: e'=1
						alpha[i+1][0] = alpha[i][0] + alpha[i][1]*(1-p.m_a)*(1-m_gamma);
						//c=0, e=1, thus (1-p.m_a):c_(i+1)=0 m_gamma:e_(i+1)=1
						//In particular, the 1st part: e'=0 (thus zero probability), the 2nd part: e'=1
						alpha[i+1][1] = alpha[i][1]*(1-p.m_a)*m_gamma;
					} else {
						alpha[i+1][0] = alpha[i][1]*(p.m_a*(1-m_gamma+p.m_s*m_gamma));
						alpha[i+1][1] = alpha[i][1]*p.m_a*m_gamma*(1-p.m_s);
					}
				}
				
				////beta
				for(int i=uSize; i>0; i--){//beta update
					TUrl url = urlList.get(i-1);
					//c = query.m_urls.get(i-1).m_click>0?1:0;
					c = url.getGTruthClick()>0?1:0;
					//p = lookupURL(query.m_query, query.m_urls.get(i-1).m_URL, true);
					p = lookupURL(tQuery, url, true, false);
					
					beta[i-1][0] = beta[i][0]*(1-c);
					if (c==0)
						beta[i-1][1] = beta[i][0]*((1-p.m_a)*(1-m_gamma)) + beta[i][1]*(1-p.m_a)*m_gamma;
					else
						beta[i-1][1] = beta[i][0]*(p.m_a*(1-m_gamma+p.m_s*m_gamma)) + beta[i][1]*p.m_a*m_gamma*(1-p.m_s);
				}
				
				////sufficient statistics
				for(int i=0; i<uSize; i++){
					//URL url = query.m_urls.get(i);
					TUrl url = urlList.get(i);
					//c = url.m_click>0?1:0;
					c = url.getGTruthClick()>0?1:0;
					//p = lookupURL(query.m_query, url.m_URL, true);
					p = lookupURL(tQuery, url, true, false);
					
					p.m_ss_a[1] += 1;	
					
					if (c>0){
						p.m_ss_a[0] += 1;
						//
						p.m_ss_s[0] += alpha[i+1][0] * beta[i+1][0] / beta[0][1] / ((1-m_gamma)/p.m_s + m_gamma);
						p.m_ss_s[1] += 1; 
					} else {
						//
						p.m_ss_a[0] += p.m_a * alpha[i][0] * beta[i][0] / beta[0][1];
					}
				}
			}
			
			////M-step
			/**
			 * (1) w.r.t. the mode of a Beta distributed random variable is the most likely value of the distribution (i.e., the peak in the pdf)
			 *     it is given as \alpha-1/(\alpha+\beta-2)
			 * (2) for sufficient statistics, m_ss_a[1]: times of being "0"-event, p.m_ss_a[0]: times of being "1"-event, the same goes for m_ss_s
			 * **/
			double old;
			diff = 0;
			for(_param para: m_urlTable.values()){
				old = para.m_a;
				
				para.m_a = (m_alpha_a-1+para.m_ss_a[0]) / (m_alpha_a+m_beta_a-2+para.m_ss_a[1]);
				
				diff += (old-para.m_a)*(old-para.m_a);
				
				old = para.m_s;
				
				para.m_s = (m_alpha_s-1+para.m_ss_s[0]) / (m_alpha_s+m_beta_s-2+para.m_ss_s[1]);
				if (para.m_s==0)
					para.m_s = 1e-10;
				
				diff += (old-para.m_s)*(old-para.m_s);
				
				//clear the ss
				para.m_ss_a[0] = 0; para.m_ss_a[1] = 0;
				para.m_ss_s[0] = 0; para.m_ss_s[1] = 0;
			}
			
			////
			///*
		    if(!_mode.equals(Mode.Original)){
		    	try {
		    		optimize(40);
				} catch (Exception e) {
					e.printStackTrace();
					//System.exit(0);
				}
		    	//
		    	updateAlpha();
		    }
		    //*/
			
		    //parameter size
			diff /= m_urlTable.size();
			
			System.out.println("[Info]EM step " + step + ", diff:" + diff);
		}
		
		System.out.println("[Info]Processed " + m_urlTable.size() + " (q,u) pairs...");
	}
		
	public void EM_MarRele(int iter, double tol){
		
	}
	
	@Override
	public double getClickProb(TQuery tQuery, TUrl tUrl) {
		/*
		_param para = lookupURL(tQuery, tUrl, false, true);
		if (para!=null)//existing one
			return 2 * para.m_a * para.m_s;
		else//totally a new one
			return 2 * (m_alpha_a-1)/(m_alpha_a+m_beta_a-2) * (m_alpha_s-1)/(m_alpha_s+m_beta_s-2) + m_rand.nextDouble()/10;
		*/
		return Double.NaN;
	}
	
	public double getClickProbAtK(TQuery tQuery, int rankPosition) {		
		if(1 == rankPosition){
			TUrl tUrl = tQuery.getUrlList().get(rankPosition-1);
			_param param = lookupURL(tQuery, tUrl, false, true);
			return param.m_a;
		}else {
			double clickPro = 1.0;
			
			for(int r=1; r<=rankPosition-1; r++){				
				TUrl tUrl = tQuery.getUrlList().get(r-1);
				_param param = lookupURL(tQuery, tUrl, false, true);
				if(tUrl.getGTruthClick() > 0){
					clickPro *= (param.m_a*(1-param.m_s));
				}else{
					clickPro *= (1-param.m_a);
				}
				
				clickPro *= m_gamma;
			}
			
			TUrl tUrl = tQuery.getUrlList().get(rankPosition-1);
			_param param = lookupURL(tQuery, tUrl, false, true);
			return clickPro*=param.m_a;
		}		
	}
		
	public double getSessionProb(TQuery tQuery, boolean onlyClicks){
		double sessionProb = 1.0;
		
		//starts from the last click
		ArrayList<TUrl> urlList = tQuery.getUrlList();		
		int rLast = urlList.size();
		TUrl lastUrl = urlList.get(rLast-1);
		_param lastParam = lookupURL(tQuery, lastUrl, false, true);
		if(lastUrl.getGTruthClick()>0){
			//satisfied
			sessionProb *= (lastParam.m_a*lastParam.m_s);
		}else{
			//abandonment
			sessionProb *= ((1-lastParam.m_a)*(1-m_gamma));
		}
		////part-2				
		for(int r=rLast-1; r>=1; r--){
			if(r>=1){
				sessionProb *= m_gamma;
			}
			//
			TUrl tUrl = urlList.get(r-1);
			_param param = lookupURL(tQuery, tUrl, false, true);
			
			if(tUrl.getGTruthClick() > 0){
				sessionProb *= (param.m_a*(1-param.m_s));
			}else{
				sessionProb *= (1-param.m_a);
			}			
		}
		
		return sessionProb;
	}

	
	public void train(){
		initialSteps(false);
		estimateParas();
	}
	
	////
	protected double calMinObjFunctionValue_NaiveRele(){
		double objVal = 0.0;
		
		for(int i=0; i<this._trainCnt; i++){
			TQuery tQuery = this._QSessionList.get(i);
			
			for(TUrl tUrl: tQuery.getUrlList()){
				_param param = lookupURL(tQuery, tUrl, false, false);
				
				double postRelePro = param.m_a;				
				double feaRelePro = tUrl.calRelePro(_naiveReleWeights);
				
				double var = Math.pow(feaRelePro-postRelePro, 2);
				
				objVal += var;
			}
		}
			
		return objVal;
	}
	protected double calMinObjFunctionValue_MarginalRele(){
		return Double.NaN;
	}
	protected void calFunctionGradient_NaiveRele(double[] g){	
		for(int i=0; i<this._trainCnt; i++){
			TQuery tQuery = this._QSessionList.get(i);			
			
			for(TUrl tUrl: tQuery.getUrlList()){						
				_param param = lookupURL(tQuery, tUrl, false, false);
				
				double postRelePro = param.m_a;								
				double feaRelePro = tUrl.calRelePro(_naiveReleWeights);
				//1
				double firstPart = 2*(feaRelePro-postRelePro);
				//2
				double releVal = USMFrame.calFunctionVal(tUrl.getReleFeatures(), _naiveReleWeights, getFunctionType());
				double expVal = Math.exp(releVal);
				double secondPart = expVal/Math.pow((1+expVal), 2);
				
				//traverse 3
				double [] naiveReleFeatures = tUrl.getReleFeatures();
				for(int k=0; k<naiveReleFeatures.length; k++){
					g[k] += (firstPart*secondPart*naiveReleFeatures[k]);
				}				
			}
		}
	}
	protected void calFunctionGradient_MarginalRele(double[] g){
		
	}
	protected void updateAlpha_NaiveRele(){
		//avoid duplicate update
		HashSet<String> releKeySet = new HashSet<>();
		
		for(int i=0; i<this._trainCnt; i++){
			TQuery tQuery = this._QSessionList.get(i);			
			
			for(TUrl tUrl: tQuery.getUrlList()){						
				String key = getKey(tQuery, tUrl);
				
				if(releKeySet.contains(key)){
					continue;
				}
				
				double feaRelePro = tUrl.calRelePro(_naiveReleWeights);
				//double emValue = m_alpha.get(key);
				/*
				if(Math.abs(emValue-feaRelePro) > 0.5){
					System.out.println("before:\t"+emValue);
					System.out.println("after:\t"+feaRelePro);
				}
				*/
				_param param = lookupURL(tQuery, tUrl, false, false);
				param.m_a = feaRelePro;
				
				releKeySet.add(key);
			}
		}
	}
	protected void updateAlpha_MarginalRele(){}
	
	protected String getOptParaFileNameString() {
		 String testParaStr = "_"+Integer.toString(_minQFreForTest)+"_"+Integer.toString(_maxQSessionSize);
			String optParaFileName = null;
			if(_mode == Mode.NaiveRele){
				optParaFileName = FRoot._bufferParaDir+"DBN_NaiveReleParameter"+testParaStr+".txt";
			}else{
				optParaFileName = FRoot._bufferParaDir+"DBN_MarReleParameter_"+_marFeaVersion.toString()+"_"+_defaultMarStyle.toString()+"_"+_lambda+testParaStr+".txt";
			}
			return optParaFileName;
	}
	
	protected void getStats_Static(){}
	protected void getStats_MarginalRele(){}
	public double getSessionProb_MarginalRele(TQuery tQuery){
		return Double.NaN;
	}
	protected FunctionType getFunctionType() {
		return _fType;
	}
	
	
	public double getSessionGainAtK(TQuery tQuery, int r){				
		double gainAtK;
		
		ArrayList<TUrl> urlList = tQuery.getUrlList();
		TUrl tUrl = urlList.get(r-1);
		
		if(tUrl.getGTruthClick() > 0){			
			if(1 == r){
				double alpha_qu = getClickProbAtK(tQuery, 1);
				gainAtK = (Math.log(alpha_qu)/_log2);
			}else{
				double alpha_qu = getClickProbAtK(tQuery, r);				
				gainAtK = (Math.log(alpha_qu)/_log2);					
			}					
		}else{
			if(1 == r){
				double alpha_qu = getClickProbAtK(tQuery, 1);
				if(1 == alpha_qu){
					alpha_qu = 0.9;
				}
				
				gainAtK = (Math.log(1-alpha_qu)/_log2);
			}else{
				double alpha_qu = getClickProbAtK(tQuery, r);
				gainAtK = (Math.log(1-alpha_qu)/_log2);
			}					
		}
		
		return gainAtK;
	}
	
	public double getSessionGainAtK_MarginalRele(TQuery tQuery, int r){
		return Double.NaN;
	}
	
	protected void estimateParas() {
		EM(40, 1e-8);
	}
//	public static void main(String[] args) {
//		if (args.length!=3){
//			System.err.println("[Usage]DBN trainset testset results");
//			return;
//		}
//			
//		DBN dbn = new DBN(0, 0.7, 2, 2, 2, 2);
//		dbn.LoadLogs("c:/Projects/Data/User Analysis/Heavy Users/Users/Content/Logs/urls_tmp.dat");
//		//dbn.LoadLogs("Data/Logs/urls.dat");
//		dbn.EM(10, 1e-3);
//		
//		//dbn.LoadLogs("Data/Bucket/urls.dat");
//		//dbn.doTest("Data/Models/DBN_bucket", true);
//		
//		dbn.set4Pred();
//		dbn.Save4MLR("Data/Vectors/logs_dpred", "Data/Heads/fv_head.dat", true, false);
//	}
	/*
	public static void main(String[] args) {
		if (args[0].equals("dtest") && args.length!=6){
			System.err.println("[Usage]dtest trainset maxUser testset results isBucket");
			return;
		} else if (args[0].equals("dpred") && args.length!=9){
			System.err.println("[Usage]dpred trainset maxUser testset maxUser fv_stat resultfile isBucket asFeature");
			return;
		}
		
		T_DBN dbn = new T_DBN(0, 0.75, 2, 2, 2, 2);
		dbn.LoadLogs(args[1], Integer.valueOf(args[2]));
		dbn.EM(10, 1e-3);//have to be trained anyway
		
		if (args[0].equals("dtest")){	
			dbn.LoadLogs(args[3]);
			dbn.doTest(args[4], Boolean.valueOf(args[5]));
		} else if (args[0].equals("dpred")){
			if (args[5].equals("null")==false)
				dbn.LoadFeatureMeanSD(args[5]);
			
			dbn.LoadLogs(args[3], Integer.valueOf(args[4]));
			dbn.set4Pred(Boolean.valueOf(args[8]));
			dbn.Save4MLR(args[6], Boolean.valueOf(args[7]));
		}
		
	}
	*/
	
	public static void main(String[] args) {
		//1
		double gamma = 0.7;
		double alpha_a; double beta_a; double alpha_s; double beta_s;
		alpha_a = beta_a = alpha_s = beta_s = 2;		
		
		double testRatio = 0.25;
		int maxQSessionSize = 10;
		int minQFreForTest = 1;

		Mode mode = Mode.NaiveRele;
		
		boolean useFeature;
		
		if(mode.equals(Mode.Original)){
			useFeature = false;
		}else{
			useFeature = true;
		}
		
		boolean uniformCmp = true;
		
		// due to the fact of serious sparcity problem!!!!, is it ok to be used as a baseline?
		T_DBN DBN = new T_DBN(maxQSessionSize, mode, useFeature, minQFreForTest, testRatio,
				gamma, alpha_a, beta_a, alpha_s, beta_s);
		
		DBN.train();
		
		//System.out.println(DBN.getTestCorpusProb(false, true));
		System.out.println();
		System.out.println("----uniform evaluation----");
		System.out.println("Log-likelihood:\t"+DBN.getTestCorpusProb(false, uniformCmp));
		System.out.println();
		DBN.getTestCorpusProb_vsQFreForTest(false, uniformCmp);
		System.out.println();
		System.out.println();
		System.out.println("Avg-perplexity:\t"+DBN.getTestCorpusAvgPerplexity(uniformCmp));
		DBN.getTestCorpusAvgPerplexity_vsQFreForTest(uniformCmp);
		
		System.out.println();
		System.out.println();
		System.out.println("----plus unobserved part evaluation----");
		System.out.println("Log-likelihood:\t"+DBN.getTestCorpusProb(false, !uniformCmp));
		System.out.println();
		System.out.println("Avg-perplexity:\t"+DBN.getTestCorpusAvgPerplexity(!uniformCmp));
	}
}
