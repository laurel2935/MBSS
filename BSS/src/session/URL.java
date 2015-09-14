/**
 * 
 */
package session;

import gLearner.ClickModel;

import java.util.Arrays;
import java.util.Vector;

import cc.mallet.grmm.types.Assignment;
import cc.mallet.grmm.types.AssignmentIterator;
import cc.mallet.grmm.types.Factor;
import cc.mallet.grmm.types.LogTableFactor;
import cc.mallet.grmm.types.TableFactor;
import cc.mallet.grmm.types.VarSet;
import cc.mallet.grmm.types.Variable;
import cc.mallet.types.Alphabet;
import cc.mallet.types.FeatureVector;
import cc.mallet.types.MatrixOps;
import cc.mallet.types.SparseVector;

/**
 * @author czhai
 * basic structure for URLs, implementation of the factors
 */
public class URL implements Comparable<URL>{
	public String m_URL;
	public int m_pos;//start from 1!!
	public long m_click;//click time stamp (might be changed later via algorithm's prediction)
	public int m_c;//ground-truth of click
	//represented by the vector of unigram and bigram
	public String[] m_content;
	//A subset of an Alphabet in which each element of the subset has an associated value.
	//The subset is represented as a SparseVector. A SparseVector represents only the non-zero locations of a vector.
	//In the case of a FeatureVector, a location represents the index of an entry 
	//in the Alphabet that is contained in the FeatureVector.
	public FeatureVector m_vect;//feature vector for content
	public double[] m_features;//Yahoo relevance ranking features
	
	public Variable m_A, m_E, m_C;//m_C is used for MAP testing phase
	private Variable[] m_vars;
	public TableFactor m_cFactor, m_eFactor, m_aFactor;//click, examine and attractiveness factors for original problem
	public Factor m_postcFactor, m_posteFactor;
	
	
	// 1) E[\phi(A=1,C=1,E=1)-10\phi(A=0,C=1,E=1)]>0
	// 2) E[\phi(A=1,C=1,E=1)] >= 1 
	public LogTableFactor[] m_constrains;
	//whether this URL would contribute a pairwise constrain
	public boolean m_pCon;
	
	//cached features
	public double[] m_A_fea, m_E_fea, m_C_fea;
	
	public double m_pClick;
	
	public URL(String meta){
		int startPos = meta.lastIndexOf(','), endPos;
		m_pos = Integer.valueOf(meta.substring(1+startPos));
		
		endPos = startPos;
		startPos = meta.lastIndexOf(',', endPos-1);
		m_click = Long.valueOf(meta.substring(1+startPos, endPos));
		m_c = m_click>0?1:0;
		
		m_URL = meta.substring(0, startPos).toLowerCase();
		m_vect = null;
		
		//variables for A, C, E events
		m_A = null;
		m_E = null;
		m_C = null;
		
		//factor tables for this particular URL
		m_cFactor = null;
		m_eFactor = null;
		m_aFactor = null;
		
		//to store the posterior result
		m_postcFactor = null;
		m_posteFactor = null;
		
		//features for the factors
		m_A_fea = null;
		m_C_fea = null;
		m_E_fea = null;
		
		//for posterior regularization
		m_constrains = null;
		m_pCon = false;
		
		m_pClick = 0.0;
	}
	
	public URL(String URL, int pos, long click){
		m_URL = URL;
		m_pos = pos;
		m_click = click;
		
		m_content = null;
		m_features = null;
	}	
	
	public String toString(){
		return m_URL + "\t" + m_pos;
	}
	
	public void setContent(String[] content, Alphabet dict){
		m_content = new String[content.length];
		System.arraycopy(content, 0, m_content, 0, content.length);
		setupFV(dict);
	}
	
	void setupFV(Alphabet dict){
		double[] values = new double[m_content.length];
		//the sum of square will be 1, and an equal weight
		Arrays.fill(values, 1.0/Math.sqrt(m_content.length));
		for(String t:m_content){
			//add the entry if not exist
			dict.lookupIndex(t, true);
		}
		m_vect = new FeatureVector(dict, m_content, values);
	}
	
	public void setFeatures(String[] features){//store all the features from file
		m_features = new double[1+features.length];//for one additional age feature
		for(int i=0; i<features.length; i++)
			m_features[i+1] = Double.valueOf(features[i]);
	}
	
	public void setAge(double age){
		m_features[0] = 2e-4 + age/3600;//unit: one hour
	}

	@Override
	public int compareTo(URL u) {
		return this.m_pos-u.m_pos;
	}
	
	public int getFeatureSize(){
		return m_A_fea.length + m_C_fea.length*2 + m_E_fea.length*2;//two weights for C and E event
	}
	
	public int getRelFeatureSize(){
		return m_features.length;
	}
	
	public void collectFeatures(Query query){
		Vector<URL> urllist = query.m_urls;
		
		double cSim = 0, ncSim = 0, sim;
		int clickSize = 0, clickPos = -1;
		URL u;
		
		SparseVector centriod = new SparseVector();
		
		for(int i=0; i<m_pos-1; i++){
			u = urllist.get(i);
			
			sim = ClickModel.cosine(this, u);
			
			if (u.m_click>0){
				cSim += sim;//clicked content similarity
				clickSize ++;//number of clicks
				if (u.m_pos>clickPos)
					clickPos = u.m_pos;//position of last click
			} 
			else
				ncSim += sim;//non-clicked content similarity
			centriod = centriod.vectorAdd(u.m_vect, 1.0);
		}
		
		double sim_mean = 0, sim_dev = 0;
		if (m_pos>1){//content variance
			centriod.vectorAdd(m_vect, 1.0);
			centriod.timesEquals(1.0/centriod.twoNorm());//normalize the centriod
			
			double[] similarity = new double[m_pos];
			for(int i=0; i<m_pos; i++){
				similarity[i] = centriod.dotProduct(urllist.get(i).m_vect);
				sim_mean += similarity[i];
			}
			sim_mean /= m_pos;
			
			for(int i=0; i<m_pos; i++)
				sim_dev += (similarity[i]-sim_mean) * (similarity[i]-sim_mean);
			sim_dev = Math.sqrt(sim_dev/m_pos);
		}
		
		//features for attractiveness
		getAfeatures();
	
		//features for click
		if (m_C_fea==null)
			m_C_fea = new double[7];
		m_C_fea[0] = m_pos/10.0;//random click depends on position
		m_C_fea[1] = (clickPos>0?m_pos-clickPos:0);//distance to last click
		m_C_fea[2] = clickSize;//# click
		m_C_fea[3] = Math.log(1+query.getQueryLength());//query length
		m_C_fea[4] = (clickSize>0?cSim/clickSize:0);//clicked content redundancy
		m_C_fea[5] = (clickSize<m_pos-1?ncSim/(m_pos-clickSize-1):0);//non-clicked content redundancy
		m_C_fea[6] = 1.0;//bias
		
		//features for examine
		if (m_E_fea==null)
			m_E_fea = new double[6];
		m_E_fea[0] = m_pos/10.0;//position
		m_E_fea[1] = clickSize;//# of clicks
		m_E_fea[2] = (clickPos>0?m_pos-clickPos:0);//distance to last click
		m_E_fea[3] = sim_mean;//avg content redundancy
		m_E_fea[4] = sim_dev;//dev content redundancy
		m_E_fea[5] = 1.0;//bias
	}
	
	public void normalize(double[] x, double[] xx){
		for(int i=0; i<m_features.length; i++){
			if (i==95)
				continue;//query length
			else if (i==0)//only age feature need to take log
				m_features[0] = Math.log(Math.abs(m_features[0]));
				
			if (xx[i]!=0)
				m_features[i] = (m_features[i]-x[i])/xx[i];
			else
				m_features[i] = 0;
		}
	}
	
	public void getAfeatures(){
		if (m_A_fea==null){
			
			m_A_fea = new double[ClickModel.A_dim];//use all features
			//if we have normalized the features
//			m_A_fea[0] = m_features[0];//freshness (may have error in the original data)
//			m_A_fea[1] = m_features[1];//authority
			m_A_fea[0] = 2*ClickModel.logistic(m_features[0])-1;
			m_A_fea[1] = 2*ClickModel.logistic(m_features[1])-1;
			
			//if we haven't normalized the features
			//m_A_fea[0] = Math.log(Math.abs(m_features[0]));//freshness (may have error in the original data)
			//m_A_fea[1] = Math.log(1+m_features[1]);//authority
			//m_A_fea[1] = m_features[1]/10.0;//authority
			
			
			if (m_A_fea.length>30){//set to 30 in case we would add more features
				for(int i=2; i<m_A_fea.length-1; i++){
					m_A_fea[i] = 2*ClickModel.logistic(m_features[i])-1;//in sigmod scale
					//m_A_fea[i] = m_features[i];//in original scale
					//m_A_fea[i] = Math.log(1+m_features[i]);//in log scale
				}
				m_A_fea[m_A_fea.length-1] = 1.0;//67 features in total
			}
			else{
				m_A_fea[2] = m_features[56];//title.occurrrence
				m_A_fea[3] = m_features[13];//abstrace.occurrence
				m_A_fea[4] = m_features[4];//abstract.completeness
				m_A_fea[5] = m_features[5];//abstract.earliness
				m_A_fea[6] = m_features[47];//title.completeness
				m_A_fea[7] = m_features[48];//title.earliness
				m_A_fea[8] = m_features[7];//fieldMatch(abstract).gapLength
				m_A_fea[9] = m_features[10];//fieldMatch(abstract).longestSequence
				m_A_fea[10] = m_features[50];//fieldMatch(title).gapLength
				m_A_fea[11] = m_features[55];//fieldMatch(title).match
				m_A_fea[12] = m_features[12];//fieldMatch(abstract).match
				m_A_fea[13] = m_features[34];//fieldMatch(body).matches
				m_A_fea[14] = m_features[53];//fieldMatch(title).longestSequence
				m_A_fea[15] = m_features[16];//fieldMatch(abstract).proximity
				m_A_fea[16] = m_features[17];//fieldMatch(abstract).queryCompleteness
				m_A_fea[17] = m_features[14];//fieldMatch(abstract).orderness
				m_A_fea[18] = m_features[29];//fieldMatch(body).gapLength
				//newly added features
				m_A_fea[19] = m_features[52];//fieldMatch(title).head
				m_A_fea[20] = m_features[62];//fieldMatch(title).segmentDistance
				m_A_fea[21] = m_features[32];//fieldMatch(body).longestSequence
				m_A_fea[22] = m_features[43];//fieldMatch(body).segments
				m_A_fea[23] = m_features[21];//fieldMatch(body).segments
				m_A_fea[24] = m_features[18];//fieldMatch(abstract).relatedness
				
//				m_A_fea[2] = Math.log(1.0+m_features[56]);//title.occurrrence
//				m_A_fea[3] = Math.log(1.0+m_features[13]);//abstrace.occurrence
//				m_A_fea[4] = Math.log(1.0+m_features[4]);//abstract.completeness
//				m_A_fea[5] = Math.log(1.0+m_features[5]);//abstract.earliness
//				m_A_fea[6] = Math.log(1.0+m_features[47]);//title.completeness
//				m_A_fea[7] = Math.log(1.0+m_features[48]);//title.earliness
//				m_A_fea[8] = Math.log(1.0+m_features[7]);//fieldMatch(abstract).gapLength
//				m_A_fea[9] = Math.log(1.0+m_features[10]);//fieldMatch(abstract).longestSequence
//				m_A_fea[10] = Math.log(1.0+m_features[50]);//fieldMatch(title).gapLength
//				m_A_fea[11] = Math.log(1.0+m_features[55]);//fieldMatch(title).match
//				m_A_fea[12] = Math.log(1.0+m_features[12]);//fieldMatch(abstract).match
//				m_A_fea[13] = Math.log(1.0+m_features[34]);//fieldMatch(body).matches
//				m_A_fea[14] = Math.log(1.0+m_features[53]);//fieldMatch(title).longestSequence
//				m_A_fea[15] = Math.log(1.0+m_features[16]);//fieldMatch(abstract).proximity
//				m_A_fea[16] = Math.log(1.0+m_features[17]);//fieldMatch(abstract).queryCompleteness
//				m_A_fea[17] = Math.log(1.0+m_features[14]);//fieldMatch(abstract).orderness
//				m_A_fea[18] = Math.log(1.0+m_features[29]);//fieldMatch(body).gapLength
////				//newly added features
//				m_A_fea[19] = Math.log(1.0+m_features[52]);//fieldMatch(title).head
//				m_A_fea[20] = Math.log(1.0+m_features[62]);//fieldMatch(title).segmentDistance
//				m_A_fea[21] = Math.log(1.0+m_features[32]);//fieldMatch(body).longestSequence
//				m_A_fea[22] = Math.log(1.0+m_features[43]);//fieldMatch(body).segments
//				m_A_fea[23] = Math.log(1.0+m_features[21]);//fieldMatch(body).segments
//				m_A_fea[24] = Math.log(1.0+m_features[18]);//fieldMatch(abstract).relatedness
				m_A_fea[25] = 1.0;//9 basic relevance features
			}
		}
	}
	
	public double getAscore(Query q, ClickModel model){
		double v = ClickModel.linear(model.m_a_weight, m_A_fea);
		if (model.m_useLocalR)
			v += ClickModel.uq_weight * model.getRelevanceInTable(q.m_query, m_URL, false);
		return (m_pClick=ClickModel.logistic(v));
	}
	
	public void updateFactors(Query q, ClickModel model){
		Assignment assign;
		AssignmentIterator aIter;
		int a;
		double v;
		
		//A factor
		v = ClickModel.linear(model.m_a_weight, m_A_fea);
		if (model.m_useLocalR)
			v += ClickModel.uq_weight * model.getRelevanceInTable(q.m_query, m_URL, false);//in testing phase, we share not register new (q,u) pairs
		v = ClickModel.logistic(v);
		
		aIter = m_aFactor.assignmentIterator();
		while(aIter.hasNext()){
			assign = aIter.assignment();
			if (assign.get(m_A)==1)
				m_aFactor.setValue(aIter, v);
			else
				m_aFactor.setValue(aIter, 1-v);
			aIter.advance();
		}
		
		//C factor
		VarSet vSet = m_cFactor.varSet();
		aIter = vSet.assignmentIterator();
		while(aIter.hasNext()){
			assign = aIter.assignment();
			if (assign.get(m_E)==0)//E_i == 0
				v = 1e-10;
			else{ //E_i == 1
				a = assign.get(m_A);//A_i = 0 / 1
				v = ClickModel.logistic(ClickModel.linear(model.m_c_weight[a], m_C_fea));//A_i = 0 / 1
			}
			
			if (m_c>0)
				m_cFactor.setValue(aIter, v);//expected
			else
				m_cFactor.setValue(aIter, 1-v);//rare
			aIter.advance();
		}
		
		//E factor
		Vector<URL> urllist = q.m_urls;
		vSet = m_eFactor.varSet();
		if (m_pos>1){//first position must be examined
			Variable Ep = urllist.get(m_pos-2).m_E, Ap = urllist.get(m_pos-2).m_A;//previous examine and attractiveness status
			aIter = vSet.assignmentIterator();
			while(aIter.hasNext()){
				assign = aIter.assignment();
				if (assign.get(Ep)==0)//E_{i-1} == 0
					v = 1e-5;//a tricky setting!
				else{//E_{i-1} == 1
					a = assign.get(Ap);
					v = ClickModel.logistic(ClickModel.linear(model.m_e_weight[a], m_E_fea));
				}
				
				if (assign.get(m_E)==1)
					m_eFactor.setValue(aIter, v);
				else 
					m_eFactor.setValue(aIter, 1-v);
				
				aIter.advance();
			}
		}
	}
	
	private void advanceConf(int i, int[] conf, int end){
		while(i<end && conf[i]==1){
			conf[i] = 0;
			i++;
		}
		if (i<end)
			conf[i] = 1;
	}
	
	private void setClicks(Vector<URL> urls, int[] conf){
		for(int i=0; i<m_pos-1; i++)
			urls.get(i).m_click = conf[i];
	}
	
	private double getC_potential(int C, int A, int E, ClickModel model){
		if (E==0)
			return 1-C;
		
		double v = ClickModel.logistic(ClickModel.linear(m_C_fea, model.m_c_weight[A]));
		if (C==1)
			return v;
		else
			return 1-v;
	}
	
	private double getE_potential(int Ep, int Ap, int E, ClickModel model){
		if (Ep==0)
			return 1-E;
		
		double v = ClickModel.logistic(ClickModel.linear(m_E_fea, model.m_e_weight[Ap]));;
		if (E==1)
			return v;
		else
			return 1-v;
	}
	
	private void fillinFirstPosition(Query query, ClickModel model){
		collectFeatures(query);
		
		try{
			double v = ClickModel.linear(m_A_fea, model.m_a_weight);
			if (model.m_useLocalR)
				v += model.getRelevanceInTable(query.m_query, m_URL, false);//in testing phase, we share not register new (q,u) pairs
			v = ClickModel.logistic(v);
			m_aFactor.setValues(new double[]{1-v, v});//A factor is easy to set
		} catch(Exception ex){
			System.err.println("[Error]Error in query " + query);
			System.exit(-1);
		}
		
		int[] conf = new int[m_pos+2];//configuration for both C factor and E factor
		Assignment assign_c = new Assignment(m_vars, conf);
		for(int j=0; j<8; j++){//to fill in C factor
			assign_c.setRow(0, conf);
			m_cFactor.setRawValue(assign_c, getC_potential(conf[0], conf[1], conf[2], model));//value to be calculated
			advanceConf(0, conf, 3);//get next click event combination
		}
	}
	
	private void fillinFactorTables(Query query, ClickModel model){//for m_pos>1 
		//c_factor: C_0, ..., C_{i-1}, E_i, A_i => C_i
		//e_factor: C_0, ..., C_{i-1}, E_{i-1}, A_{i-1} => E_i
		int[] conf = new int[m_pos+2];//configuration for both C factor and E factor
		Assignment assign_e = new Assignment(m_vars, conf); 
		m_vars[m_pos-1] = m_C;
		m_vars[m_pos] = m_A;
		Assignment assign_c = new Assignment(m_vars, conf);
		for(int i=0; i<Math.pow(2, m_pos-1); i++){//all the previous clicks' combination: m_pos-1 positions
			setClicks(query.m_urls, conf);
			collectFeatures(query);//use the fake clicks to generate the features to fill in the table
			
			for(int j=0; j<8; j++){
				//to fill in C factor
				assign_c.setRow(0, conf);
				m_cFactor.setRawValue(assign_c, getC_potential(conf[m_pos-1], conf[m_pos], conf[m_pos+1], model));//value to be calculated
				
				//to fill in E factor
				assign_e.setRow(0, conf);
				m_eFactor.setRawValue(assign_e, getE_potential(conf[m_pos-1], conf[m_pos], conf[m_pos+1], model));//value to be calculated
				
				//get next event combination
				advanceConf(m_pos-1, conf, conf.length);
			}
			
			advanceConf(0, conf, m_pos);//get next previous click event combination
		}
		
		double v = ClickModel.linear(m_A_fea, model.m_a_weight);
		if (model.m_useLocalR)
			v += model.getRelevanceInTable(query.m_query, m_URL, false);//in testing phase, we share not register new (q,u) pairs
		v = ClickModel.logistic(v);
		m_aFactor.setValues(new double[]{1-v, v});//A factor is easy to set
	}
	
	public void createFactors(Query query, ClickModel model){
		createFactors(query);
		updateFactors(query, model);
	}
	//building the factor graph for a specific query
	public void createFactors(Query query){
		collectFeatures(query);
		
		m_A = new Variable(2);//A
		m_E = new Variable(2);//E		

		//A factor, w.r.t. Relevance event (or attractiveness event), thus no parents	
		m_aFactor = new TableFactor(m_A);
		
		//C factor, w.r.t. click event, taking the A and E as the parent nodes
		m_cFactor = new TableFactor(new Variable[]{m_A, m_E});
		
		//E factor
		if (m_pos==1){
			//first position must be examined
			//thus no parent node
			m_eFactor = new TableFactor(m_E, new double[]{0, 1});
		} else {
			//for a url that below rank position 1, thus taking E_{i-1} and A_{i-1} as parents
			Vector<URL> urllist = query.m_urls;
			Variable Ep = urllist.get(m_pos-2).m_E, Ap = urllist.get(m_pos-2).m_A;//previous examine and attractiveness status
			m_eFactor = new TableFactor(new Variable[]{m_E, Ep, Ap});
		}	
		
		if (ClickModel.constrain_size>0)
			createConstrains(query.m_urls);
	}
	
	public void createFactors4MAP(Query query, ClickModel model){		
		m_A = new Variable(2);//A
		m_C = new Variable(2);//C
		m_E = new Variable(2);//E		

		//A factor		
		m_aFactor = new TableFactor(m_A);
		
		//C factor
		int i;
		m_vars = new Variable[2+m_pos];
		for(i=0; i<m_pos-1; i++)
			m_vars[i] = query.m_urls.get(i).m_C;//previous clicks
		m_vars[i] = m_C;
		m_vars[i+1] = m_A;
		m_vars[i+2] = m_E;
		m_cFactor = new TableFactor(m_vars);
		
		//E factor
		if (m_pos==1){//first position must be examined
			m_eFactor = new TableFactor(m_E, new double[]{0, 1});
		} else {
			Vector<URL> urllist = query.m_urls;
			m_vars[i] = urllist.get(m_pos-2).m_E;//Ep
			m_vars[i+1] = urllist.get(m_pos-2).m_A;//Ap
			//vars[i+2] = m_E;
			m_eFactor = new TableFactor(m_vars);
		}
		
		if (m_pos>1)
			fillinFactorTables(query, model);
		else
			fillinFirstPosition(query, model);
	}
	//
	public void createConstrains(Vector<URL> urls){
		testPcon(urls);
		if (m_pCon){
			URL pURL = urls.get(m_pos-2);
			m_constrains = new LogTableFactor[]{
					new LogTableFactor(new Variable[]{m_A, m_E}), //for A-C and A constrain
					new LogTableFactor(new Variable[]{m_A, m_E, pURL.m_A})  //for pairwise constrain
				}; 
		} else {
			m_constrains = new LogTableFactor[]{
					new LogTableFactor(new Variable[]{m_A, m_E}), //for A-C and A constrain
				}; 
		}
		updateConstrains(urls, null);//no effect for the first time of inferring P(Z|X)
	}
	
	private void testPcon(Vector<URL> urls){
		if (m_pos==1)
			m_pCon = false;
		else{
			//previous url
			URL pURL = urls.get(m_pos-2);
			//boolean variable indicating: skip next or skip above
			m_pCon = (m_c != pURL.m_c);
		}
	}
	
	private double getAdiff(URL pURL){//this - previous
		return Math.abs(m_aFactor.value(1) - pURL.m_aFactor.value(1));
	}
	
	public void updateConstrains(Vector<URL> urls, double[] weight){
		if (ClickModel.constrain_size==0 || MatrixOps.sum(ClickModel.m_constrain_mark)==0)
			return ;//no active constrains
		
		double[] lambda;
		if (weight==null)
			lambda = new double[ClickModel.constrain_size];//default: all zero
		else
			lambda = weight;
		
		Assignment assign;
		double value;
		
		//for A-C and A constrains
		AssignmentIterator aIter = m_cFactor.assignmentIterator(); //(vA, vE)
		while(aIter.hasNext()){
			assign = aIter.assignment();
			if (assign.get(m_E)==0)
				value = 0;
			else {//the factor is -\lambda\phi(X,Y)
				//for A-C constrain
				if (assign.get(m_A)==m_c)
					value = lambda[0];
				else
					value = -ClickModel.c_weight*lambda[0];			
				
				//for A constrain
				if (assign.get(m_A)==1)
					value += lambda[1];
			}
			m_constrains[0].setLogValue(assign, value);
			aIter.advance();
		}
		
		//for pairwise constrain
		if (m_pCon){
			URL pURL = urls.get(m_pos-2);
			Variable pA = pURL.m_A;
			aIter = m_constrains[1].assignmentIterator();
			while(aIter.hasNext()){
				assign = aIter.assignment();
				//loose setting
//				if (assign.get(m_E)==0 || assign.get(m_A)==assign.get(pA))
//					m_constrains[2].setLogValue(assign, 0);
//				else if (assign.get(m_A)==c && assign.get(pA)==(1-c))
//					m_constrains[2].setLogValue(assign, lambda[2]*getAdiff(pURL));
//				else
//					m_constrains[2].setLogValue(assign, -lambda[2]*getAdiff(pURL));	
				
				//strict setting
				if (assign.get(m_E)==0 ||
					(assign.get(m_A)==m_c && assign.get(pA)==(1-m_c)) )
					value = 0;
				else
					value = -lambda[2]*getAdiff(pURL);//
				m_constrains[1].setLogValue(assign, value);
				aIter.advance();
			}
		}
	}
	
	public void con4C(Factor ptf, double[] g){//PR on C clique
		int c = m_click>0?1:0;
		AssignmentIterator aIter = m_cFactor.assignmentIterator();
		Assignment assign;
		double p;
		while(aIter.hasNext()){
			assign = aIter.assignment();
			if (assign.get(m_E)==1){
				p = ptf.value(assign);
				if (Double.isNaN(p)){
					System.err.println("[Error]Encounter NaN for " + ptf + " in con4C()!");
					return;
				}
					
				//A-C constrain
				if (ClickModel.m_constrain_mark[0]==1){
					if (assign.get(m_A)==c)
						g[0] -= p;
					else
						g[0] += ClickModel.c_weight*p;
				}
				
				//C constrain
				if (ClickModel.m_constrain_mark[1]==1){
					if (assign.get(m_A)==1)
						g[1] -= p;
				}
			}
			aIter.advance();
		}
	}
	
	public void con4P(Vector<URL> urls, Factor ptf, double[] g){
		URL pURL = urls.get(m_pos-2);
		Variable pA = pURL.m_A;
		AssignmentIterator aIter = m_constrains[1].assignmentIterator();
		Assignment assign;
		int c = m_click>0?1:0;
		double p;
		while(aIter.hasNext()){
			assign = aIter.assignment();
//			if (assign.get(m_E)==1 && assign.get(m_A)!=assign.get(pA)){
//				if (assign.get(m_A)==c && assign.get(pA)==(1-c))
//					g[2] += ptf.value(assign)*getAdiff(pURL); 
//				else
//					g[2] -= ptf.value(assign)*getAdiff(pURL); 
//			}
			
			if (assign.get(m_E)==1 && (assign.get(m_A)!=c || assign.get(pA)!=1-c)){
				p = ptf.value(assign);
				if (Double.isNaN(p))
					System.err.println("[Error]Encounter NaN for " + ptf + " in con4P()");
				else
					g[2] += p * getAdiff(pURL);
			}
			aIter.advance();
		}
	}
	
	public void stat4A(Query query, ClickModel model){//pft is the joint posterior for (A_i,E_i)
		AssignmentIterator assign = m_postcFactor.assignmentIterator();
		Assignment assignment;
		double q, p = m_aFactor.value(1), diff = 0;//prior
		int i, a, pos = model.getRelevanceIndexInTable(query.m_query, m_URL);
		
		while(assign.hasNext()){
			assignment = assign.assignment();
			if (assignment.get(m_E)==1){//posterior	
				q = m_postcFactor.value(assign);
				if (Double.isNaN(q)){
					System.err.println("Encounter NaN for " + m_postcFactor + " in stat4A() of " + this);
					return;
				}
				a = assignment.get(m_A);	
				diff += q * (a-p);
			}
			assign.advance();
		}
		
		for(i=0; i<m_A_fea.length; i++)
			model.m_a_suffstat[i] += diff*m_A_fea[i];
		
		if (model.m_useLocalR && pos>=0)
			model.m_rStat[pos] += diff*ClickModel.uq_weight;
	}
	
	public void diag4A(Query query, ClickModel model){//pft is the joint posterior for (A_i,E_i)
		AssignmentIterator assign = m_postcFactor.assignmentIterator();
		Assignment assignment;
		
		double q, pE=0, p = m_aFactor.value(1);//prior
		int pos = model.getRelevanceIndexInTable(query.m_query, m_URL);
		
		while(assign.hasNext()){
			assignment = assign.assignment();
			if (assignment.get(m_E)==1){//posterior	
				q = m_postcFactor.value(assign);
				if (Double.isNaN(q)){
					System.err.println("Encounter NaN for " + m_postcFactor + " in stat4A() of " + this);
					return;
				}
				pE += q;
			}
			assign.advance();
		}
		
		double diff = pE * p * (1-p);
		for(int i=0; i<m_A_fea.length; i++)
			model.m_a_suffstat[i] += diff*m_A_fea[i]*m_A_fea[i];
		
		if (model.m_useLocalR && pos>=0)
			model.m_rStat[pos] += diff*ClickModel.uq_weight*ClickModel.uq_weight;
	}
	
	public void stat4C(ClickModel model){
		AssignmentIterator assign = m_cFactor.assignmentIterator();
		Assignment assignment;
		double q, p;
		double[] diff = {0, 0};
		int c = m_c>0?1:0, a;
		while(assign.hasNext()){
			assignment = assign.assignment();
			q = m_postcFactor.value(assign);//posterior
			if (Double.isNaN(q)){
				System.err.println("Encounter NaN for " + m_postcFactor + " in stat4A() of " + this);
				System.exit(-1);
			}
			else if (assignment.get(m_E)==1 && q>0){								
				p = m_cFactor.value(assignment);
				a = assignment.get(m_A);
				if (c==1)
					diff[a] = q * (1-p);
				else
					diff[a] = -q * (1-p);
			}
			assign.advance();
		}
		
		for(int i=0; i<m_C_fea.length; i++){
			model.m_c_suffstat[0][i] += diff[0]*m_C_fea[i];//when A=0
			model.m_c_suffstat[1][i] += diff[1]*m_C_fea[i];//when A=1
		}
	}
	
	public void diag4C(ClickModel model){
		AssignmentIterator assign = m_cFactor.assignmentIterator();
		Assignment assignment;
		double q, p;
		double[] diff = {0, 0};
		int a;
		while(assign.hasNext()){
			assignment = assign.assignment();
			q = m_postcFactor.value(assign);//posterior
			if (Double.isNaN(q)){
				System.err.println("Encounter NaN for " + m_postcFactor + " in stat4A() of " + this);
				System.exit(-1);
			}
			else if (assignment.get(m_E)==1 && q>0){								
				p = m_cFactor.value(assignment);
				a = assignment.get(m_A);
				diff[a] = q*p*(1-p);				
			}
			assign.advance();
		}
		
		for(int i=0; i<m_C_fea.length; i++){
			model.m_c_suffstat[0][i] += diff[0]*m_C_fea[i]*m_C_fea[i];//when A=0
			model.m_c_suffstat[1][i] += diff[1]*m_C_fea[i]*m_C_fea[i];//when A=1
		}
	}
	
	public void stat4E(Vector<URL> urllist, ClickModel model){
		if (m_pos==1)
			return ;//no contribution for the likelihood
		
		AssignmentIterator assign = m_eFactor.assignmentIterator();
		Assignment assignment;
		Variable Ep = urllist.get(m_pos-2).m_E, Ap = urllist.get(m_pos-2).m_A;//previous examine and attractiveness status
		
		double q, p;
		double[] diff = {0, 0};
		int a;
		while(assign.hasNext()){
			assignment = assign.assignment();
			q = m_posteFactor.value(assignment);//posterior
			if (Double.isNaN(q)){
				System.err.println("Encounter NaN for " + m_posteFactor + " in stat4A() of " + this);
				System.exit(-1);
			}
			else if (assignment.get(Ep)==1 && q>0){				
				p = m_eFactor.value(assignment);				
				a = assignment.get(Ap);
				if (assignment.get(m_E)==1)
					diff[a] += q*(1-p);
				else
					diff[a] -= q*(1-p);
			}
			assign.advance();
		}
		
		for(int i=0; i<m_E_fea.length; i++){
			model.m_e_suffstat[0][i] += diff[0]*m_E_fea[i];
			model.m_e_suffstat[1][i] += diff[1]*m_E_fea[i];
		}
	}
	
	public void diag4E(Vector<URL> urllist, ClickModel model){
		if (m_pos==1)
			return ;//no contribution for the likelihood
		
		AssignmentIterator assign = m_eFactor.assignmentIterator();
		Assignment assignment;
		Variable Ep = urllist.get(m_pos-2).m_E, Ap = urllist.get(m_pos-2).m_A;//previous examine and attractiveness status
		
		double q, p;
		double[] diff = {0, 0};
		int a;
		while(assign.hasNext()){
			assignment = assign.assignment();
			q = m_posteFactor.value(assignment);//posterior
			if (Double.isNaN(q)){
				System.err.println("Encounter NaN for " + m_posteFactor + " in stat4A() of " + this);
				System.exit(-1);
			}
			else if (assignment.get(Ep)==1 && q>0){				
				p = m_eFactor.value(assignment);				
				a = assignment.get(Ap);
				diff[a] += q*p*(1-p);
			}
			assign.advance();
		}
		
		for(int i=0; i<m_E_fea.length; i++){
			model.m_e_suffstat[0][i] += diff[0]*m_E_fea[i]*m_E_fea[i];
			model.m_e_suffstat[1][i] += diff[1]*m_E_fea[i]*m_E_fea[i];
		}
	}
	
	//codes for sampling purpose
	public int sample4A(double rnd){
		if (rnd<m_aFactor.value(0))
			return 0;
		else 
			return 1;
	}
	
	public int sample4C(double rnd, Assignment assign){
		if (rnd<m_cFactor.value(assign)){
			if (m_click>0)
				return 1;
			else 
				return 0;
		}
		else{
			if (m_click>0)
				return 0;
			else 
				return 1;
		}
	}
	
	public int sample4E(double rnd, Assignment assign){
		if (rnd<m_eFactor.value(assign))
			return 0;
		else
			return 1;
	}
}
