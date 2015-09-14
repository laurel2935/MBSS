package gLearner;

import java.util.Arrays;

import optimizer.LBFGS;
import optimizer.LBFGS.ExceptionWithIflag;
import session.Query;
import session.URL;
import session.User;
import cc.mallet.types.MatrixOps;

public class LogisticRegression extends GraphLearner {
	//?
	double[] m_positions;
	
	public LogisticRegression(double lambda, boolean scale, int reserve){
		super(lambda, scale, reserve);
		m_positions = new double[10];
		Arrays.fill(m_positions, 1.0);//naive logistic regression
	}
	
	public void LoadLogs(String filename, boolean forTrain, int maxUser){
		m_graphsize = 0;
		super.LoadLogs(filename, maxUser);
		buildupGraphs(forTrain);
	}
	
	@Override
	public void LoadLogs(String filename, boolean forTrain){
		m_graphsize = 0;
		super.LoadLogs(filename, -1);
		buildupGraphs(forTrain);
	}
	
	private String getCTR(){
		String result = "(" + Double.toString(m_positions[0]);
		for(int i=1; i<m_positions.length; i++)
			result += ", " + Double.toString(m_positions[i]);
		return result + ")";
	}
	
	public void buildupGraphs(boolean forTrain){
		double[] pos_stat = null;
		
		if (forTrain)
			pos_stat = new double[m_positions.length];
		
		for(User user:m_userlist){//later each user would have a ClickModel
			for(Query q:user.m_queries){
				for(URL u:q.m_urls){
					if (forTrain){
						if (u.m_click>0)
							m_positions[u.m_pos-1]++;
						pos_stat[u.m_pos-1]++;
					}
				}
				buildupAfeatures(q);
				
				if(forTrain)
					q.shrink_mem(true);//done with this graph
				m_graphsize ++;
			}
		}

		if (forTrain){
			System.out.println("[Info]Logistic regression with position-bias");
			for(int i=0; i<pos_stat.length; i++)
				m_positions[i] /= pos_stat[i];
			System.out.println("[Info]CTR: " + getCTR());
		} else
			System.out.println("[Info]Logistic regression without position-bias");
	}
	
	private boolean optimize(int maxIter, double converge) throws ExceptionWithIflag{
		//lbfgs ( int n , int m , double[] x , double f , double[] g , boolean diagco , double[] diag , int[] iprint , double eps , double xtol , int[] iflag )
		double f = 0;
		double[] g = new double[m_model.m_dim], diag = new double[m_model.m_dim];
		int[] iprint = {0, 0}, iflag = {0};
		int iter = 0;
		m_opt = new LBFGS();
		do{
			if (iflag[0]==1 || iflag[0]==0){
				f = -getValue();
				getValueGradient(g);			
				MatrixOps.timesEquals(g, -1);	
			}
			
//			if (iflag[0]==2 || iflag[0]==0)//if we want to calculate the diagonals ourselves
//				getDiagnoal(diag);
			
			m_opt.lbfgs(m_model.m_dim, 5, m_model.m_weights, f, g, false, diag, iprint, converge, 1e-3, iflag);
			m_model.setWeights(m_model.m_weights);
		}while(iflag[0] > 0 && ++iter<maxIter);
		
		return iflag[0]==0;
	}
	
	public void doTrain(int iter, double tol){
		m_model = new ClickModel(50.0);
		try{
			init_opt();
			if (optimize(iter, tol)==false){
				System.err.println("[Error]Optimization failed!");
				return;
			}
			else
				System.out.println("[Info]Optimization succeed with log-likelihood " + getValue() + "!");
		} catch(Exception e){
			e.printStackTrace();
			System.err.println("Restore to previous local maximal " + m_exp_old);
			m_model.setWeights(m_Xold);
		}
	}
	
	public void getDiagnoal(double[] d){
		double p, beta;
		int lastPos;
		Arrays.fill(d, m_lambda);
		for(User user:m_userlist){
			for(int i=0; i<user.m_queries.size()-m_reserve; i++){
				Query query = user.m_queries.get(i);
				lastPos = query.getLastClick();
				for(URL u:query.m_urls){
					//beta = u.m_pos-1>lastPos?m_positions[u.m_pos-1]:1.0;
					beta = m_positions[u.m_pos-1];
					p = ClickModel.logistic(ClickModel.linear(u.m_A_fea, m_model.m_a_weight)) * beta;
					
					p *= (1.0-p);
					for(int n=0; n<u.m_A_fea.length; n++)
						d[n] += p * u.m_A_fea[n] * u.m_A_fea[n];
				}
			}
		}
		for(int i=0; i<d.length; i++)
			d[i] = 1.0/d[i];
	}
	
	public void getValueGradient(double[] g) {
		m_model.stat_clear();
		
		double p, beta;
		int lastPos;
		for(User user:m_userlist){
			for(int i=0; i<user.m_queries.size()-m_reserve; i++){
				Query query = user.m_queries.get(i);
				lastPos = query.getLastClick();
				for(URL u:query.m_urls){
					//beta = u.m_pos-1>lastPos?m_positions[u.m_pos-1]:1.0;
					beta = m_positions[u.m_pos-1];
					p = ClickModel.logistic(ClickModel.linear(u.m_A_fea, m_model.m_a_weight));
					
					if (u.m_c==1){
						p = (1.0-p);
						for(int n=0; n<u.m_A_fea.length; n++)
							m_model.m_a_suffstat[n] += p * u.m_A_fea[n];
					} else {
						p = (1.0-p)*p*beta/(1.0-p*beta);
						for(int n=0; n<u.m_A_fea.length; n++)
							m_model.m_a_suffstat[n] -= p * u.m_A_fea[n];
					}
				}
			}
		}
		
		m_model.getStats(g);
		for(int i=0; i<m_model.m_dim; i++){
			if (m_scale)
				g[i] /= m_graphsize;
			g[i] -= m_lambda * m_model.m_weights[i];
		}
	}
	
	public double getValue() {
		updateGraphs();
		return m_exp_sum;
	}
	
	private void updateGraphs(){
		double reg = 0.5 * m_lambda * m_model.L2Norm();; 
		if (m_scale)
			m_exp_sum = calcExp()/m_graphsize - reg;
		else
			m_exp_sum = calcExp() - reg;
		
		if (m_exp_sum>m_exp_old){
			m_exp_old = m_exp_sum;
			System.arraycopy(m_model.m_weights, 0, m_Xold, 0, m_model.m_dim);
		}
	}
	
	//based on the current parameter setting, calculate the click probability.
	//Further based on whether this url is actually clicked or not, calculate the loss (expected loss)
	private double calcExp(){
		double likelihood = 0, p, beta;
		int lastPos;
		for(User user:m_userlist){
			for(int i=0; i<user.m_queries.size()-m_reserve; i++){
				Query query = user.m_queries.get(i);
				lastPos = query.getLastClick();
				for(URL u:query.m_urls){
					//beta = u.m_pos-1>lastPos?m_positions[u.m_pos-1]:1.0;
					beta = m_positions[u.m_pos-1];
					p = ClickModel.logistic(ClickModel.linear(u.m_A_fea, m_model.m_a_weight)) * beta; 
					
					if (p<=0 || p>=1){
						System.err.println("[Error]Illegal probability for (q,u) " + query + "\t" + u);
						System.exit(-1);
					}
					
					if (u.m_c==1)
						likelihood += Math.log(p);
					else
						likelihood += Math.log(1-p);
				}
			}
		}
		return likelihood;
	}

	@Override
	public void doPredicton(Query q) {}

	@Override
	public double getClickProb(Query q, URL u) {
		return u.getAscore(q, m_model);
	}

	@Override
	public void initialize() {
		super.initialize();
	}
	
//	public static void main(String[] args) {
//		LogisticRegression rLearner = new LogisticRegression(0.01, true, 0);
////		rLearner.LoadModel("Data/Models/model_examine");
//		
//		rLearner.LoadLogs("Data/Logs/urls_huge_train.dat", true);
//		rLearner.doTrain(5000, 1e-4);
//		rLearner.SaveModel("Data/Models/model_logistic");
//		rLearner.doTest("Data/Results/logistic_train", false);
//		
//		rLearner.LoadLogs("Data/Logs/urls_huge_test.dat", false);
//		rLearner.doTest("Data/Results/logistic_test", false);
//		
//		rLearner.LoadLogs("Data/Bucket/urls.dat", false);
//		rLearner.doTest("Data/Results/logistic_bucket", true);
//	}
	
	public static void main(String[] args) {
		if (args[0].equals("ltrain") && args.length!=6){
			System.err.println("[Usage]ltrain fv_stat trainset maxUser model isExamine\n");
			return;
		} else if (args[0].equals("ltest") && args.length!=7) {
			System.err.println("[Usage]ltest fv_stat testset maxUser model result isBucket\n");
			return; 
		}
		
		LogisticRegression rLearner = new LogisticRegression(0.050, true, 0);
		
		if (args[1].equals("null")==false)
			rLearner.LoadFeatureMeanSD(args[1]);
		
		if (args[0].equals("ltrain")){
			rLearner.LoadLogs(args[2], Boolean.valueOf(args[5]), Integer.valueOf(args[3]));
			rLearner.doTrain(5000, 1e-4);
			rLearner.SaveModel(args[4]);
		} 
		else if (args[0].equals("ltest")){
			rLearner.LoadModel(args[4]);
			rLearner.LoadLogs(args[2], false, Integer.valueOf(args[3]));//keep the same size of training/testing cases
			rLearner.doTest(args[5], Boolean.valueOf(args[6]));
		}
	}
}
