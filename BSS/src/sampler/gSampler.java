package sampler;

import gLearner.GraphLearner;

import java.util.Random;

import session.Query;
import session.URL;
import session.User;
import cc.mallet.grmm.types.Assignment;
import cc.mallet.grmm.types.Variable;

/**
 * 
 * @author hongning
 * Samling the clicks by the given model, for sanity check purpose
 */
public class gSampler extends GraphLearner {
	
	double[] m_ctr;
	Random m_rand;
	
	public gSampler(double lambda, boolean scale, int reserve){
		super(lambda, scale, reserve);
		m_ctr = new double[10];
		m_rand = new Random();
	}
	
	private void sample4query(Query query){
		Assignment assign_c, assign_e;
		int a, e=1, aP=0, eP=0, c;
		Variable Ap = null, Ep = null;
		
		for(int i=0; i<query.m_urls.size(); i++){
			URL url = query.m_urls.get(i);
			//sample E
			if (i>0){
				assign_e = new Assignment(new Variable[]{url.m_E, Ep, Ap}, new int[]{0, eP, aP});
				e = url.sample4E(m_rand.nextDouble(), assign_e);
			}
			
			//sample A
			a = url.sample4A(m_rand.nextDouble());
			
			//sample C
			assign_c = new Assignment(new Variable[]{url.m_A, url.m_E}, new int[]{a, e});
			c = url.sample4C(m_rand.nextDouble(), assign_c);
			m_ctr[i] += c;
			
			//remember the state
			aP = a; 			
			Ap = url.m_A;
			eP = e;
			Ep = url.m_E;
			
			//store the sample result
			url.m_click = c;
		}
	}
	
	public void doSampling(){
		for(User user:m_userlist){//later each user would have a ClickModel
			for(Query q:user.m_queries){
				for(URL u:q.m_urls)
					u.updateFactors(q, m_model);
				sample4query(q);
				
				//update the factors according to new data
				for(URL u:q.m_urls)
					u.updateFactors(q, m_model);
			}
		}
		m_model = null;
		
		for(double v:m_ctr)
			System.out.println(v/m_graphsize);
	}
	
	public static void main(String[] args) {
		gSampler sampler = new gSampler(0.1, true, 0);
		
		sampler.LoadModel("Data/Models/model_manual.dat");
		sampler.LoadLogs("Data/Logs/urls.dat", true);
		sampler.doSampling();
		
		sampler.doTrain(15, 1e-3);
		sampler.SaveModel("Data/Models/model_recover.dat");
		
		sampler.doTest("Data/Models/click_sampler", false);
	}

}
