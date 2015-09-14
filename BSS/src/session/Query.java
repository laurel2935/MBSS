/**
 * 
 */
package session;

import gLearner.ClickModel;

import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Vector;

import optimizer.ProjectedGradientValue;
import optimizer.ProjectedLBFGS;
import analyzer.Analyzer;
import cc.mallet.grmm.inference.Inferencer;
import cc.mallet.grmm.types.Factor;
import cc.mallet.grmm.types.FactorGraph;
import cc.mallet.types.MatrixOps;

/**
 * @author czhai
 * basic structure for query: a list of URLs
 */
public class Query implements ProjectedGradientValue, Comparable<Query> {
	public String m_query;//unprocessed raw queries
	public int m_date;
	public long m_timestamp;//UTC timestamp
	public Vector<URL> m_urls;//a ranked list of URLs under this query
	
	//
	public FactorGraph m_fGraph;//factor graph for all the URLs in this query
	
	boolean m_update, m_gUpdate;
	double[] m_x, m_Xold, m_gOld;
	double m_exp_sum, m_exp_old;	

	ProjectedLBFGS m_opt;// = new ProjectedLBFGS(this);
	Inferencer m_infer;
	
	private int m_clickSize;
	
	static final Comparator<URL> URL_RANKER = 
            new Comparator<URL>() {
				@Override
				public int compare(URL u1, URL u2) {
					if (u1.m_pClick>u2.m_pClick)
						return -1;
					else if (u1.m_pClick<u2.m_pClick)
						return 1;
					else
						return 0;
				}
			};
	
	
	public Query(String query, int date, long timestamp){
		m_query = query.toLowerCase();
		m_date = date;
		m_timestamp = timestamp;
		m_urls = new Vector<URL>();
		m_fGraph = null;
		
		m_x = null;
		m_Xold = null;
		m_gOld = null;
		
		m_opt = null;
		m_clickSize = -1;
	}
	
	private void init_opt(ProjectedLBFGS opt){
		if (m_Xold==null && ClickModel.constrain_size>0){
			m_x = new double[ClickModel.constrain_size];
			m_Xold = new double[ClickModel.constrain_size];
			m_gOld = new double[ClickModel.constrain_size];
		}
		
		m_exp_old = Double.NEGATIVE_INFINITY;
		m_update = true;
		m_gUpdate = true;
		
		m_opt = opt;
		m_opt.setObj(this);
	}
	
	public void addURL(URL u){
		m_urls.add(u);
	}
	
	public String toString(){
		return m_timestamp + "\t" + m_query;
	}
	
	public void addFactors(URL u){
		m_fGraph.addFactor(u.m_aFactor);
		m_fGraph.addFactor(u.m_eFactor);
		m_fGraph.addFactor(u.m_cFactor);
		if (ClickModel.constrain_size>0 && u.m_constrains!=null){
			for(int i=0; i<u.m_constrains.length; i++){
				if (ClickModel.m_constrain_mark[i]==1)//enable/disable constrain factors
					m_fGraph.addFactor(u.m_constrains[i]);
			}
		}
	}
	
	//test the effect of original order!!!
	public void sort_urls(){
		Collections.sort(m_urls);//according to the position
		//for(int i=0; i<m_urls.size(); i++)
		//	m_urls.get(i).m_pos = i+1;
	}
	
	public void shrink_mem(boolean full){
		for(URL u:m_urls){
			u.m_features = null;
			u.m_content = null;
			u.m_vect = null;
			if (full)
				u.m_URL = null;
		}
		if (full)
			m_query = null;
	}
	
	public String getLambda(){
		if (ClickModel.constrain_size>0 && m_x!=null){
			String result = "(" + m_x[0];
			for(int i=1; i<m_x.length; i++)
				result += ", " + m_x[i];
			return result + ")";
		}
		else
			return "";
	}
	
	public boolean isLegal(){
		if (m_urls.size()<Analyzer.URL_CUT || m_urls.get(0).m_pos!=1)
			return false;
		
		int clickSize = m_urls.get(0).m_click>0?1:0;
		for(int i=1; i<m_urls.size(); i++){
			if (m_urls.get(i).m_pos-m_urls.get(i-1).m_pos != 1)
				return false;
			if (m_urls.get(i).m_click>0)
				clickSize++;
		}
		return clickSize>0;
	}
	
	public void resetClickSize(){
		m_clickSize = -1;
	}
	
	public int clickSize(){
		if (m_clickSize!=-1)
			return m_clickSize;
		
		m_clickSize = 0;
		for(URL u:m_urls)
			if (u.m_click>0)
				m_clickSize ++;
		return m_clickSize;
	}
	
	public int getLastClick(){
		int pos = m_urls.size()-1;
		while(pos>=0){
			if (m_urls.get(pos).m_click>0)
				break;
			pos--;
		}
		return pos;
	}
	
	public int getQueryLength(){
		URL u = m_urls.firstElement();
		return (int)u.m_features[95];
	}
	
	public double AP(int k){//calculate AP@k
		double AP = 0, P = 0;
		Collections.sort(m_urls, URL_RANKER);
		if (k<0)
			k = m_urls.size();
		else
			k = Math.min(k, m_urls.size());
		
		for(int i=0; i<k; i++){
			if ( m_urls.get(i).m_c>0 ){
				P++;
				AP += P/(i+1);
			}
		}
		Collections.sort(m_urls);
		return P>0?AP/P:0.0;
	}
	
	public void calc_posterior(Inferencer infer, ProjectedLBFGS opt, int iter){
		//
		if (ClickModel.constrain_size==0 || iter==0 || MatrixOps.sum(ClickModel.m_constrain_mark)==0)
			infer.computeMarginals(m_fGraph);
		else{
			m_infer = infer;
			
			init_opt(opt);
			try{
				m_opt.optimize(iter);//find the optimal posterior \lambda
			} catch (Exception e) {
				System.err.println("[Error]Restore to previous local maximal " + m_exp_old);
				System.arraycopy(m_Xold, 0, m_x, 0, m_x.length);
			} finally {
				m_opt.reset();
			}
		}
	}
		
	private void updateQueryGraph(){
		for(URL u:m_urls)
			u.updateConstrains(m_urls, m_x);
		
		m_exp_sum = calcCon();
		if (m_exp_sum>m_exp_old){
			m_exp_old = m_exp_sum;
			System.arraycopy(m_x, 0, m_Xold, 0, m_x.length);
		}
		m_update = false;
	}
	
	private double calcCon(){
		double v = m_fGraph.sum();
		if (v>0)
			v = -Math.log(v);
		v += -MatrixOps.dotProduct(m_x, ClickModel.m_constrain_value) - ClickModel.m_epsilon*MatrixOps.twoNorm(m_x);
		return v;
	}
	
	private void projection(double[] x){
		for(int i=0; i<x.length; i++)
			if (x[i]<0)
				x[i] = 0;
	}

	@Override
	public boolean testDirection(double[] g) {
		if (MatrixOps.twoNorm(g)==0)
			return false;//search direction is zero
		
		for(int i=0; i<g.length; i++){
			if ( !(m_x[i]<=0 && g[i]<=0) )
				return true;
		}
		return false;
	}

	@Override
	public double getValue() {
		if (m_update)
			updateQueryGraph();
		return m_exp_sum;
	}

	@Override
	public void getValueGradient(double[] g) {
		if (m_gUpdate==false){
			System.arraycopy(m_gOld, 0, g, 0, g.length);
			return;
		}
		
		if (m_update)
			updateQueryGraph();
		Arrays.fill(g, 0);
		
		m_infer.computeMarginals(m_fGraph);
		Factor q;
		for(URL url:m_urls){//q(Y) * \phi(X,Y) factorize accordingly
			q = m_infer.lookupMarginal(url.m_cFactor.varSet());
			url.con4C(q, g);
			if (ClickModel.m_constrain_mark[2]==1 && url.m_pCon){//pairwise constrain
				q = m_infer.lookupMarginal(url.m_constrains[1].varSet());
				url.con4P(m_urls, q, g);
			}
		}
		
		double L2 = MatrixOps.twoNorm(m_x);
		for(int i=0; i<g.length; i++){
			g[i] = -ClickModel.m_constrain_value[i] + g[i];//-b + E_q[\phi(X,Y)]
			if (L2>0 && m_x[i]>0)
				g[i] -= ClickModel.m_epsilon*m_x[i]/L2;
		}
		
		m_gUpdate = false;
		System.arraycopy(g, 0, m_gOld, 0, g.length);
	}

	@Override
	public int getNumParameters() {
		return m_x.length;
	}

	@Override
	public double getParameter(int i) {
		return m_x[i];
	}

	@Override
	public void getParameters(double[] x) {
		System.arraycopy(m_x, 0, x, 0, x.length);
	}

	@Override
	public void setParameter(int i, double v) {
		m_x[i] = v>0?v:0;
		m_update = true;
		m_gUpdate = true;
	}

	@Override
	public void setParameters(double[] x) {
		System.arraycopy(x, 0, m_x, 0, x.length);
		projection(m_x);
		m_update = true;
		m_gUpdate = true;
	}

	@Override
	public int compareTo(Query q) {
		if (q.m_timestamp < m_timestamp)
			return 1;
		else if (q.m_timestamp > m_timestamp)
			return -1;
		else 
			return 0;
	}
}
