package org.archive.rms.advanced;

import java.io.BufferedWriter;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Random;
import java.util.Map.Entry;

import org.archive.access.feature.FRoot;
import org.archive.access.feature.HtmlPlainText;
import org.archive.access.feature.IAccessor;
import org.archive.access.feature.RFeature;
import org.archive.access.feature.TextCollection;
import org.archive.access.index.DocData.DocStyle;
import org.archive.access.utility.SimpleTensor;
import org.archive.nicta.kernel.LDAKernel;
import org.archive.nicta.kernel.TFIDF_A1;
import org.archive.rms.data.BingQSession1;
import org.archive.rms.data.DataAccessor;
import org.archive.rms.data.TQuery;
import org.archive.rms.data.TUrl;
import org.archive.rms.data.TUser;
import org.archive.util.io.IOText;
import org.archive.util.tuple.Pair;
import org.archive.util.tuple.StrInt;
import org.ejml.simple.SimpleMatrix;


/**
 * The framework performs the following tasks:
 * 1. process w.r.t. the search log
 * 2. provide features for the input QSessions for the MClickModel
 * 
 * **/

public class MAnalyzer {
	//--for extending
	protected Random m_rand;
	//protected double _testRatio;
	//i.e., top-trainNum instances
	protected int trainCnt;
	//i.e., later-testNum instances
	protected int testCnt;
	//total data
	protected ArrayList<TQuery> _QSessionList;
	//training data
	protected ArrayList<TQuery> _trainingCorpus;
	//testing data
	protected ArrayList<TQuery> _testCorpus;
	protected int _foldCnt;
	protected int _kFoldForTest;
	ArrayList<Pair<Integer, Integer>> _foldList;
	//--
	
	protected int _minQFreForTest;
	protected int _maxQSessionSize;
	
	public static final String NEWLINE = System.getProperty("line.separator");
	protected static final String QSessionLine = "QSession";
	protected static final String TAB = "\t";	
	
	String _rawSearchLogFile;
	
	//filtering sessions
	int _threshold_UnavailableHtml_NonClickedUrl = 0;
	int _threshold_UnavailableHtml_ClickedUrl    = 0;
	int _totalAcceptedSessions = 10778;
	
	protected ArrayList<TUser> _userList;
	
	//key: {docNo:queryText}
	protected HashMap<String, ArrayList<Double>> key2ReleFeatureMap;
	//simQSession.getKey()+":"+qText
	protected HashMap<String, SimpleTensor> key2MarFeatureMap;
		
	protected MClickModel _mClickModel;	
	
	//for features
	IAccessor _iAccessor;
	//= new IAccessor(DocStyle.ClickText, false, true);	
	
	/**
	 * @param ini true is for buffering features. once the features have been buffered, it should be false;
	 * **/
	MAnalyzer(boolean ini, boolean fieldSpecificLDA, boolean useLoadedModel){
		
		if(ini){
			_iAccessor = new IAccessor(DocStyle.ClickText, fieldSpecificLDA, useLoadedModel);
		}
		
	}
	
	//for comparing UBM and DBN
	private static boolean _useAll = false;
	private static int _minQFreForSparcityCmp = 1;
	
	protected MAnalyzer(int foldCnt, int kFoldForTest, int minQFreForTest, boolean useFeature, int maxQSessionSize){
		//this._testRatio = testRatio;
		this._minQFreForTest = minQFreForTest;
		this._maxQSessionSize = maxQSessionSize;
		this._foldCnt = foldCnt;
		this._kFoldForTest = kFoldForTest;
		
		this._QSessionList = new ArrayList<>();
		//search log
		LoadLog(_useAll);
				
		//features
		if(useFeature){
			loadFeatureVectors();
		}	
		
		adjustBySize(maxQSessionSize);
		
		//should head of filterBySize
		int maxClickCnt = (int)(0.9*maxQSessionSize);
		filterByClickNum(maxClickCnt);
		
		int corpusSize = this._QSessionList.size();
		this._foldList = getFoldList(corpusSize, _foldCnt);
		
		this._trainingCorpus = getTrainingCorpus(this._kFoldForTest);
		this._testCorpus = getTestCorpus(this._kFoldForTest, this._minQFreForTest);
		this.trainCnt = _trainingCorpus.size();
		this.testCnt = _testCorpus.size();
		
		System.out.println("Finally used query sessions:\t"+this._QSessionList.size()+"\t"+(this.trainCnt+this.testCnt));
	}
	
	MAnalyzer(){
		this._QSessionList = new ArrayList<>();
	}
	
	//////////
	//Necessary for click-model training & testing
	//////////
	public void filterByClickNum(int atMostClicks){
		ArrayList<TQuery> QSessionList = new ArrayList<>();
		
		for(TQuery tQuery: this._QSessionList){
			int clickCnt = tQuery.getClickCount();
			if(clickCnt <= atMostClicks && clickCnt>0){
				QSessionList.add(tQuery);
			}
		}
		
		this._QSessionList = QSessionList;
	}
	
	public void adjustBySize(int maxQSessionSize){
		
		for(TQuery tQuery: this._QSessionList){
			ArrayList<TUrl> tUrList = tQuery.getUrlList();
			if(tUrList.size() > maxQSessionSize){
				for(int k=tUrList.size()-1; k>=maxQSessionSize; k--){
					tUrList.remove(k);
					tQuery._gTruthClickSequence.remove(k);
				}
			}			
		}
	}
	
	public ArrayList<TQuery> getTrainingCorpus(int kFoldForTest){
		ArrayList<TQuery> trainingCorpus = new ArrayList<>();
		
		for(int i=1; i<=this._foldCnt; i++){
			if(i == kFoldForTest){
				continue;
			}else{
				Pair<Integer, Integer> foldEntry = this._foldList.get(i-1);
				int be=foldEntry.getFirst();
				int en=foldEntry.getSecond();
				for(int j=be; j<=en; j++){
					trainingCorpus.add(this._QSessionList.get(j));
				}
			}
		}
		
		return trainingCorpus;
	}
	
	public ArrayList<TQuery> getTestCorpus(int kFoldForTest, int minQFreForTest){
		ArrayList<TQuery> testCorpus = new ArrayList<>();
		
		if(minQFreForTest > 1){
			HashMap<String, StrInt> qFreMap = new HashMap<>();			
			for(TQuery tQuery: this._trainingCorpus){				
				String txt = tQuery.getQueryText();				
				if(qFreMap.containsKey(txt)){
					qFreMap.get(txt).intPlus1();
				}else{
					qFreMap.put(txt, new StrInt(txt));
				}
			}
			//
			Pair<Integer, Integer> foldEntry = this._foldList.get(kFoldForTest-1);
			int be=foldEntry.getFirst();
			int en=foldEntry.getSecond();
			
			for(int j=be; j<=en; j++){
				TQuery tQuery = this._QSessionList.get(j);
				String q = tQuery.getQueryText();
				if(qFreMap.containsKey(q) && qFreMap.get(q).getSecond() >= minQFreForTest){
					testCorpus.add(tQuery);
				}
			}			
			return testCorpus;			
		}else{
			Pair<Integer, Integer> foldEntry = this._foldList.get(kFoldForTest-1);
			
			//System.out.println(foldEntry.toString());
			//System.out.println(this._QSessionList.size());
			
			int be=foldEntry.getFirst();
			int en=foldEntry.getSecond();			
			
			for(int j=be; j<=en; j++){
				testCorpus.add(this._QSessionList.get(j));
			}
			return testCorpus;
		}				
	}
	
	public void refreshCorpus(int kFoldForTest){
		this._kFoldForTest = kFoldForTest;
		this._trainingCorpus = getTrainingCorpus(this._kFoldForTest);
		this._testCorpus = getTestCorpus(this._kFoldForTest, this._minQFreForTest);
		this.trainCnt = _trainingCorpus.size();
		this.testCnt = _testCorpus.size();
	}
	
	public ArrayList<TQuery> filterCorpus(int _minQFreForSparcityCmp){
		ArrayList<TQuery> corpus = new ArrayList<>();
		
		if(_minQFreForSparcityCmp > 1){
			HashMap<String, StrInt> qFreMap = new HashMap<>();			
			for(TQuery tQuery: this._QSessionList){				
				String txt = tQuery.getQueryText();				
				if(qFreMap.containsKey(txt)){
					qFreMap.get(txt).intPlus1();
				}else{
					qFreMap.put(txt, new StrInt(txt));
				}
			}
			//
			for(TQuery tQuery: this._QSessionList){
				String q = tQuery.getQueryText();
				if(qFreMap.containsKey(q) && qFreMap.get(q).getSecond() >= _minQFreForSparcityCmp){
					corpus.add(tQuery);
				}
			}			
			return corpus;			
		}else{
			return this._QSessionList;
		}				
	}
	
	public void loadFeatureVectors(){
		key2ReleFeatureMap = loadRFeatureVectors();
		key2MarFeatureMap = loadMarFeatureVectors();
		
		//test
		/*
		//rele
		System.out.println(key2ReleFeatureMap.size());
		for(Entry<String, ArrayList<Double>> entry: key2ReleFeatureMap.entrySet()){
			System.out.print(entry.getKey()+":");
			ArrayList<Double> dList = entry.getValue();
			for(Double d: dList){
				System.out.print(d);
			}		
			System.out.println();
			break;
		}
		*/
		
		/*
		//marginal
		//System.out.println(key2MarFeatureMap.size());
		for(Entry<String, SimpleTensor> entry: key2MarFeatureMap.entrySet()){
			System.out.println(entry.getKey());
			SimpleTensor simpleTensor = entry.getValue();
			
			System.out.print(getMFeatureVector(simpleTensor));			
			//break;
		}
		*/
	}
	
	//initial test
	protected void iniClickModel() {		
		//set the corresponding feature vectors
		for(TUser tUser: _userList){
			for(TQuery tQuery: tUser.getQueryList()){
				String qKey = tQuery.getKey()+":"+tQuery.getQueryText();
				tQuery.setMarTensor(key2MarFeatureMap.get(qKey));
				
				for(TUrl tUrl: tQuery.getUrlList()){
					String urlKey = tUrl.getDocNo()+":"+tQuery.getQueryText();
					tUrl.setReleFeatureVector(toDArray(key2ReleFeatureMap.get(urlKey)));
				}				
			}
		}
				
		//_mClickModel = new MClickModel(_userList);		
		
		//_mClickModel.train();
	}
	
	public static double [] toDArray(ArrayList<Double> dArrayList){
		double [] dArray = new double [dArrayList.size()];
		for(int i=0; i<dArrayList.size(); i++){
			dArray[i] = dArrayList.get(i);
		}
		return dArray;
	}

	//////////
	//Preliminary steps w.r.t. buffering
	//////////
	
	////
	//step-1 get simplified QSessions, which is the basis step for buffering feature vectors
	////
	/**
	 * required lines: 
	 * MAnalyzer(int threshold_UnavailableHtml_NonClickedUrl, int threshold_UnavailableHtml_ClickedUrl){}
	 * setSearchLogFile(String rawSearchLogFile);
	 * 
	 * if 0 0, this will not be needed
	 * **/
	public void getSimplifiedQSessions(boolean check){
		loadSearchLog(check);
		bufferAcceptedSessions(this._userList);		
	}
	////required builder
	MAnalyzer(int threshold_UnavailableHtml_NonClickedUrl, int threshold_UnavailableHtml_ClickedUrl){
		this._threshold_UnavailableHtml_NonClickedUrl = threshold_UnavailableHtml_NonClickedUrl;
		this._threshold_UnavailableHtml_ClickedUrl = threshold_UnavailableHtml_ClickedUrl;
	}
	
	////w.r.t. search log
	public void setSearchLogFile(String rawSearchLogFile){
		this._rawSearchLogFile = rawSearchLogFile;
	}
	
	protected void LoadLog(boolean useAll){
		setSearchLogFile(FRoot._file_UsedSearchLog);
		
		ArrayList<BingQSession1> bingQSessionList = DataAccessor.loadSearchLog(_rawSearchLogFile);
		
		//int sessionCount = 0;
		ArrayList<TQuery> tQueryList = new ArrayList<>();
		
		if(useAll){
			for(BingQSession1 bQSession: bingQSessionList){
				TQuery tQuery = new TQuery(bQSession);
				
				tQueryList.add(tQuery);
				//sessionCount++;
			}
		}else{
			for(BingQSession1 bQSession: bingQSessionList){
				TQuery tQuery = new TQuery(bQSession);
				
				if(acceptSession(tQuery, true)){
					tQueryList.add(tQuery);
					//sessionCount++;
				}
			}
		}
		
		
		
		this._QSessionList.addAll(tQueryList);
		
		if(_minQFreForSparcityCmp > 1){
			this._QSessionList = filterCorpus(_minQFreForSparcityCmp);
		}
		
		this._totalAcceptedSessions = this._QSessionList.size();	
		
		System.out.println("Total number of used sessions:\t"+_totalAcceptedSessions);
	}
	
	/**
	 * load the search query log, and initialize specific TQuery, TUrl, TUser, etc.
	 * **/
	private void loadSearchLog(boolean check){
		
		ArrayList<BingQSession1> bingQSessionList = DataAccessor.loadSearchLog(_rawSearchLogFile);
		
		int sessionCount = 0;
		ArrayList<TQuery> tQueryList = new ArrayList<>();
		for(BingQSession1 bQSession: bingQSessionList){
			TQuery tQuery = new TQuery(bQSession);
			if(acceptSession(tQuery, true)){
				tQueryList.add(tQuery);
				sessionCount++;
			}
		}
		
		HashMap<String, TUser> tUserMap = new HashMap<>();
		
		int usedCount = 0;
		
		//System.out.println(key2MarFeatureMap.size());
		
		for(TQuery tQuery: tQueryList){
			
			String str = tQuery.getKey()+":"+tQuery.getQueryText();
			
			if(check){
				if(!key2MarFeatureMap.containsKey(str)){
					continue;
				}
				usedCount++;
			}			
			
			String userID = tQuery.getUserID();
			
			if(tUserMap.containsKey(userID)){
				tUserMap.get(userID).addTQuery(tQuery);
			}else{
				TUser tUser = new TUser(userID);
				tUser.addTQuery(tQuery);
				
				tUserMap.put(userID, tUser);
			}			
		}
		
		ArrayList<TUser> tUserList = new ArrayList<>();		
		for(TUser tUser: tUserMap.values()){
			tUserList.add(tUser);
		}
		
		this._totalAcceptedSessions = sessionCount;
		this._userList = tUserList;		
		
		System.out.println(_totalAcceptedSessions);
		System.out.println("Used sessions:\t"+usedCount);
	}
	/**
	 * buffer and load via intermediate simplified sessions
	 * **/
	protected void bufferAcceptedSessions(ArrayList<TUser> tUserList) {
		try {
			String targetFile = FRoot._bufferDir+"SimplifiedSessions"
					+"_"+Integer.toString(this._threshold_UnavailableHtml_ClickedUrl)
					+"_"+Integer.toString(this._threshold_UnavailableHtml_NonClickedUrl)
					+"_"+Integer.toString(this._totalAcceptedSessions)+".txt";
			
			BufferedWriter sessionWriter = IOText.getBufferedWriter_UTF8(targetFile);
			
			int count = 1;
			for(TUser tUser: tUserList){
				ArrayList<TQuery> queryList = tUser.getQueryList();
				for(TQuery tQuery: queryList){
					
					//tQuery.calContextInfor();
					
					sessionWriter.write(QSessionLine+":"
										+(count++)+":"
										+tQuery.getKey()+":"
										+tQuery.getQueryText()
										+NEWLINE);					
					ArrayList<TUrl> urlList = tQuery.getUrlList();
					for(TUrl tUrl: urlList){
						sessionWriter.write(tUrl.getDocNo()+":"+tUrl.getUrl()+NEWLINE);
					}
				}
			}
			sessionWriter.flush();
			sessionWriter.close();
		} catch (Exception e) {
			// TODO: handle exception
			e.printStackTrace();
		}		
	}
	
	protected ArrayList<SimQSession> loadAcceptedSessions() {
		String simSessionFile = FRoot._bufferDir+"SimplifiedSessions"
				+"_"+Integer.toString(this._threshold_UnavailableHtml_ClickedUrl)
				+"_"+Integer.toString(this._threshold_UnavailableHtml_NonClickedUrl)
				+"_"+Integer.toString(this._totalAcceptedSessions)+".txt";
		
		File simSFile = new File(simSessionFile);
		if(!simSFile.exists()){
			System.err.println("No file error!");
			return null;
		}
		
		ArrayList<SimQSession> simQSessionList = null;
		try {
			simQSessionList = new ArrayList<>();
			
			ArrayList<String> lineList = IOText.getLinesAsAList_UTF8(simSessionFile);
			
			SimQSession simQSession = null;
			for(String line: lineList){
				if(line.indexOf(QSessionLine) >= 0){
					String [] parts = line.split(":");
					String key = parts[2];
					String qText = parts[3];
					if(null == simQSession){
						simQSession = new SimQSession(key, qText);
					}else{
						simQSessionList.add(simQSession);
						simQSession = new SimQSession(key, qText);
					}						
				}else{
					if(null != simQSession){
						int splitIndex = line.indexOf(":");
						String docNo = line.substring(0, splitIndex);
						String url = line.substring(splitIndex+1);
						//
						simQSession.addDoc(docNo, url);											
					}
				}
			}
			//last one
			simQSessionList.add(simQSession);
						
		} catch (Exception e) {
			// TODO: handle exception
			e.printStackTrace();
		}
		
		System.out.println("Loaded simSession number:\t"+simQSessionList.size());
		
		return simQSessionList;		
	}
	
	protected boolean acceptSession(TQuery tQuery, boolean adjust){
		int numUnava_NonClickedUrl=0, numUnava_ClickedUrl=0;
		
		ArrayList<TUrl> urlList = tQuery.getUrlList();
		for(TUrl tUrl: urlList){
			if(!tUrl.isHtmlAvailable()){
				if(tUrl.getGTruthClick() > 0){
					numUnava_ClickedUrl++;
				}else{
					numUnava_NonClickedUrl++;
				}
			}
		}
		
		if((numUnava_NonClickedUrl<=this._threshold_UnavailableHtml_NonClickedUrl) 
				&& (numUnava_ClickedUrl<=this._threshold_UnavailableHtml_ClickedUrl)
				){
			//adjust if needed
			if(adjust && (numUnava_NonClickedUrl>0 || numUnava_ClickedUrl>0)){
				adjustSession(tQuery);
			}			
			return true;
		}else{
			return false;
		}
	}
	
	protected void adjustSession(TQuery tQuery){
		
		ArrayList<TUrl> originalUrlList = tQuery.getUrlList();
		
		ArrayList<TUrl> adjustedUrlList = new ArrayList<>();
		
		ArrayList<Boolean> clickVector = new ArrayList<>();
		int adjustedRankPosition = 1;
		
		for(TUrl tUrl: originalUrlList){
			if(tUrl.isHtmlAvailable()){
				tUrl.adjustRankPosition(adjustedRankPosition++);
				adjustedUrlList.add(tUrl);
				clickVector.add(tUrl.getGTruthClick()>0?true:false);
			}
		}	
		
		tQuery.setGTruthClickSequence(clickVector);
	}
		
	/**
	 * 
	 * **/
	class SimQSession{
		////userID_Rguid: serial number of acceptedSessions; userid ; rguid
		String _key;
		String _queryText;
		ArrayList<String> _docNoList;
		ArrayList<String> _urlList;
		
		SimQSession(String key, String queryText){
			this._key = key;
			this._queryText = queryText;
			this._docNoList = new ArrayList<>();
			this._urlList = new ArrayList<>();
		}
		
		public void addDoc(String docNo, String url){
			this._docNoList.add(docNo);
			this._urlList.add(url);
		}
				
		public String getQueryText(){
			return this._queryText;
		}
		
		public String getKey() {
			return this._key;
		}
		
		public ArrayList<String> getDocList(){
			return this._docNoList;
		}
	}
	
	////w.r.t. features	
	public void bufferReleFeature(ArrayList<SimQSession> simQSessionList, TextCollection textCollection){
		
		String targetFile = FRoot._bufferDir+"RelevanceFeatureVectors"
				+"_"+Integer.toString(this._threshold_UnavailableHtml_ClickedUrl)
				+"_"+Integer.toString(this._threshold_UnavailableHtml_NonClickedUrl)
				+"_"+Integer.toString(this._totalAcceptedSessions)+".txt";
		
		try {
			BufferedWriter rFeatureWriter = IOText.getBufferedWriter_UTF8(targetFile);	
			
			//avoid redo
			HashSet<String> keySet = new HashSet<>();
			
			int i=1;
			for(SimQSession simQSession: simQSessionList){
				
				System.out.println("Buffering rele-session-"+(i++));
				
				String qText = simQSession.getQueryText();
				ArrayList<String> docNoList = simQSession.getDocList();
				
				for(String docNo: docNoList){					
					String key = docNo+":"+qText+":";					
					if(!keySet.contains(key)){
						keySet.add(key);
						
						rFeatureWriter.write(key);
						RFeature rFeature = _iAccessor.getRFeature(false, _iAccessor.getDocStyle(), qText, docNo, textCollection);
						rFeatureWriter.write(rFeature.toVectorString()+NEWLINE);
					}					
				}
			}
			rFeatureWriter.flush();
			rFeatureWriter.close();
		} catch (Exception e) {
			// TODO: handle exception
			e.printStackTrace();
		}		
	}
	//part-1
	public HashMap<String, ArrayList<Double>> loadRFeatureVectors(){
		
		String targetFile = FRoot._bufferDir+"RelevanceFeatureVectors"
				+"_"+Integer.toString(this._threshold_UnavailableHtml_ClickedUrl)
				+"_"+Integer.toString(this._threshold_UnavailableHtml_NonClickedUrl)
				+"_"+Integer.toString(this._totalAcceptedSessions)+".txt";
		
		HashMap<String, ArrayList<Double>> key2ReleFeatureMap = new HashMap<>();
		try {
			ArrayList<String> lineList = IOText.getLinesAsAList_UTF8(targetFile);
			
			for(String line: lineList){
				String [] parts = line.split(":");
				//key: docNo:queryText
				String key = parts[0]+":"+parts[1];
				if(!key2ReleFeatureMap.containsKey(key)){
					key2ReleFeatureMap.put(parts[0]+":"+parts[1], getRFeature(parts[2]));
				}else{
					System.out.println(key);
					break;
				}
				
			}
		} catch (Exception e) {
			// TODO: handle exception
			e.printStackTrace();
		}
		
		return key2ReleFeatureMap;
	}
	private ArrayList<Double> getRFeature(String dString){
		String [] dArray = dString.split(",");
		ArrayList<Double> dList = new ArrayList<>();
		for(String dElement: dArray){
			dList.add(Double.parseDouble(dElement));
		}
		return dList;
	}
	//part-2
	public void bufferMarFeature(boolean check, ArrayList<SimQSession> simQSessionList, TextCollection textCollection){
		
		String targetFile = FRoot._bufferDir+"MarginalRelevanceFeatureVectors"
				+"_"+Integer.toString(this._threshold_UnavailableHtml_ClickedUrl)
				+"_"+Integer.toString(this._threshold_UnavailableHtml_NonClickedUrl)
				+"_"+Integer.toString(this._totalAcceptedSessions)+".txt";
		
		try {			
			HashMap<String, LDAKernel> ldaKernelMap = null;
			HashMap<String, TFIDF_A1> tfidfKernelMap = null;
			/*
			System.out.println("Set up lda kernels ...");
			ldaKernelMap = DataAccessor.getKernelMap_ClickText_LDA(textCollection);
			
			System.out.println("Set up tfidf kernels ...");
			tfidfKernelMap = DataAccessor.getKernelMap_ClickText_TFIDF(textCollection);
			*/
			
			BufferedWriter mFeatureWriter = IOText.getBufferedWriter_UTF8(targetFile);
			
			int threshold;
			if(check){
				threshold = Math.min(10, simQSessionList.size());
			}else {
				threshold = simQSessionList.size();
			}			
			
			System.out.println("Buffering marginal features ...");
			
			for(int i=0; i<threshold; i++){
				System.out.println("Buffering mar-session-"+i);
				
				SimQSession simQSession = simQSessionList.get(i);				
				String qText = simQSession.getQueryText();
				ArrayList<String> docNoList = simQSession.getDocList();
				
				//call per query
				SimpleTensor mTensor = _iAccessor.getMFeature(false, _iAccessor.getDocStyle(), qText, docNoList, textCollection, tfidfKernelMap, ldaKernelMap);
				
				String mTensorString = getMFeatureVector(mTensor);
				 
				mFeatureWriter.write(QSessionLine+":"										
										+simQSession.getKey()+":"
										+qText
										+NEWLINE);
				mFeatureWriter.write(mTensorString);				
			}
			
			mFeatureWriter.flush();
			mFeatureWriter.close();			
		} catch (Exception e) {
			// TODO: handle exception
			e.printStackTrace();
		}		
	}
	
	public String getMFeatureVector(SimpleTensor mTensor){
		int sliceNum = mTensor.numSlices();
		int rowNum = mTensor.numRows();
		int colNum = mTensor.numCols();
				
		StringBuffer strBuffer = new StringBuffer();
		for(int k=0; k<sliceNum; k++){
			SimpleMatrix aMatrix = mTensor.getSlice(k);
			for(int i=0; i<rowNum; i++){
				for(int j=0; j<colNum; j++){
					if(i != j){
						strBuffer.append(Integer.toString(k)+TAB);
						strBuffer.append(Integer.toString(i)+TAB);
						strBuffer.append(Integer.toString(j)+TAB);
						strBuffer.append(Double.toString(aMatrix.get(i, j))+NEWLINE);
					}else{
						strBuffer.append(Integer.toString(k)+TAB);
						strBuffer.append(Integer.toString(i)+TAB);
						strBuffer.append(Integer.toString(j)+TAB);
						strBuffer.append(Double.toString(0.0)+NEWLINE);
					}
				}
			}
		}
		
		return strBuffer.toString();
	}
	
	public HashMap<String, SimpleTensor> loadMarFeatureVectors(){
		String targetFile = FRoot._bufferDir+"MarginalRelevanceFeatureVectors"
				+"_"+Integer.toString(this._threshold_UnavailableHtml_ClickedUrl)
				+"_"+Integer.toString(this._threshold_UnavailableHtml_NonClickedUrl)
				+"_"+Integer.toString(this._totalAcceptedSessions)+".txt";
		
		HashMap<String, SimpleTensor> key2MarFeatureMap = null;
		
		try {
			ArrayList<String> lineList = IOText.getLinesAsAList_UTF8(targetFile);
			
			key2MarFeatureMap = IAccessor.loadMarTensor(lineList);
			
		} catch (Exception e) {
			// TODO: handle exception
			e.printStackTrace();
		}
		
		return key2MarFeatureMap;
	}
	
	////
	//step-2 buffer feature vectors, 之前需完成(1) text extraction for LDA training; (2) pre-index;
	////
	
	public void bufferFeatureVectors(boolean check){
		ArrayList<SimQSession> simQSessionList = loadAcceptedSessions();
		
		//for test
		//SimQSession sQSession = simQSessionList.get(0);
		//System.out.println(sQSession._docNoList);
		
		System.out.println("Loading plain text ...");
		
		HashSet<String> htmlUrlSet = new HashSet<>();			
		for(SimQSession simSession: simQSessionList){
			for(String url: simSession._urlList){
				if(!htmlUrlSet.contains(url)){
					htmlUrlSet.add(url);
				}
			}
		}
		
		HashMap<String, HtmlPlainText> docNo2HtmlPlainTextMap = DataAccessor.loadHtmlText(htmlUrlSet);		
		TextCollection textCollection = DataAccessor.getTextCollection(docNo2HtmlPlainTextMap);
		
		//buffer-1
		bufferReleFeature(simQSessionList, textCollection);
		
		//buffer-2
		bufferMarFeature(check, simQSessionList, textCollection);
	}
	
	
	//statistics
	public void getStatistics(){
		HashMap<String, StrInt> queryFreMap = new HashMap<>();
		HashMap<String, StrInt> docFreMap = new HashMap<>();
		
		HashSet<String> clickedDocSet = new HashSet<>();
		int totalClickCnt = 0;
		
		for(TQuery tQuery: this._QSessionList){
			//1
			String txt = tQuery.getQueryText();				
			if(queryFreMap.containsKey(txt)){
				queryFreMap.get(txt).intPlus1();
			}else{
				queryFreMap.put(txt, new StrInt(txt));
			}
			
			//2
			ArrayList<TUrl> tUrlList = tQuery.getUrlList();
			for(TUrl tUrl: tUrlList){
				String docno = tUrl.getDocNo();
				if(docFreMap.containsKey(docno)){
					docFreMap.get(docno).intPlus1();
				}else{
					docFreMap.put(docno, new StrInt(docno));
				}
				
				if(tUrl.getGTruthClick() > 0){
					totalClickCnt++;
					
					if(!clickedDocSet.contains(docno)){
						clickedDocSet.add(docno);
					}
				}
			}
		}
		
		int totalQueries = 0;
		int totalDoc = 0;
		for(Entry<String, StrInt> qEntry: queryFreMap.entrySet()){
			StrInt qElement = qEntry.getValue();
			totalQueries += qElement.second;
		}
		for(Entry<String, StrInt> docEntry: docFreMap.entrySet()){
			StrInt docElement = docEntry.getValue();
			totalDoc += docElement.second;
		}
		System.out.println("Unique Q:\t"+queryFreMap.size());
		System.out.println("Total  Q:\t"+totalQueries);
		System.out.println();
		System.out.println("Unique Doc:\t"+docFreMap.size());
		System.out.println("Total  Doc:\t"+totalDoc);
		System.out.println();
		System.out.println("Total query sessions:\t"+this._QSessionList.size());
		System.out.println();
		System.out.println("Unique clicked Docs:\t"+clickedDocSet.size());
		System.out.println("Total clicks:\t"+totalClickCnt);
	}
	
	
	//
	private static ArrayList<Pair<Integer, Integer>> getFoldList(int corpusSize, int k){
		if(0 == corpusSize%k ){
			int cap = corpusSize/k;
			
			ArrayList<Pair<Integer, Integer>> foldList = new ArrayList<>();
			for(int i=1; i<=k; i++){
				int begin = cap*(i-1);
				int end = (cap*i-1);
				
				foldList.add(new Pair<Integer, Integer>(begin, end));				
			}
			/*
			for(Pair<Integer, Integer> p: foldList){
				System.out.println(p.getFirst()+" "+p.getSecond());
			}
			*/
			
			return foldList;
			
		}else{
			int cap = corpusSize/k+1;
			ArrayList<Integer> intCapList = new ArrayList<Integer>();
			for(int i=0; i<k; i++){
				intCapList.add(cap);
			}
			
			int gap = cap*k - corpusSize;
			
			int count = 0;
			for(int j=k-1; j>=0; j--){				
				intCapList.set(j, intCapList.get(j)-1);
				count++;
				if(count == gap){
					break;
				}
			}
			
			ArrayList<Pair<Integer, Integer>> foldList = new ArrayList<>();
			int begin = 0;
			for(Integer intCap: intCapList){
				int end = begin+intCap-1;
				foldList.add(new Pair<Integer, Integer>(begin, end));
				begin = end+1;
			}
			/*
			for(Pair<Integer, Integer> p: foldList){
				System.out.println(p.getFirst()+" "+p.getSecond());
			}
			*/
			return foldList;			
		}		
	}
	
	
	
	////
	//main
	////
	public static void main(String []args){
		//1 test
		/*
		MAnalyzer mAnalyzer = new MAnalyzer();
		mAnalyzer.setSearchLogFile(FRoot._file_UsedSearchLog);
		mAnalyzer.loadSearchLog();
		*/
		
		//2 step-1
		//requirements: (1) set of urls derived from extracted text files
		/*
		MAnalyzer mAnalyzer = new MAnalyzer(false);
		mAnalyzer.setSearchLogFile(FRoot._file_UsedSearchLog);
		mAnalyzer.getSimplifiedQSessions(false);
		*/
		
		//3 step-2 application
		// buffer rele and mar features
		/*
		boolean fieldSpecificLDA = true;
		boolean useLoadedModel = false;
		MAnalyzer mAnalyzer = new MAnalyzer(true, fieldSpecificLDA, useLoadedModel);
		mAnalyzer.bufferFeatureVectors(false);
		*/
		
		//4 step-3
		/*
		MAnalyzer mAnalyzer = new MAnalyzer(false);
		mAnalyzer.loadFeatureVectors();
		
		mAnalyzer.setSearchLogFile(FRoot._file_UsedSearchLog);
		mAnalyzer.loadSearchLog(true);
		
		mAnalyzer.iniClickModel();
		*/
		
		//5 get statistics of the data set
		/*
		MAnalyzer mAnalyzer = new MAnalyzer();
		mAnalyzer.LoadLog();
		mAnalyzer.getStatistics();
		*/
		
		//6
		/*
		0 1
		2 3
		4 5
		6 7
		8 9
		*/
		///*
		MAnalyzer.getFoldList(10, 3);
		//*/
		
		
	}
}
