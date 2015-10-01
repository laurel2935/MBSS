package org.archive.rms;

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
	protected double _testRatio;
	//i.e., top-trainNum instances
	protected int _trainNum;
	//i.e., later-testNum instances
	protected int _testNum;
	protected ArrayList<TQuery> _QSessionList;
	//--
	protected static FRoot _fRoot = new FRoot();
	
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
	
	
	protected MAnalyzer(double testRatio){
		this._testRatio = testRatio;
		
		this._testNum = (int)(this._QSessionList.size()*testRatio);
		this._trainNum = this._QSessionList.size()-this._testNum;
	}
	//////////
	//Necessary for click-model training & testing
	//////////
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
	
	private double [] toDArray(ArrayList<Double> dArrayList){
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
	
	protected void LoadLogs(){
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
		
		this._QSessionList.addAll(tQueryList);		
		this._totalAcceptedSessions = sessionCount;	
		
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
			String targetFile = _fRoot._bufferDir+"SimplifiedSessions"
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
		String simSessionFile = _fRoot._bufferDir+"SimplifiedSessions"
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
				&& (numUnava_ClickedUrl<=this._threshold_UnavailableHtml_ClickedUrl)){
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
	public void bufferReleFeature(ArrayList<SimQSession> simQSessionList){
		
		String targetFile = _fRoot._bufferDir+"RelevanceFeatureVectors"
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
						RFeature rFeature = _iAccessor.getRFeature(false, _iAccessor.getDocStyle(), qText, docNo);
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
		
		String targetFile = _fRoot._bufferDir+"RelevanceFeatureVectors"
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
	public void bufferMarFeature(boolean check, ArrayList<SimQSession> simQSessionList){
		
		String targetFile = _fRoot._bufferDir+"MarginalRelevanceFeatureVectors"
				+"_"+Integer.toString(this._threshold_UnavailableHtml_ClickedUrl)
				+"_"+Integer.toString(this._threshold_UnavailableHtml_NonClickedUrl)
				+"_"+Integer.toString(this._totalAcceptedSessions)+".txt";
		
		try {
			HashSet<String> htmlUrlSet = new HashSet<>();			
			for(SimQSession simSession: simQSessionList){
				for(String url: simSession._urlList){
					if(!htmlUrlSet.contains(url)){
						htmlUrlSet.add(url);
					}
				}
			}	
			
			System.out.println("Loading plain text ...");
			HashMap<String, HtmlPlainText> docNo2HtmlPlainTextMap = DataAccessor.loadHtmlText(htmlUrlSet);
			
			TextCollection textCollection = DataAccessor.getTextCollection(docNo2HtmlPlainTextMap);
			
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
		String targetFile = _fRoot._bufferDir+"MarginalRelevanceFeatureVectors"
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
		
		//buffer-1
		bufferReleFeature(simQSessionList);
		
		//buffer-2
		bufferMarFeature(check, simQSessionList);
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
		
		//3 step-2
		// buffer rele and mar features
		///*
		boolean fieldSpecificLDA = true;
		boolean useLoadedModel = false;
		MAnalyzer mAnalyzer = new MAnalyzer(true, fieldSpecificLDA, useLoadedModel);
		mAnalyzer.bufferFeatureVectors(false);
		//*/
		
		//4 step-3
		/*
		MAnalyzer mAnalyzer = new MAnalyzer(false);
		mAnalyzer.loadFeatureVectors();
		
		//mAnalyzer.setSearchLogFile(FRoot._file_UsedSearchLog);
		//mAnalyzer.loadSearchLog(true);
		
		//mAnalyzer.iniClickModel();
		*/
		
	}
}
