package gLearner;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;

import session.URL;

/**
 * 
 * @author hongning
 * structure for BSS models
 */

public class ClickModel {
	class _stat{
		int _count;
		int _index;
		double _value;
		
		public _stat(int c, int i, double v){
			_count = c;
			_index = i;
			_value = v;
		}
	}
	
	static public enum ModelStatus {
		ST_init,
		ST_load,
		ST_train,
		ST_error
	};
	
	public double[] m_a_weight;//for attractiveness factor (logistic function): freshness, authority, title.occurrence, abstract.occurrence and bias term
	public double[] m_a_suffstat;
	
	public double[][] m_c_weight;//for click factor similarity: 1) A=1 (logistic function): redundancy, distance to last click, and bias term; 2) A=0, \gamma (noise)
	public double[][] m_c_suffstat;
	
	public double[][] m_e_weight;//for examine factor: 1) A=1 (logistic function): position, distance to last click, bias term; 1) A=0 (logistic function): position, # clicks, bias term; 
	public double[][] m_e_suffstat;
	
	public int m_dim;//flattened feature vector
	public double[] m_weights;	
	
	//(q,u) -> _stat in relevance table
	public HashMap<String, _stat> m_rIndex; 
	public double[] m_rTable; // (q,u) -> relevance value (just for training phase)
	public double[] m_rStat; // (q,u) -> sufficient statistics
	public boolean m_useLocalR = true; // whether to use localized relevance estimator
	public ModelStatus m_status;
	
	static public final int A_dim = 67;//26: manually selected features; 67: text matching features; 114: all the features
	
	static public final int constrain_size = 3; 
	static public final double c_weight = 10.0;
	static public final double uq_weight = 1.1;//(u,q) pair's contribution
	// 1) E[\phi(A=1,C=1,E=1)-c\phi(A=0,C=1,E=1)]>0 (A,E)
	// 2) E[\phi(A=1,C=1,E=1)] >= 1 (A,E)
	// 3) E[\phi(A_{i+1}=1,C_{i+1}=1,E_{i+1}=1) - \phi(A_i=1,C_i=0,E_i=1)] >= 0 
	static public double[] m_constrain_value = {-0.0, -1.0, 0.0};//b in the PR framework
	static public int[] m_constrain_mark = {1, 1, 1};
	static public double m_epsilon = 0.05;
	static public double m_alpha = 1.0; //default init relevance value
	static public double R_cutoff = 0.1;
	
	static final double LOG2 = Math.log(2.0);
	
	public ClickModel(double init_scale){
		m_weights = null;
		
		//feature space
		init_space();
		//ini weight w.r.t. features
		init_rand(init_scale);
		//init_zero();//initialize all the parameters to zero
		m_status = ModelStatus.ST_init;
	}
	
	public ClickModel(){
		m_dim = 0;
	}
	
	void init_space(){
		m_a_weight = new double[A_dim];
		m_a_suffstat = new double[A_dim];
		
		m_c_weight = new double[2][7];//A=0/1: position, distance to last click, # clicks, query length, clicked content sim, skipped content sim, bias
		m_c_suffstat = new double[2][7];
		
		m_e_weight = new double[2][6];//A=0/1: position, # clicks, distance to last click, avg content redundancy, dev content redundancy, bias
		m_e_suffstat = new double[2][6];
		
		if (m_useLocalR)
			m_rIndex = new HashMap<String, _stat>();
	}
	
	void init_residual(int cutoff){
		trimRelevanceTable(cutoff);
		m_rStat = new double[m_rIndex.size()];
	}
	
	void init_zero(){
		m_dim = 0;		
		m_dim += m_a_weight.length;
		
		for(int i=0; i<m_c_weight.length; i++)
			m_dim += m_c_weight[i].length;		
		
		for(int i=0; i<m_e_weight.length; i++)
			m_dim += m_e_weight[i].length;

		System.out.println("[Info]Functional feature dimension " + m_dim);
		//getWeights();//have to wait until we know all the (q,u) pairs
	}
	
	void init_rand(double scale){
		m_dim = 0;
		Random rand = new Random();
		for(int i=0; i<m_a_weight.length; i++)
			m_a_weight[i] = (2*rand.nextDouble()-1)/scale;
		m_dim += m_a_weight.length;
		
		for(int i=0; i<m_c_weight.length; i++){
			m_dim += m_c_weight[i].length;
			for(int j=0; j<m_c_weight[i].length; j++)
				m_c_weight[i][j] = (2*rand.nextDouble()-1)/scale;
		}
		
		for(int i=0; i<m_e_weight.length; i++){
			m_dim += m_e_weight[i].length;
			for(int j=0; j<m_e_weight[i].length; j++)
				m_e_weight[i][j] = (2*rand.nextDouble()-1)/scale;
		}

		System.out.println("Feature dimension " + m_dim);
		//getWeights();//have to wait until we know all the (q,u) pairs
	}
	
	public int getDim(){
		if (m_useLocalR)
			return m_dim + m_rIndex.size();
		else
			return m_dim;
	}
	
	public void SaveRelevanceTable(String filename){
		try {
			BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(filename)));
			Iterator<Map.Entry<String, _stat> > it = m_rIndex.entrySet().iterator();
			_stat s = null;
		    while (it.hasNext()) {
		        Map.Entry<String, _stat> pairs = (Map.Entry<String, _stat>)it.next();
		        s = pairs.getValue();
		        writer.write(pairs.getKey() + "\t" + m_rTable[s._index] + "\n");
		    }
			writer.close();
			System.out.println("[Info]Total " + m_rIndex.size() + " (q,u) pairs saved!");
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	public void LoadRelevanceTable(String filename){
		try {
			File table_model = new File(filename);
			if (table_model.exists()==false){
				System.out.println("[Info]No (q,u) pair can be loaded!");
				return;
			}
			
			BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(table_model)));
			String tmpTxt;
			String[] container;
			while ((tmpTxt=reader.readLine()) != null){
				if (tmpTxt.isEmpty())
					continue;
				container = tmpTxt.split("\t");
				m_rIndex.put(container[0], new _stat(0, m_rIndex.size(), Double.valueOf(container[1])));
			}
			reader.close();
			System.out.println("[Info]Total " + m_rIndex.size() + " (q,u) pairs loaded!");
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	void set_a_weight(String[] weights){
		m_a_weight = new double[weights.length];
		for(int i=0; i<weights.length; i++)
			m_a_weight[i] = Double.valueOf(weights[i]);
		m_a_suffstat = new double[weights.length];
		m_dim += weights.length;
	}
	
	void set_c_weight(String[][] weights){
		m_c_weight = new double[weights.length][];
		m_c_suffstat = new double[weights.length][];
		for(int i=0; i<weights.length; i++){
			m_c_weight[i] = new double[weights[i].length];
			m_c_suffstat[i] = new double[weights[i].length];
			for(int j=0; j<weights[i].length; j++)
				m_c_weight[i][j] = Double.valueOf(weights[i][j]);
			m_dim += weights[i].length;
		}
	}
	
	void set_e_weight(String[][] weights){
		m_e_weight = new double[weights.length][];
		m_e_suffstat = new double[weights.length][];
		for(int i=0; i<weights.length; i++){
			m_e_weight[i] = new double[weights[i].length];
			m_e_suffstat[i] = new double[weights[i].length];
			for(int j=0; j<weights[i].length; j++)
				m_e_weight[i][j] = Double.valueOf(weights[i][j]);
			m_dim += weights[i].length;
		}
	}
	
	void stat_clear(){
		Arrays.fill(m_a_suffstat, 0);
		
		Arrays.fill(m_c_suffstat[0], 0);
		Arrays.fill(m_c_suffstat[1], 0);	
		
		Arrays.fill(m_e_suffstat[0], 0);
		Arrays.fill(m_e_suffstat[1], 0);
		
		if (m_useLocalR)
			Arrays.fill(m_rStat, 0);
	}
	
	
	public double[] getWeights(){
		if (m_weights==null || m_weights.length<getDim())
			m_weights = new double[getDim()];
		
		int index=0;
		for(int i=0; i<m_a_weight.length; i++)
			m_weights[index++] = m_a_weight[i];
		
		for(int i=0; i<m_c_weight.length; i++)
			for(int j=0; j<m_c_weight[i].length; j++)
				m_weights[index++] = m_c_weight[i][j];
		
		for(int i=0; i<m_e_weight.length; i++)
			for(int j=0; j<m_e_weight[i].length; j++)
				m_weights[index++] = m_e_weight[i][j];
		
		if (m_useLocalR){
			for(int i=0; i<m_rTable.length; i++)
				m_weights[index++] = m_rTable[i];
		}
		return m_weights;
	}
	
	public void getStats(double[] g){
		if (g.length != getDim()){
			System.err.println("Unmatched feature dimension!");
			System.exit(-1);
		}
		
		int index=0;
		for(int i=0; i<m_a_suffstat.length; i++)
			g[index++] = m_a_suffstat[i];
		
		for(int i=0; i<m_c_suffstat.length; i++)
			for(int j=0; j<m_c_suffstat[i].length; j++)
				g[index++] = m_c_suffstat[i][j];
		
		for(int i=0; i<m_e_suffstat.length; i++)
			for(int j=0; j<m_e_suffstat[i].length; j++)
				g[index++] = m_e_suffstat[i][j];
		
		if (m_useLocalR){
			for(int i=0; i<m_rStat.length; i++)
				g[index++] = m_rStat[i];
		}
	}
	
	public void setWeights(double[] weights){
		if (weights.length != getDim()){
			System.err.println("Unmatched feature dimension!");
			System.exit(-1);
		}
		
		int index=0;
		for(int i=0; i<m_a_weight.length; i++)
			m_a_weight[i] = weights[index++];
		
		for(int i=0; i<m_c_weight.length; i++)
			for(int j=0; j<m_c_weight[i].length; j++)
				m_c_weight[i][j] = weights[index++];
		
		for(int i=0; i<m_e_weight.length; i++)
			for(int j=0; j<m_e_weight[i].length; j++)
				m_e_weight[i][j] = weights[index++];
		
		if (weights!=m_weights)
			System.arraycopy(weights, 0, m_weights, 0, m_weights.length);
		
		if (m_useLocalR)
			updateRelevanceTable(weights);
	}
	
	public void updateRelevanceTable(double[] w){
		System.arraycopy(w, m_dim, m_rTable, 0, m_rTable.length);
	}
	
	public int getRelevanceIndexInTable(String query, String url){
		String key = query + "@" + url;
		if (m_rIndex!=null && m_rIndex.containsKey(key))
			return m_rIndex.get(key)._index;
		else
			return -1;
	}
	
	public double getRelevanceInTable(String query, String url, boolean add4miss){
		return getRelevanceInTable(query, url, ClickModel.m_alpha, add4miss);
	}
		
	public double getRelevanceInTable(String query, String url, double rate, boolean add4miss){
		String key = query + "@" + url;
		if (m_rIndex!=null && m_rIndex.containsKey(key)){
			_stat s = m_rIndex.get(key);
			if (add4miss){
				s._count ++;
				s._value += rate;//accumulate the statistics, in order to trim the table
			}
			if (m_rTable==null)
				return s._value;
			else
				return m_rTable[s._index];
		}
		else if (m_rIndex!=null && add4miss){
			m_rIndex.put(key, new _stat(1, m_rIndex.size(), rate));
			return rate;
		}
		else
			return ClickModel.m_alpha;
	}
	
	public void trimRelevanceTable(int cutoff){
		if (cutoff>0 || R_cutoff>0){
			HashMap<String, _stat> newDict = new HashMap<String, _stat>();
			Iterator<Map.Entry<String, _stat> > it = m_rIndex.entrySet().iterator();
			_stat s;
		    while (it.hasNext()) {
		        Map.Entry<String, _stat> pairs = (Map.Entry<String, _stat>)it.next();
		        s = pairs.getValue();
		        if (s._count>cutoff && s._value/s._count>R_cutoff){//we get enough difference
		        	s._index = newDict.size();
		        	newDict.put(pairs.getKey(), s);
		        }
		    }
		    System.out.println("[Info]Trim the (q,u) pairs from " + m_rIndex.size() + " to " + newDict.size() + "...");
		    m_rIndex = newDict;
		} 
		m_rTable = new double[m_rIndex.size()];//just for training purpose 
		ClickModel.m_alpha = 0.0;//in case others would need it
	}
	
	public double L2Norm(){
		double sum = 0;
		for(int i=0; i<m_weights.length; i++)
			sum += m_weights[i] * m_weights[i];
		return sum;
	}
	
	public double L2Norm(int dim){
		double sum = 0;
		for(int i=0; i<Math.min(dim, m_weights.length); i++)
			sum += m_weights[i] * m_weights[i];
		return sum;
	}
	
	public double L2Norm(double[] target){
		double sum = 0;
		for(int i=0; i<m_weights.length; i++)
			sum += (m_weights[i]-target[i]) * (m_weights[i]-target[i]);
		return sum;
	}
	
	public double L2Norm(double[] mean, double[] var){
		double sum = 0;
		for(int i=0; i<m_weights.length; i++)
			sum += (m_weights[i]-mean[i]) * (m_weights[i]-mean[i]) / var[i];
		return sum;
	}
	
	static public double cosine(URL u1, URL u2){
		return u1.m_vect.dotProduct(u2.m_vect);
	}
	
	static public double logistic(double a){
		if (a>20)
			a = 20;
		else if (a<-20)
			a = -20;
		return 1.0 / (1.0+Math.exp(-a));
	}
	
	//dot product
	static public double linear(double[] w, double[] v){
		if (w.length != v.length){
			System.err.println("Unmatched dimention!");
			return 0;
		}
		double sum = 0;
		for(int i=0; i<w.length; i++)
			sum += w[i] * v[i];
		return sum;
	}
	
	static public boolean fileExists(String filename){
		File f = new File(filename);
		return f.exists();
	}
	
	//project to x>=0
	static public void doProjection(double[] x){
		for(int i=0; i<x.length; i++)
			if (x[i]<0)
				x[i] = 0;
	}
	
	static public double click_likelihood(double p, int c){
		if (c==1){
			if (p<=0)
				return 0;//log underflow
			return Math.log(p)/ClickModel.LOG2;
		}
		else{
			if (p>=1)
				return 0;
			return Math.log(1-p)/ClickModel.LOG2;
		}
	}
}
