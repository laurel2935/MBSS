package optimizer;

import cc.mallet.optimize.Optimizable.ByGradientValue;

public interface ProjectedGradientValue extends ByGradientValue {
	public boolean testDirection (double[] g);
}
