package gLearner;

import gLearner.ClickModel.ModelStatus;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.Vector;

import optimizer.LBFGS;
import optimizer.LBFGS.ExceptionWithIflag;
import optimizer.ProjectedLBFGS;
import session.Query;
import session.URL;
import session.User;
import analyzer.Analyzer;
import analyzer.Evaluation;
import cc.mallet.grmm.inference.Inferencer;
import cc.mallet.grmm.inference.JunctionTreeInferencer;
import cc.mallet.grmm.types.Assignment;
import cc.mallet.grmm.types.AssignmentIterator;
import cc.mallet.grmm.types.Factor;
import cc.mallet.grmm.types.FactorGraph;
import cc.mallet.grmm.types.TableFactor;
import cc.mallet.grmm.types.Variable;
import cc.mallet.optimize.Optimizable.ByGradientValue;
import cc.mallet.types.MatrixOps;

/**
 * 
 * @author hongning
 * Implemetnation of BSS model
 */
public class GraphLearner extends Analyzer implements ByGradientValue, Evaluation {
	
	class Step{
		public double m_likellihood;
		public String m_performance;
		
		public Step(double likelihood, String performance){
			m_likellihood = likelihood;
			m_performance = performance;
		}
		
		public String toString(){
			return m_likellihood + "\t" + m_performance;
		}
	}
	
	double m_lambda;
	boolean m_scale;
	protected double m_graphsize;
	
	Inferencer m_infer, m_MAP_infer;
	ProjectedLBFGS m_project_opt;
	
	Vector<Step> m_trace;	
	
	double[] m_Xold;//for original problem	
	double m_exp_sum, m_exp_old;
	
	//? reserved queries
	protected int m_reserve;
	private boolean m_use_MAP = false;
	
	protected LBFGS m_opt;
	double m_A, m_C, m_E;
	
	public GraphLearner(double lambda, boolean scale, int reserve) {
		super(reserve);
		
		m_exp_sum = Double.NEGATIVE_INFINITY;
		m_lambda = lambda;
		m_scale = scale;
		m_graphsize = 0;
		m_infer = null;
		m_MAP_infer = null;
		
		m_Xold = null;
		
		m_model = new ClickModel(100.0);
		m_trace = null;
	}
	
	//initialize the optimizer
	public void init_opt(){
		if (m_Xold==null){
			if (m_model.m_useLocalR)
				m_model.init_residual(1);
			
			m_model.getWeights();
			m_Xold = new double[m_model.getDim()];			
			
			m_trace = new Vector<Step>();
			System.out.println("[Info]Total feature size " + m_model.getDim());
		}
		
		m_exp_old = Double.NEGATIVE_INFINITY;
	}
	
	public void setMAPoption(){
		m_use_MAP = true;
		System.err.println("[Warning]Switch to MAP inference!");
	}
	
	public void LoadLogs(String filename, boolean forTrain){
		LoadLogs(filename, forTrain, -1);
	}
	
	public void LoadLogs(String filename, boolean forTrain, int maxUser){
		m_graphsize = 0;
		super.LoadLogs(filename, maxUser);
		if (forTrain==true)
			buildupGraphs(m_model.m_useLocalR==false);//NOTE: cannot clean the URL contents if we want to use intrinsic relevance
		//in testing phase, we build up the graph on the fly
	}
	
	public void buildupGraphs(boolean clean){
		double diff;
		for(User user:m_userlist){//later each user would have a ClickModel
			for(Query q:user.m_queries){
				q.m_fGraph = new FactorGraph();
				for(URL u:q.m_urls){
					//
					u.createFactors(q);
					q.addFactors(u);
					//
					if (m_model.m_useLocalR){
						if (m_model.m_status == ModelStatus.ST_load)//the model is loaded from file (continue train)
							diff = Math.abs(u.m_c - getClickProb(q, u));//only keep the large residual terms
						else 
							diff = ClickModel.m_alpha;
						m_model.getRelevanceInTable(q.m_query, u.m_URL, diff, true); //register the (q,u) pair
					}
				}
				
				if(clean)
					q.shrink_mem(true);//done with this graph
				m_graphsize ++;
			}
		}
	}
	
	public void buildupAfeatures(Query q){
		for(URL u:q.m_urls){
			//u.normalize(m_x, m_xx);//normalize the features
			u.getAfeatures();//build up the relevance features (normalize and select proper set)
		}
	}
	
	//build up graph for MAP inference, problematic setting. IGNORE the implementation
	public void buildupGraphs4MAP(Query q){
		q.m_fGraph = new FactorGraph();
		for(URL u:q.m_urls){
			u.createFactors4MAP(q, m_model);
			q.addFactors(u);
		}	
	}
	
	//update the graph by the newly learned model
	private void updateGraphs(){
		m_model.setWeights(m_model.m_weights);
		for(User user:m_userlist){
			for(Query q:user.m_queries){
				for(URL u:q.m_urls){
					u.updateFactors(q, m_model);
				}
			}
		}
	}
	
	//E-step of EM
	private void calcPosterior(){
		for(User user:m_userlist){
			for(Query q:user.m_queries){				
				q.calc_posterior(m_infer, m_project_opt, 75);
				for(URL u:q.m_urls){
					//c
					u.m_postcFactor = m_infer.lookupMarginal(u.m_cFactor.varSet());
					if (u.m_pos>1){
						//e
						u.m_posteFactor = m_infer.lookupMarginal(u.m_eFactor.varSet());
					}						
				}
			}
		}
	}
	
	public void doTrain(int iter, double tol){
		init_opt();
		
		//different choice of inferencer
		m_infer = new JunctionTreeInferencer(); //new myTRP(); //new myLoopyBP(100);// 

		//projected gradient method for PR
		m_project_opt = new ProjectedLBFGS();
		m_project_opt.setTolerance(tol);
		
		//LBFGS for EM
		long stime = System.currentTimeMillis();
		try{			
			if (optimize(iter, tol)==false){
				System.err.println("[Error]Optimization failed!\n[Warning]Restore to previous local maximal " + m_exp_old);
				m_model.setWeights(m_Xold);
				m_model.m_status = ModelStatus.ST_error;
				return;
			}
			else{
				m_model.m_status = ModelStatus.ST_train;
				System.out.println("[Info]Optimization succeed with log-likelihood " + getValue() + "!");
			}
		} catch(Exception e){
			e.printStackTrace();
			System.err.println("[Warning]Restore to previous local maximal " + m_exp_old);
			m_model.setWeights(m_Xold);
		} finally {
			SaveTrace("trace_TRP.dat");
			System.out.println("[Info]Using " + (System.currentTimeMillis()-stime)/1000 + " seconds...");
		}
	}
	
	private void SaveTrace(String filename){
		if (m_trace.isEmpty())
			return;
		
		try {
			BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(filename)));
			for(Step s:m_trace)
				writer.write(s + "\n");
			writer.close();
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public void LoadModel(String filename){
		if (m_model==null)
			m_model = new ClickModel();
		
		try {
			BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(filename+".model")));
			String tmpTxt;
			m_model.m_dim = 0;
			while ((tmpTxt=reader.readLine()) != null){
				if (tmpTxt.startsWith("A_Factor")){
					tmpTxt=reader.readLine();
					m_model.set_a_weight(tmpTxt.trim().split("\t"));
				} 
				else if (tmpTxt.startsWith("C_Factor")){
					tmpTxt=reader.readLine().trim();
					String nextline = reader.readLine().trim();
					m_model.set_c_weight(new String[][]{tmpTxt.split("\t"), nextline.split("\t")});
				}
				else if (tmpTxt.startsWith("E_Factor")){
					tmpTxt=reader.readLine().trim();
					String nextline = reader.readLine().trim();
					m_model.set_e_weight(new String[][]{tmpTxt.split("\t"), nextline.split("\t")});
				}
			}
			m_model.m_status = ModelStatus.ST_load;
			reader.close();	
			
			//m_model.getWeights();//call before the relevance table is loaded, to save unnecessary memory allocation
			if (m_model.m_useLocalR && ClickModel.fileExists(filename+".table"))
				m_model.LoadRelevanceTable(filename+".table");	
			
			System.out.println("Model load from " + filename);
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	public void SaveModel(String filename){
		try {
			BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(filename+".model")));
			writer.write("A_Factor:\n");
			for(int i=0; i<m_model.m_a_weight.length; i++)
				writer.write("\t" + m_model.m_a_weight[i]);
			
			writer.write("\nC_Factor:\n");
			for(int i=0; i<m_model.m_c_weight.length; i++){
				for(int j=0; j<m_model.m_c_weight[i].length; j++)
					writer.write("\t" + m_model.m_c_weight[i][j]);
				writer.write("\n");
			}
			
			writer.write("E_Factor:\n");
			for(int i=0; i<m_model.m_e_weight.length; i++){
				for(int j=0; j<m_model.m_e_weight[i].length; j++)
					writer.write("\t" + m_model.m_e_weight[i][j]);
				writer.write("\n");
			}
			writer.close();
			
			if (m_model.m_useLocalR && m_model.m_rIndex.size()>0)
				m_model.SaveRelevanceTable(filename+".table");
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	@Override
	public int getNumParameters() {	
		return m_model.getDim();
	}

	@Override
	public double getParameter(int i) {
		if (m_model.m_weights==null)
			m_model.getWeights();
		return m_model.m_weights[i];
	}

	@Override
	public void getParameters(double[] w) {
		System.arraycopy(m_model.getWeights(), 0, w, 0, w.length);
	}

	@Override
	public void setParameter(int i, double v) {	
		m_model.m_weights[i] = v;
		m_model.setWeights(m_model.m_weights);
	}

	@Override
	public void setParameters(double[] w) {
		m_model.setWeights(w);
	}
	
	public void getDiagnoal(double[] diag, boolean updateG){
		m_model.stat_clear();

		for(User user:m_userlist){
			for(int i=0; i<user.m_queries.size()-m_reserve; i++){
				Query query = user.m_queries.get(i);	
				
				for(URL u:query.m_urls){
					//A factor
					u.diag4A(query, m_model);
					
					//C factor
					u.diag4C(m_model);
					
					//E factor
					u.diag4E(query.m_urls, m_model);
				}
			}
		}
		
		m_model.getStats(diag);
		for(int i=0; i<m_model.getDim(); i++){
			diag[i] += m_lambda;
			diag[i] = 1.0/diag[i];
		}
	}
	
	@Override
	public void getValueGradient(double[] g) {
		m_model.stat_clear();
		
		//Factor posterior;
		for(User user:m_userlist){
			for(int i=0; i<user.m_queries.size()-m_reserve; i++){
				Query query = user.m_queries.get(i);//the rest for testing purpose	
				for(URL u:query.m_urls){
					//A factor
					u.stat4A(query, m_model);
					
					//C factor
					u.stat4C(m_model);
					
					//E factor
					u.stat4E(query.m_urls, m_model);
				}
			}
		}
		
		m_model.getStats(g);
		//double norm = Math.sqrt(m_model.L2Norm(m_model.m_dim));//no reg over the residual terms
		//double norm = m_model.L2Norm(m_model.m_dim);//no reg over the residual terms
		for(int i=0; i<m_model.getDim(); i++){
			if (m_scale)// && i<m_model.m_dim
				g[i] /= m_graphsize;

//			if (norm>0 && i<m_model.m_dim)// 
//				g[i] -= m_lambda*m_model.m_weights[i]/norm;
//			if (norm>0 && i<m_model.m_dim)// 
//				g[i] -= m_lambda*m_model.m_weights[i];
//			if (norm>0)
				g[i] -= m_lambda*m_model.m_weights[i];
		}
	}
	
	protected double getExpectation(Factor p, Factor q, Variable vE){//p is the likelihood, q is the marginal posterior distribution
		AssignmentIterator assign = p.assignmentIterator();
		Assignment assignment;
		double exp_sum = 0, prob, v;
		while(assign.hasNext()){
			assignment = assign.assignment();
			if (assignment.get(vE) == 1){//only this configuration matters
				v = p.value(assign);
				prob = q.value(assign);//posterior	
				if (prob>0 && v>0 && v<1){//in case of log underflow
					exp_sum += prob * Math.log(v);
					if (Double.isNaN(exp_sum)){
						System.err.println("Encounter NaN for " + p + " in getExpectation()");
						return 0;
					}
				}
			}
			assign.advance();
		}
		return exp_sum;
	}
	
	protected double getExpectation4A(TableFactor p, Factor q, Variable vA, Variable vE){//p is the factor defined on A, q is the posterior distribution over (A,E)
		AssignmentIterator assign = q.assignmentIterator();
		Assignment assignment;
		double exp_sum = 0, prob, value;
		while(assign.hasNext()){
			assignment = assign.assignment();
			if (assignment.get(vE)==1){//only this configuration matters
				prob = q.value(assignment);//posterior probability
				value = p.value( assignment.get(vA) );
				if (prob>0 && value>0 && value<1){
					exp_sum += prob * Math.log(value);
					if (Double.isNaN(exp_sum)){
						System.err.println("Encounter NaN for " + p + " with f(a)=" + value + " and q(a)=" + prob + " in getExpectation4A()");
						return 0;
					}
				}
			}
			assign.advance();
		}
		return exp_sum;
	}
	
	private double calcExp(){
		m_A = 0;
		m_C = 0;
		m_E = 0;
		
		Variable pE = null;
		for(User user:m_userlist){
			for(int i=0; i<user.m_queries.size()-m_reserve; i++){
				Query query = user.m_queries.get(i);//the rest for testing purpose
				for(URL u:query.m_urls){
					//A factor
					m_A += getExpectation4A(u.m_aFactor, u.m_postcFactor, u.m_A, u.m_E);
					
					//C factor
					m_C += getExpectation(u.m_cFactor, u.m_postcFactor, u.m_E);
					
					//E factor
					if (u.m_pos>1)
						m_E += getExpectation(u.m_eFactor, u.m_posteFactor, pE);
					pE = u.m_E;
				}
			}
		}
		
		return m_A+m_C+m_E;
	}

	@Override
	public double getValue() {//calculate based on current graph setting
		//double reg = m_lambda * Math.sqrt(m_model.L2Norm(m_model.m_dim));// no regularization for the residual terms
		//double reg = m_lambda * Math.sqrt(m_model.L2Norm());// regularization for the residual terms
		double reg = 0.5 * m_lambda * m_model.L2Norm(m_model.m_dim);//no regularization for the residual terms
		if (m_scale)
			m_exp_sum = calcExp()/m_graphsize;
		else
			m_exp_sum = calcExp();
		m_exp_sum -= reg;
		
		if (m_exp_sum>m_exp_old){
			m_exp_old = m_exp_sum;
			System.arraycopy(m_model.m_weights, 0, m_Xold, 0, m_Xold.length);
		}		
			
		return m_exp_sum;
	}
	
	@Override
	public void initialize(){//used for testing phase
		super.initialize();
		if (m_infer==null && m_use_MAP==false){
			m_infer = new JunctionTreeInferencer();
			m_project_opt = new ProjectedLBFGS();
			m_project_opt.setTolerance(1e-4);
		}else if (m_use_MAP)
			m_MAP_infer = JunctionTreeInferencer.createForMaxProduct();
	}
	
	private boolean optimize(int maxIter, double converge) throws ExceptionWithIflag{
		//lbfgs ( int n , int m , double[] x , double f , double[] g , boolean diagco , double[] diag , int[] iprint , double eps , double xtol , int[] iflag )
		double f = 0, lastF = 1, rate;
		double[] g = new double[m_model.m_weights.length], diag = new double[m_model.m_weights.length];
		int[] iprint = {-1, 0}, iflag = {0};
		int iter = 0, miter = 0;
		m_opt = new LBFGS();
		//initialize the factors
		updateGraphs();
		
		System.out.println("Iteration\tE[logA]\tE[logC]\tE[logE]\tConverge");
		do{
			//e-step
			calcPosterior();
			
			//m-step
			miter = 0;
			iflag[0] = 0;
			do {
				if (iflag[0]==0 || iflag[0]==1){
					//function value based on the posterior graph inference! 
					f = getValue();
					getValueGradient(g);
					MatrixOps.timesEquals(g, -1);
				}
				
//				if (iflag[0]==0 || iflag[0]==2){//if we want to estimate the diagonals by ourselves
//					getDiagnoal(diag, iflag[0]==2);
//				}
				
				try{
					m_opt.lbfgs(m_model.m_weights.length, 4, m_model.m_weights, -f, g, false, diag, iprint, 1e-3, 1e-3, iflag);
					updateGraphs();//update factors
				} catch (ExceptionWithIflag ex){
					System.err.println("[Warning]M-step cannot proceed!");
				}
			}while(iflag[0]>0 && ++miter<40);
			
			//relative improvement for one EM iteration
			rate = (lastF - f)/lastF;
			lastF = f;
			
			m_trace.add(new Step(f, eval(false)));			
			
			System.out.println(iter+"\t" + m_A + "\t" + m_C + "\t" + m_E + "\t"+rate);
		}while(++iter<maxIter && (rate<0 || rate>converge));
		
		System.out.println("[Info]EM iteration converges to " + rate);
		return (rate>=0 && rate<=converge);
	}
		
	@Override
	public void doPredicton(Query q) {
		if (q.m_fGraph==null){
			if (m_use_MAP){
				buildupGraphs4MAP(q);
				m_MAP_infer.computeMarginals(q.m_fGraph);
			}
			else{
				buildupAfeatures(q);
			}
		} 
	}

	@Override
	public double getClickProb(Query q, URL u) {
		if (m_use_MAP){
			TableFactor map = (TableFactor)m_MAP_infer.lookupMarginal(u.m_C);
			return map.value(1);//draw from posterior
		}
		else
			return u.getAscore(q, m_model);//draw from prior
	}
	
//	public static void main(String[] args) {
//		GraphLearner gLearner = new GraphLearner(10.0, false, 0);
////		gLearner.LoadModel("Data/Models/model_click");
//		gLearner.LoadFeatureMeanSD("Data/Results/feature_stat_huge");
//		
//		gLearner.LoadLogs("Data/Logs/urls_huge_train.dat", true);
////		gLearner.LoadLogs("c:/Projects/Data/User Analysis/Heavy Users/Users/Content/Logs/urls_huge_train.dat", true, 1200);
//		gLearner.doTrain(50, 1e-4);
//		gLearner.SaveModel("Data/Models/model_click");
//		gLearner.doTest("Data/Results/Result_log_train", false);
//		
//		gLearner.LoadLogs("Data/Logs/urls_huge_test.dat", false);
////		gLearner.setMAPoption();
////		gLearner.LoadLogs("c:/Projects/Data/User Analysis/Heavy Users/Users/Content/Logs/urls_huge_test.dat", false);
//		gLearner.doTest("Data/Results/Result_log_test", false);
//		
//		gLearner.LoadLogs("Data/Bucket/urls.dat", false);
////		gLearner.LoadAnnotation("Data/Edit/click_quj");
////		gLearner.LoadLogs("c:/Projects/Data/User Analysis/Heavy Users/Users/Content/Bucket/urls.dat", false, 1000);
////		gLearner.LoadAnnotation("c:/Projects/Data/User Analysis/Heavy Users/Users/Content/Edit/click_quj");
//		gLearner.doTest("Data/Results/click_bucket", true);
//		
////		gLearner.setMAPoption();
////		gLearner.set4Pred();
////		gLearner.Save4MLR("Data/Vectors/logs_pred", "Data/Heads/fv_head.dat", true, false);
//	}
	
	public static void main(String[] args) {
		if (args[0].equals("ctrain") && args.length!=5){
			System.err.println("[Usage]ctrain fv_stat trainset maxUser model\n");
			return;
		} else if (args[0].equals("ctest") && args.length!=7) {
			System.err.println("[Usage]ctest fv_stat testset maxUser model result isBucket\n");
			return; 
		} else if (args[0].equals("cpred") && args.length!=8) {
			System.err.println("[Usage]cpred fv_stat testset maxUser model result isBucket asFeature\n");
			return;
		}
		
		GraphLearner gLearner = new GraphLearner(20.0, false, 0);
		if (args[1].equals("null")==false)
			gLearner.LoadFeatureMeanSD(args[1]);
		
		if (args[0].equals("ctrain")){
			gLearner.LoadLogs(args[2], true, Integer.valueOf(args[3]));
			gLearner.doTrain(40, 1e-4);
			gLearner.SaveModel(args[4]);
		} else if (args[0].equals("ctest")){
			gLearner.LoadModel(args[4]);
			gLearner.LoadLogs(args[2], false, Integer.valueOf(args[3]));
			gLearner.doTest(args[5], Boolean.valueOf(args[6]));
		} else if (args[0].equals("cpred")){
			gLearner.set4Pred(Boolean.valueOf(args[8]));
			gLearner.LoadModel(args[4]);
			gLearner.LoadLogs(args[2], false, Integer.valueOf(args[3]));
			gLearner.Save4MLR(args[5], Boolean.valueOf(args[6]));
		}
	}
}

