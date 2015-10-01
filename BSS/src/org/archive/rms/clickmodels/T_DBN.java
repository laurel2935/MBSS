package org.archive.rms.clickmodels;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Random;

import org.archive.rms.advanced.MAnalyzer;
import org.archive.rms.data.TQuery;
import org.archive.rms.data.TUrl;


/**
 * 
 * Implementation of User Browsing Modeling proposed in 
 * "Chapelle, Olivier, and Ya Zhang. "A dynamic bayesian network click model for web search ranking." WWW. ACM, 2009."
 */

public class T_DBN extends MAnalyzer implements T_Evaluation {

	class _param{
		double m_a;
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
	
	public T_DBN(double testRatio, double gamma, double alpha_a, double beta_a, double alpha_s, double beta_s){
		super(testRatio, false);
		
		m_gamma = gamma;
		
		//beta distribution w.r.t. event:A
		m_alpha_a = alpha_a;
		m_beta_a = beta_a;
		
		//beta distribution w.r.t. event:S
		m_alpha_s = alpha_s;
		m_beta_s = beta_s;
		
		m_rand = new Random();
		m_urlTable = new HashMap<String, _param>();
	}
	
	_param lookupURL(String query, String URL, boolean add4miss){
		String key = query + "@" + URL;
		if (m_urlTable.containsKey(key))
			return m_urlTable.get(key);
		else if (add4miss){
			_param p = new _param();
			m_urlTable.put(key, p);
			return p;
		}
		else 
			return null;
	}
	
	public void EM(int iter, double tol){
		//
		double[][] alpha = new double[11][2], beta = new double[11][2];//treiler for forward and backward
		
		alpha[0][0] = 0.0; alpha[0][1] = 1.0; 
		
		int uSize, c, step=0;
		_param p;
		double diff = 1.0;
		
		while(step++<iter && diff>tol){
			//E-step
			for(TQuery tQuery : _QSessionList){
				String qText = tQuery.getQueryText();
				//uSize = query.m_urls.size();
				ArrayList<TUrl> urlList = tQuery.getUrlList();
				
				uSize = urlList.size();
				
				//prior beta distribution with parameters (1,1)
				beta[uSize][0] = 1.0; beta[uSize][1] = 1.0;//in case we have varying length of sessions
				
				//alpha
				for(int i=0; i<uSize; i++){//alpha update
					//c = query.m_urls.get(i).m_click>0?1:0;
					TUrl url = urlList.get(i);
					c = url.getGTruthClick()>0?1:0;
					//p = lookupURL(query.m_query, query.m_urls.get(i).m_URL, true);
					p = lookupURL(qText, url.getDocNo(), true);
					
					if (c==0){
						//corresponds to equation page_10 in the paper appendix: e.g., one can easily derive the recursion formula:
						//c=0, e=0, thus (1-p.m_a):c_(i+1)=0 (1-m_gamma):e_(i+1)=0
						alpha[i+1][0] = alpha[i][0] + alpha[i][1]*(1-p.m_a)*(1-m_gamma);
						//c=0, e=1, thus (1-p.m_a):c_(i+1)=0 m_gamma:e_(i+1)=1
						alpha[i+1][1] = alpha[i][1]*(1-p.m_a)*m_gamma;
					} else {
						alpha[i+1][0] = alpha[i][1]*(p.m_a*(1-m_gamma+p.m_s*m_gamma));
						alpha[i+1][1] = alpha[i][1]*p.m_a*m_gamma*(1-p.m_s);
					}
				}
				//beta
				for(int i=uSize; i>0; i--){//beta update
					TUrl url = urlList.get(i-1);
					//c = query.m_urls.get(i-1).m_click>0?1:0;
					c = url.getGTruthClick()>0?1:0;
					//p = lookupURL(query.m_query, query.m_urls.get(i-1).m_URL, true);
					p = lookupURL(qText, url.getDocNo(), true);
					
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
					p = lookupURL(qText, url.getDocNo(), true);
					
					p.m_ss_a[1] += 1;						
					if (c>0){
						p.m_ss_a[0] += 1;
							
						p.m_ss_s[0] += alpha[i+1][0] * beta[i+1][0] / beta[0][1] / ((1-m_gamma)/p.m_s + m_gamma);
						p.m_ss_s[1] += 1; 
					} else {
						p.m_ss_a[0] += p.m_a * alpha[i][0] * beta[i][0] / beta[0][1];
					}
				}
			}
			//M-step
			double old;
			diff = 0;
			for(_param para:m_urlTable.values()){
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
			diff /= m_urlTable.size();
			System.out.println("[Info]EM step " + step + ", diff:" + diff);
		}
		System.out.println("[Info]Processed " + m_urlTable.size() + " (q,u) pairs...");
	}
		
	@Override
	public double getClickProb(TQuery tQuery, TUrl tUrl) {
		_param para = lookupURL(tQuery.getQueryText(), tUrl.getDocNo(), false);
		if (para!=null)//existing one
			return 2 * para.m_a * para.m_s;
		else//totally a new one
			return 2 * (m_alpha_a-1)/(m_alpha_a+m_beta_a-2) * (m_alpha_s-1)/(m_alpha_s+m_beta_s-2) + m_rand.nextDouble()/10;
	}
	
	
	public double getSessionProb(TQuery tQuery, boolean onlyClicks){
		//due to the uncertainty of "2*"
		return 0.0;
	}
	
	@Override
	public double getTestCorpusProb(boolean onlyClicks){
		double corpusLikelihood = 0.0;
		
		for(int k=this._testNum; k<this._QSessionList.size(); k++){
			TQuery tQuery = this._QSessionList.get(k);
			double session = getSessionProb(tQuery, onlyClicks);
			corpusLikelihood += Math.log(session);
		}
		//the higher the better
		return corpusLikelihood;		
	}
	
	public void train(){}
	
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
	
	public static void main(String[] args) {
		if (args[0].equals("dtest") && args.length!=6){
			System.err.println("[Usage]dtest trainset maxUser testset results isBucket");
			return;
		} else if (args[0].equals("dpred") && args.length!=9){
			System.err.println("[Usage]dpred trainset maxUser testset maxUser fv_stat resultfile isBucket asFeature");
			return;
		}
		/*
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
		*/
	}
}
