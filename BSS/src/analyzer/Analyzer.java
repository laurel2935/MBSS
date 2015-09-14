/**
 * 
 */
package analyzer;

import gLearner.ClickModel;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Random;
import java.util.Vector;

import session.Query;
import session.URL;
import session.User;
import cc.mallet.types.Alphabet;

/**
 * @author wang296
 * basic interfaces for processing the log data
 */
public class Analyzer implements Evaluation, Comparator<URL> {
	static final public int QUERY_CUT = 1;
	static final public int URL_CUT = 3;
	
	protected HashMap<String, String> m_annotation;
	protected HashMap<String, Integer> m_grades;
	
	public Vector<User> m_userlist;
	Alphabet m_dict;
	public int m_reserve;//number of queries reserved not for testing
	protected ClickModel m_model;
	protected Random m_rand;

	private Vector<String> m_fv_heads;
	private boolean m_pred_click;
	private boolean m_asFeature;
	protected boolean m_test_init;
	
	public double[] m_x, m_xx;
	
	//basic structure for ranking the items 
	class _Item implements Comparable<_Item>{
		String m_name;
		int m_count;
		
		_Item(String name){
			m_name = name;
			m_count = 1;
		}

		@Override
		public int compareTo(_Item arg0) {
			return arg0.m_count - m_count;
		}
	}
	
	public Analyzer(int reserve){
		m_userlist = new Vector<User>();
		//
		m_dict = new Alphabet();//alphabet for all the contents from the log

		m_annotation = null;
		m_reserve = reserve;
		m_model = null;
		
		m_rand = new Random();
		
		m_fv_heads = null;
		m_pred_click = false;//by default, we will depend on the original clicks
		m_asFeature = false;//by default, we will not use the predicted click as feature
		m_test_init = false;
		
		m_x = null;
		m_xx = null;
	}
	
	public void LoadLogs(String filename){
		LoadLogs(filename, -1);
	}
	
	//loading fixed Yahoo! news search log format
	public void LoadLogs(String filename, int maxUser){
		if (m_userlist.isEmpty()==false)
			m_userlist.clear();
		
		try {
			BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(filename), "UTF-8"));
			String tmpTxt, userID="";
			String[] container, sub_container, termlist;
			User user = null;
			Query query = null;
			URL url = null;
			double age;
			int querySize = 0;
			
			while((tmpTxt=reader.readLine()) != null){
				container = tmpTxt.split("\t");//get query meta-data
				//userID, query, date, query timestamp and URL set
				if (container.length!=5)
					continue;				
				
				if (container[0].equals(userID)==false){//a new user
					if (user!=null && user.m_queries.size()>QUERY_CUT){
						//by querying timestamp
						user.sortQueries();
						m_userlist.add(user);
					}

					userID = container[0];
					user = new User(container[0]);
					
					if (maxUser!= -1 && m_userlist.size()>=maxUser)
						break;
				}
				
				try{
					query = new Query(container[1], Integer.valueOf(container[2]), Long.valueOf(container[3]));
					//get the URLs
					container = (container[4].substring(2, container[4].length()-4)).split("\\),\\(");
					//parse for each URL
					////Each URL inside the URL set is enclosed by a pair of parentheses, seperated by comma.
					////The first four columns for each URL is URL string, click timestamp, original display position,
					////unigram/bigram text content.
					////And the rests are the relevance-driven features.
					for(String u_text:container){
						sub_container = u_text.split(",\\(");//split into two parts
						if (sub_container.length>2){//with features (cf. readme for the format of a URL with features)
							//meta e.g., information of rank, click, urlString, etc.
							url = new URL(sub_container[0]);
							termlist = sub_container[1].split(",");
							age = Double.valueOf(termlist[1]);
							url.setContent(termlist[0].split(";"), m_dict); //gradually set up content feature vector
							url.setFeatures(sub_container[2].substring(0, sub_container[2].length()-1).split(","));
							url.setAge(age);
						} 
						query.addURL(url);
					}
					//by position
					query.sort_urls();//get the original ranking order
					
					if (user.addQuery(query))
						querySize ++;
				} catch(Exception ex){
					System.err.println("[Error]Wrong format for: " + query);
					System.exit(-1);
				}
			}
			if (user.m_queries.size()>QUERY_CUT)
				m_userlist.add(user);//the last user
			
			reader.close();			
			System.out.println("Load " + m_userlist.size() + " users with " + querySize + " sessions from " + filename);
			
			if (m_x!=null && m_xx!=null)
				normalize();
			
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	public int getFeatureSize(){
		URL u = m_userlist.firstElement().m_queries.firstElement().m_urls.firstElement();
		return u.getFeatureSize();
	}
	
	private int getRelFeatureSize(){
		Query q = m_userlist.firstElement().m_queries.firstElement();
		return q.m_urls.firstElement().getRelFeatureSize();
	}
	
	//load pre-calculated mean and standard deviation of features for normalization purpose
	public void LoadFeatureMeanSD(String filename){
		m_x = new double[113];
		m_xx = new double[113];
		
		try {
			BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(filename)));
			String tmpTxt;
			String[] container;
			int i = 0;
			while(i<m_x.length && (tmpTxt=reader.readLine())!=null){
				container = tmpTxt.split("\t");
				m_x[i] = Double.valueOf(container[0]);
				m_xx[i] = Double.valueOf(container[1]);
				i++;
			}
			reader.close();
			System.out.println("[Info]Load " + i + " feature statistics from " + filename);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	//estimating mean and standard deviation
	private double getFeatureMeanSD(double[] mean, double[] dev){
		double size = 0, value;
		double[] feature;
		for(User user:m_userlist){
			for(Query query:user.m_queries){
				for(URL url:query.m_urls){
					feature = url.m_features;//raw feature statistics
					for(int i=0; i<feature.length; i++){
						if (i==0)
							feature[0] = Math.log(Math.abs(feature[0]));//only take log for age feature
						value = feature[i];
						mean[i] += value;
						dev[i] += value * value;
					}
					size ++;
				}
			}
		}
		
		for(int i=0; i<mean.length; i++){
			mean[i] /= size;
			dev[i] = Math.sqrt(dev[i]/size - mean[i] * mean[i]);
		}
		return size;
	}
	
	public void getFeatureStats(String filename){
		double[] mean = new double[getRelFeatureSize()], dev = new double[getRelFeatureSize()];
		getFeatureMeanSD(mean, dev);
		
		try {
			BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(filename)));
			for(int i=0; i<mean.length; i++)
				writer.write(mean[i] + "\t" + dev[i] + "\n");
			writer.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	//estimate query statistics, frequency
	public void getQueryStats(String filename){
		HashMap<String, Integer> queryIndex = new HashMap<String, Integer>();
		Vector<_Item> queryList = new Vector<_Item>();
		
		for(User user:m_userlist){
			for(Query query:user.m_queries){
				if (queryIndex.containsKey(query.m_query)){
					queryList.get(queryIndex.get(query.m_query)).m_count ++;
				} else {
					queryIndex.put(query.m_query, Integer.valueOf(queryList.size()));
					queryList.add(new _Item(query.m_query));
				}
			}
		}
		
		Collections.sort(queryList);
		
		try {
			BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(filename)));
			for(_Item query:queryList)
				writer.write(query.m_name + "\t" + query.m_count + "\n");
			writer.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	public void doTest(String filename, boolean isBucket){
		initialize();
		
		Save4QujFormat(filename+".label", isBucket, true);//in order to use the testing script purpose
		try {
			BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(filename+".dat")));
			BufferedWriter score_writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(filename+".score")));
		
			String key;
			double A, P=0, N=0, perplexity=0, MAP = 0, Qsize = 0;
			int i, j, c;
			Query q;
			for(User user:m_userlist){//later each user would have a ClickModel
				if (user.m_model!=null)
					m_model = user.m_model;//for personalized model				
				
				for(i=(m_reserve==0?0:user.m_queries.size()-m_reserve); i<user.m_queries.size(); i++){
					q = user.m_queries.get(i);//the rest for testing purpose
					
					if (q.clickSize()==0)
						continue;//no need to evaluate in this session
					doPredicton(q);
					
					double maxA = 0, perp = 0;
					String grade = null;
					
					writer.write(user.m_ID + "\t" + q.m_query + "\n");
					for(j=0; j<q.m_urls.size(); j++){						
						URL u = q.m_urls.get(j);
						writer.write(u.m_c>0?"1":"0");
						
						//click probability
						A = getClickProb(q, u);
						writer.write("\t" + A);
						score_writer.write(A+"\n");
						
						key = q.m_query + "\t" + u.m_URL;
						if (m_annotation!=null && m_annotation.containsKey(key))
							writer.write("\t" + m_annotation.get(key) + "\t");
						else
							writer.write("\tUnknown\t");
						writer.write("\n");		
						
						if (maxA < A){//prefer top positions
							maxA = A;
							if (m_annotation!=null)
								grade = m_annotation.get(key);
							else if (u.m_c>0)
								grade = "1";
							else
								grade = "0";
						}
						
						N ++;//number of predictions
						perp += ClickModel.click_likelihood(A, u.m_c);
						
						if (isBucket && m_annotation==null && u.m_pos==4){
							j = 4;//correct the position after break
							break;//only test on the first four positions in random bucket 	
						}
					}
					perplexity += perp;
					MAP += q.AP(-1);
					Qsize ++;
					
					if ( grade!=null && ((m_grades!=null && m_grades.get(grade)>1) || grade.equals("1")) ){
						c = 1;
						P ++;
					}
					else
						c = 0;
					
					writer.write(j + "\t" + perp + "\t" + c + "\t" + q.clickSize() + "\n\n");
					q.m_fGraph = null;//release the memory
				}
			}
			
			writer.close();
			score_writer.close();
			
			perplexity = Math.pow(2.0, -perplexity/N); 
			System.out.println("P@1 " + P/Qsize + "\nMAP " + (MAP/Qsize) + "\nperplexity " + perplexity);
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	public String eval(boolean isBucket){
		String key;
		double A, P=0, T=0, N=0, perplexity=0, MAP=0;
		for(User user:m_userlist){
			for(int i=0; i<user.m_queries.size()-m_reserve; i++){
				Query q = user.m_queries.get(i);//the rest for testing purpose
				if (q.clickSize()==0)
					continue;
				
				double maxA = 0;
				int clickSize = 0;
				String grade = null;
				
				doPredicton(q);
				for(URL u:q.m_urls){
					A = getClickProb(q, u);
					
					if (maxA < A){
						maxA = A;
						if (m_annotation!=null){
							key = q.m_query + "\t" + u.m_URL;
							grade = m_annotation.get(key);
						}
						else if (u.m_click>0)
							grade = "1";
						else
							grade = "0";
					}
					
					if (u.m_click>0)
						clickSize++;
					
					N ++;//number of predictions
					perplexity += ClickModel.click_likelihood(A, u.m_c);
					
					if (isBucket && m_annotation==null && u.m_pos==4)
						break;//only test on the first four positions in random bucket 						
				}
				
				if (clickSize>0){
					MAP += q.AP(-1);
					T ++;//total query size
					if (m_grades!=null && m_grades.get(grade)>1)
						P ++;
					else if (grade.equals("1"))
						P ++;
				}
			}
		}
		
		perplexity = Math.pow(2.0, -perplexity/N); 
		return P/T + "\t" + MAP/T + "\t" + perplexity;
	}
	
	public void LoadAnnotation(String filename){
		try{
			BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(filename), "UTF-8"));
			m_annotation = new HashMap<String, String>();
			String tmpTxt;
			String[] container;
			while( (tmpTxt=reader.readLine()) != null){
				container = tmpTxt.split("\t");
				String key = container[1] + "\t" + container[2];
				m_annotation.put(key, container[3]);
			}
			reader.close();
			
			m_grades = new HashMap<String, Integer>();
			m_grades.put("Bad", 0);
			m_grades.put("Fair", 1);
			m_grades.put("Good", 2);
			m_grades.put("Excellent", 3);
			m_grades.put("Perfect", 4);
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	public void LoadFvHeads(String filename){
		if (m_fv_heads!=null)
			return;//head already loaded
		
		try{
			BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(filename)));
			m_fv_heads = new Vector<String>();
			String tmpTxt;
			while( (tmpTxt=reader.readLine())!=null ){
				m_fv_heads.add(tmpTxt.trim());
			}
			reader.close();
			System.out.println("[Info]Relevance feature size " + m_fv_heads.size());
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}	
	}
	
	public void setAsFeature(boolean asFeature){
		m_asFeature = asFeature;
	}
	
	public void set4Pred(boolean asFeature){
		m_pred_click = true;
		m_asFeature = asFeature;
		System.err.println("[Warning]Switch to prediction-based pairwise preferences!");
	}
	
	public void Save4MLR(String filename, boolean isBucket){
		Save4QujFormat(filename + ".label", isBucket, true);
		Save4SVM(filename + ".fv", isBucket);
	}
	
	public void Save4QujFormat(String filename, boolean isBucket, boolean isNumeric){//will do prediction for the URLs under each query  
		try {
			String key;
			
			BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(filename)));
			writer.write("group\tquery\turl\tgrade\n");
			
			if (m_pred_click && m_test_init==false)
				initialize();//no need to do it again for label generation
			
			for(User user:m_userlist){
				for(Query query:user.m_queries){
					if(m_pred_click){//get relevance status
						doPredicton(query);
						if (m_asFeature==false)
							setClicks(query);//use the predicted click as supervision
					}
					
					if (query.clickSize()==0)
						continue;
					
					for(URL url:query.m_urls){
						writer.write(query.m_timestamp + "\t" + query.m_query + "\t" + url.m_URL);
						key = query.m_query + "\t" + url.m_URL;
						if (isNumeric){
							int jug;
							if (m_annotation!=null) {
								if (m_annotation.containsKey(key))
									jug = m_grades.get(m_annotation.get(key));
								else
									jug = url.m_c;
							}
							else
								jug = url.m_click>0?1:0;
							writer.write("\t" + jug + "\n");
						} else {
							String jug;
							if (m_annotation!=null && m_annotation.containsKey(key))
								jug = m_annotation.get(key);
							else
								jug = (url.m_click>0?"Bad":"Good");
							writer.write("\t" + jug + "\n");
						}
						if (isBucket && url.m_pos==4)
							break;
					}
					
					if(m_pred_click && !isNumeric)
						query.m_fGraph = null;//release it anyway
				}
			}
			writer.close();
		} catch (IOException e) {
			e.printStackTrace();
		} 
	}
	
	//save for Yahoo!'s GBRank format
	public void Save4FvFormat(String filename, boolean isBucket){
		if (m_fv_heads==null){
			System.err.println("[Error]Load the fv heads first!");
			System.exit(-1);
		}
		
		int i;
		try {
			BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(filename)));
			for(i=0; i<m_fv_heads.size()-1; i++)//to use all the ranking features
				writer.write(m_fv_heads.get(i) + "\t");
			if (m_asFeature)
				writer.write("BSS\t");
			writer.write(m_fv_heads.get(i) + "\n");
			
			for(User user:m_userlist){
				for(Query query:user.m_queries){
					if ( query.clickSize()==0 ) 
						continue;
					
					for(URL url:query.m_urls){
						for(i=0; i<url.m_features.length-1; i++)
							writer.write(url.m_features[i] + "\t");
						if (m_asFeature)//click probability as feature 
							writer.write(getClickProb(query, url) + "\t");
						writer.write(url.m_features[i] + "\n");
						
						if (isBucket && url.m_pos==4)
							break;
					}
				}
			}
			writer.close();
		} catch (IOException e) {
			e.printStackTrace();
		} 
	}
	
	//save for RankSVM format (with pairwise preference generation)
	public void Save4SVM(String filename, boolean isBucket){
		try {
			BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(filename)));
			
			int qid = 0;
			Vector<URL> container = new Vector<URL>();
			for(User user:m_userlist){
				for(Query query:user.m_queries){
					if (query.m_urls.size()<2)
						continue;
					
					if (m_pred_click==false){
						if ( query.clickSize()==0 ) 
							continue;
						
						for(URL url:query.m_urls){
							Out2Fv(writer, query, url, qid, url.m_c, null);						
							if (isBucket && url.m_pos==4)
								break;
						}
					} else {//hack for RankSVM
						container.clear();
						for(URL url:query.m_urls){
							url.m_pClick = getClickProb(query, url);
							container.add(url);
						}
						
						Collections.sort(container, this);//sort by click probability						
						Out2Fv(writer, query, container.get(0), qid, 2, null);
						if (query.clickSize()>0)
							Out2Fv(writer, query, container.get(1), qid, 1, null);
						for(int n=2; n<container.size()-1; n++)
							Out2Fv(writer, query, container.get(n), qid, 0, null);
					}
					qid ++;
				}
			}
			writer.close();
		} catch (IOException e) {
			e.printStackTrace();
		} 
	}
	
	public void Save4Vct(String filename, boolean isBucket){
		m_asFeature = false;
		try {
			BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(filename)));
			int qid = 0, userSize = 0;
			URL url;
			for(User user:m_userlist){
				if (user.m_queries.size()<3)
					continue;//discard users with two few queries
				
				for(Query query:user.m_queries){
					if (query.m_urls.size()<2 || query.clickSize()<1)
						continue;//nothing to learn from it
					for(int i=0; i<query.m_urls.size(); i++){
						url = query.m_urls.get(i);
						Out2Fv(writer, query, url, qid, url.m_c, user.m_ID+"@@"+query.m_query);//currently only use binary click label
						if (isBucket && i==3)
							break;//output the first four positions for bucket queries
					}
					qid ++;
				}
				userSize ++;
			}
			writer.close();
			System.out.println("[Info]Save " + userSize + " users to " + filename);
		} catch (IOException e) {
			e.printStackTrace();
		} 
	}
	
	private double normFeature(double v){
		//return 2*ClickModel.logistic(v)-1;//try different way of feature normalization
		return v;
	}
	
	private void Out2Fv(BufferedWriter writer, Query query, URL url, int qid, int label, String UID) throws IOException{
		int i;
		writer.write(label + " qid:" + qid + " ");
		for(i=0; i<url.m_features.length; i++)//exclude the bias term
			if (url.m_features[i]!=0)
				writer.write((1+i) + ":" + normFeature(url.m_features[i]) + " ");//index starts from 1
		
		if (m_asFeature)//click probability as feature 
			writer.write((1+i) + ":" + getClickProb(query, url) + " ");
		
		if (UID!=null)
			writer.write("#" + UID);
		writer.write("\n");
	}
	
	private void normalize(){
		if (m_x==null){
			m_x = new double[getRelFeatureSize()];
			m_xx = new double[m_x.length];
			getFeatureMeanSD(m_x, m_xx);
			System.out.println("[Info]Normalizing relevance features by self-estimated mean/variance...");
		} else
			System.out.println("[Info]Normalizing relevance features by reloaded mean/variance...");
		
		for(User user:m_userlist){
			for(Query query:user.m_queries){			
				for(URL url:query.m_urls){
					url.normalize(m_x, m_xx);
				}
			}
		}
	}
	
	public void Save4PairFormat(String filename){
		try {
			BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(filename)));
			writer.write("group\tquery\turl1\turl2\tgdiff\n");
			
			URL urlA, urlB;
			int i, j, pairSize = 0;
			
			for(User user:m_userlist){
				for(Query query:user.m_queries){					
					if (query.clickSize()==0)
						continue;
					
					int urlSize = query.m_urls.size();
					for(i=urlSize-1; i>=0; i--){
						urlA = query.m_urls.get(i);
						if (urlA.m_click==0)
							continue;
						for(j=0; j<Math.min(urlSize-1, i+2); j++){
							urlB = query.m_urls.get(j);
							if (j!=i && urlB.m_click==0){//skip above/next
								writer.write(query.m_timestamp + "\t" + query.m_query + "\t" + urlA.m_URL + "\t" + urlB.m_URL + "\t1\n");
								pairSize ++;
							}
						}
					}
				}
			}
			writer.close();
			System.out.println("[Info]Totally " + pairSize + " pairwise preferences found!");
		} catch (IOException e) {
			e.printStackTrace();
		} 	
	}
	
	public void Save4DP(String filename){
		try {
			BufferedWriter fWriter = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(filename+"_x.dat"))),
					yWriter = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(filename+"_y.dat")));
			
			int userSize = 0;
			for(User user:m_userlist){
				if (user.getUrlSize()<50)
					continue;
				
				int urlSize = 0;
				boolean quit = false;
				for(Query q:user.m_queries){
					if (q.clickSize()==0)
						continue;
					
					for(URL u:q.m_urls){
						if (u.m_click>0)
							yWriter.write("1\n");
						else
							yWriter.write("0\n");
						
						fWriter.write(Double.toString(u.m_features[0]));
						for(int i=1;i<u.m_features.length;i++)
							fWriter.write("," + u.m_features[i]);
						fWriter.write("\n");
						if (++urlSize==50){
							quit = true;
							break;
						}
					}
					
					if (quit)
						break;
				}
				
				if (++userSize==800)
					break;
			}
			fWriter.close();
			yWriter.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	@Override
	public void doPredicton(Query q) {}

	@Override
	public double getClickProb(Query q, URL u) {
		return 0;
	}
	
	@Override
	public void setClicks(Query q){
		double p;
		for(URL u:q.m_urls){//when we don't have predictions, we just keep it untouched
			p = getClickProb(q, u);
			if (p>=0.5)
				u.m_click = 1;
			else
				u.m_click = 0;
		}
		q.resetClickSize();//in case some other places would use it
	}

	@Override
	public void initialize() {
		m_test_init = true;
		ClickModel.m_alpha = 0.0;
	}
	
	@Override
	public int compare(URL u1, URL u2) {
		if (u1.m_pClick>u2.m_pClick)
			return -1;
		else if (u1.m_pClick<u2.m_pClick)
			return 1;
		else 
			return 0;
	}
	
//	static public void main(String[] args){
//		Analyzer analyzer = new Analyzer(0);
//		analyzer.LoadFeatureMeanSD("Data/Logs/ext_features/ext_feature_stat.dat");
//		analyzer.LoadLogs("Data/Logs/ext_features/urls_ext.dat", 10000);
//		analyzer.Save4Vct("Data/Vectors/ext_log", false);
//		//analyzer.LoadAnnotation("Data/Edit/click_quj");
//		
//		
//		//analyzer.LoadLogs("Data/Edit/urls.dat");
//		//analyzer.Save4MLR("Data/Vectors/edit", true, false);
//		
//		//analyzer.LoadLogs("c:/Projects/Data/User Analysis/Heavy Users/Users/Content/Bucket/urls.dat");
//		//analyzer.Save4MLR("Data/Vectors/bucket_url", "Data/Heads/fv_head.dat", false, true);
//		//analyzer.Save4DP("Data/Vectors/search");
//		
//		//analyzer.getQueryStats("Data/Results/query_stat.dat");
//		//analyzer.getFeatureStats("Data/Results/ext_feature_stat.dat");
//	}
	
	static public void main(String[] args){
		if (args[0].equals("tofv") && args.length!=6){
			System.err.println("[Usage]tofv logfile maxUser fv_head resultfile isBucket");
			return;
		} else if (args[0].equals("tovct") && args.length!=6){
			System.err.println("[Usage]tofv fstat logfile maxUser resultfile isBucket");
			return;
		} else if (args[0].equals("qstat") && args.length!=3){
			System.err.println("[Usage]qstat logfile resultfile");
			return;
		} else if (args[0].equals("fstat") && args.length!=4){
			System.err.println("[Usage]fstat logfile maxUser resultfile");
			return;
		} 
		
		Analyzer analyzer = new Analyzer(0);

		if (args[0].equals("tofv")){
			if (args[3].equals("null")==false)
				analyzer.LoadFeatureMeanSD(args[3]);
			
			analyzer.LoadLogs(args[1], Integer.valueOf(args[2]));
			analyzer.Save4MLR(args[4], Boolean.valueOf(args[5]));
		} else if (args[0].equals("tovct")){
			if (args[1].equals("null")==false)
				analyzer.LoadFeatureMeanSD(args[1]);
			
			analyzer.LoadLogs(args[2], Integer.valueOf(args[3]));
			analyzer.Save4Vct(args[4], Boolean.valueOf(args[5]));
		} else if (args[0].equals("qstat")){
			analyzer.LoadLogs(args[1]);
			analyzer.getQueryStats(args[2]);
		} else if (args[0].equals("fstat")){
			analyzer.LoadLogs(args[1], Integer.valueOf(args[2]));
			analyzer.getFeatureStats(args[3]);
		}
	}
}
