package optimizer;

import java.util.Arrays;

/**
 * 
 * @author Hongning Wang
 * Test the project gradient algorithm by -a(x-b)^2 + c
 */
public class TestCase implements ProjectedGradientValue{

	double[] m_a, m_b, m_c;
	double[] m_x;
	
	ProjectedLBFGS m_opt;
	
	public TestCase(double[] a, double[] b, double[] c){
		m_a = a;
		m_b = b;
		m_c = c;
		m_x = new double[a.length];
		Arrays.fill(m_x, 10);//initial guess
	}
	
	public String getSolution(double[] x){
		String solution = "(";
		for(int i=0; i<x.length; i++){
			if (i<x.length-1)
				solution += x[i] + ", ";
			else
				solution += x[i] + ")";
		}
		return solution;
	}
	
	@Override
	public boolean testDirection(double[] g) {
		for(int i=0; i<g.length; i++){
			if ( !(m_x[i]<=0 && g[i]<0) )
				return true;
		}
		return false;
	}
	
	@Override
	public double getValue() {
		double v = 0;
		for(int i=0; i<m_x.length; i++)
			v += m_a[i] * (m_x[i]-m_b[i]) * (m_x[i]-m_b[i]) + m_c[i];
		System.out.println("[Info]Object value " + v + " for x=" + getSolution(m_x));
		return v;
	}

	@Override
	public void getValueGradient(double[] g) {
		for(int i=0; i<m_x.length; i++)
			g[i] = 2*m_a[i]*(m_x[i]-m_b[i]);
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
		System.arraycopy(m_x, 0, x, 0, m_x.length);
	}

	@Override
	public void setParameter(int i, double x) {
		m_x[i] = x;
	}

	@Override
	public void setParameters(double[] x) {
		doProjection(x);
		System.arraycopy(x, 0, m_x, 0, x.length);
	}
	
	private void doProjection(double[] x){
		for(int i=0; i<m_x.length; i++)
			if (x[i]<0)
				x[i] = 0;
	}
	
	public void optimize(int iter, double tol){
		m_opt = new ProjectedLBFGS(this);
		m_opt.setTolerance(tol);
		//m_opt.setLineOptimizer(new ProjectedLinearSearch(this));
		
		if (m_opt.optimize(iter)==false){
			System.err.println("[Error]Optimization failed!");
			return;
		}
		else{
			System.out.println("[Info]Optimization succeed with object " + getValue() + "!");
		}
	}

	static public void main(String[] args){
		TestCase testing = new TestCase(new double[]{-1, -1}, new double[]{1, 2}, new double[]{3, 4});
		testing.optimize(10, 1e-3);
		//testing.pGradient(100, 1e-3);
	}
}
