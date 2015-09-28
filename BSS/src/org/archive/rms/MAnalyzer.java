package org.archive.rms;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.HashMap;

import org.archive.access.feature.FRoot;
import org.archive.access.feature.IAccessor;
import org.archive.access.feature.RFeature;
import org.archive.access.index.DocData.DocStyle;
import org.archive.access.utility.SimpleTensor;
import org.archive.rms.data.BingQSession1;
import org.archive.rms.data.DataAccessor;
import org.archive.rms.data.TQuery;
import org.archive.rms.data.TUrl;
import org.archive.rms.data.TUser;
import org.archive.util.io.IOText;
import org.archive.util.tuple.Triple;
import org.ejml.simple.SimpleMatrix;


/**
 * The framework performs the following tasks:
 * 1. process w.r.t. the search log
 * 
 * **/

public class MAnalyzer {
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
	
	//key: docNo:queryText
	protected HashMap<String, ArrayList<Double>> key2ReleFeatureMap;
	//simQSession.getKey()+":"+qText
	protected HashMap<String, SimpleTensor> key2MarFeatureMap;
	
	
	protected MClickModel _mClickModel;
	
	//for features
	IAccessor _iAccessor;
	//= new IAccessor(DocStyle.ClickText, false, true);
	
	MAnalyzer(boolean ini){
		
		if(ini){
			_iAccessor = new IAccessor(DocStyle.ClickText);
		}
		
	}
	
	//////////
	//Necessary for click-model training & testing
	//////////
	public void loadFeatureVectors(int sliceNum){
		key2ReleFeatureMap = loadRFeatureVectors();
		key2MarFeatureMap = loadMarFeatureVectors(sliceNum);
	}
	//////////
	//for pre-buffering
	//////////
	MAnalyzer(int threshold_UnavailableHtml_NonClickedUrl, int threshold_UnavailableHtml_ClickedUrl){
		this._threshold_UnavailableHtml_NonClickedUrl = threshold_UnavailableHtml_NonClickedUrl;
		this._threshold_UnavailableHtml_ClickedUrl = threshold_UnavailableHtml_ClickedUrl;
	}
	
	public void setSearchLogFile(String rawSearchLogFile){
		this._rawSearchLogFile = rawSearchLogFile;
	}
	
	/**
	 * load the search query log, and initialize specific TQuery, TUrl, TUser, etc.
	 * **/
	private void loadSearchLog(){
		
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
		
		boolean check = true;
		int usedCount = 0;
		
		System.out.println(key2MarFeatureMap.size());
		
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
						sessionWriter.write(tUrl.getDocNo()+NEWLINE);
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
		
		int adjustedRankPosition = 1;
		
		for(TUrl tUrl: originalUrlList){
			if(tUrl.isHtmlAvailable()){
				tUrl.adjustRankPosition(adjustedRankPosition++);
				adjustedUrlList.add(tUrl);
			}
		}		
	}
	
	
	protected void iniClickModel() {
		
		for(TUser tUser: _userList){
			for(TQuery tQuery: tUser.getQueryList()){
				//System.out.println(tQuery.getKey());
				tQuery.setMarTensor(key2MarFeatureMap.get(tQuery.getKey()+":"+tQuery.getQueryText()));
				
				for(TUrl tUrl: tQuery.getUrlList()){
					tUrl.setReleFeatureVector(toDArray(key2ReleFeatureMap.get(tUrl.getDocNo()+":"+tQuery.getQueryText())));
				}				
			}
		}
				
		_mClickModel = new MClickModel(_userList);		
		
		_mClickModel.train();
	}
	
	private double [] toDArray(ArrayList<Double> dArrayList){
		double [] dArray = new double [dArrayList.size()];
		for(int i=0; i<dArrayList.size(); i++){
			dArray[i] = dArrayList.get(i);
		}
		return dArray;
	}
	
	/**
	 * 
	 * **/
	class SimQSession{
		////userID_Rguid: serial number of acceptedSessions; userid ; rguid
		String _key;
		String _queryText;
		ArrayList<String> _docNoList;
		
		SimQSession(String key, String queryText){
			this._key = key;
			this._queryText = queryText;
			this._docNoList = new ArrayList<>();
		}
		
		public void addDoc(String docNo){
			this._docNoList.add(docNo);
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
						//String [] parts = line.split(":");					 	
						//docno
						simQSession.addDoc(line);											
					}
				}
			}
			//last one
			simQSessionList.add(simQSession);
						
		} catch (Exception e) {
			// TODO: handle exception
			e.printStackTrace();
		}
		
		System.out.println("Loaded number:\t"+simQSessionList.size());
		
		return simQSessionList;		
	}
	
	public void bufferReleFeature(ArrayList<SimQSession> simQSessionList){
		String targetFile = _fRoot._bufferDir+"RelevanceFeatureVectors"
				+"_"+Integer.toString(this._threshold_UnavailableHtml_ClickedUrl)
				+"_"+Integer.toString(this._threshold_UnavailableHtml_NonClickedUrl)
				+"_"+Integer.toString(this._totalAcceptedSessions)+".txt";
		try {
			BufferedWriter rFeatureWriter = IOText.getBufferedWriter_UTF8(targetFile);				
			for(SimQSession simQSession: simQSessionList){
				String qText = simQSession.getQueryText();
				ArrayList<String> docNoList = simQSession.getDocList();
				for(String docNo: docNoList){
					rFeatureWriter.write(docNo+":"+qText+":");
					RFeature rFeature = _iAccessor.getRFeature(false, _iAccessor.getDocStyle(), qText, docNo);
					rFeatureWriter.write(rFeature.toVectorString()+NEWLINE);
				}
			}
			rFeatureWriter.flush();
			rFeatureWriter.close();
		} catch (Exception e) {
			// TODO: handle exception
			e.printStackTrace();
		}		
	}
	
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
				key2ReleFeatureMap.put(parts[0]+":"+parts[1], getRFeature(parts[2]));
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
	
	public void bufferMarFeature(ArrayList<SimQSession> simQSessionList){
		String targetFile = _fRoot._bufferDir+"MarginalRelevanceFeatureVectors"
				+"_"+Integer.toString(this._threshold_UnavailableHtml_ClickedUrl)
				+"_"+Integer.toString(this._threshold_UnavailableHtml_NonClickedUrl)
				+"_"+Integer.toString(this._totalAcceptedSessions)+".txt";
		try {
			BufferedWriter mFeatureWriter = IOText.getBufferedWriter_UTF8(targetFile);
			
			boolean check = true;
			int threshold;
			if(check){
				threshold = Math.min(10, simQSessionList.size());
			}else {
				threshold = simQSessionList.size();
			}			
			
			for(int i=0; i<threshold; i++){
				
				SimQSession simQSession = simQSessionList.get(i);
				
				String qText = simQSession.getQueryText();
				ArrayList<String> docNoList = simQSession.getDocList();
				SimpleTensor mTensor = _iAccessor.getMFeature(false, _iAccessor.getDocStyle(), qText, docNoList);
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
	
	public HashMap<String, SimpleTensor> loadMarFeatureVectors(int sliceNum){
		String targetFile = _fRoot._bufferDir+"MarginalRelevanceFeatureVectors"
				+"_"+Integer.toString(this._threshold_UnavailableHtml_ClickedUrl)
				+"_"+Integer.toString(this._threshold_UnavailableHtml_NonClickedUrl)
				+"_"+Integer.toString(this._totalAcceptedSessions)+".txt";
		
		HashMap<String, SimpleTensor> key2MarFeatureMap = null;
		
		try {
			ArrayList<String> lineList = IOText.getLinesAsAList_UTF8(targetFile);
			
			key2MarFeatureMap = IAccessor.loadMarTensor(sliceNum, lineList);
			
		} catch (Exception e) {
			// TODO: handle exception
			e.printStackTrace();
		}
		
		return key2MarFeatureMap;
	}
	
	
	//////////
	//Preliminary steps
	//////////
	//1 get simplified QSessions, which is the basis step for buffering feature vectors
	/**
	 * required lines: 
	 * MAnalyzer(int threshold_UnavailableHtml_NonClickedUrl, int threshold_UnavailableHtml_ClickedUrl){}
	 * setSearchLogFile(String rawSearchLogFile);
	 * 
	 * if 0 0, this will not be needed
	 * **/
	public void getSimplifiedQSessions(){
		loadSearchLog();
		bufferAcceptedSessions(this._userList);		
	}
	
	//2 buffer feature vectors
	//之前需完成(1) text extraction for LDA training; (2) pre-index;
	
	public void bufferFeatureVectors(){
		ArrayList<SimQSession> simQSessionList = loadAcceptedSessions();
		//for test
		//SimQSession sQSession = simQSessionList.get(0);
		//System.out.println(sQSession._docNoList);
		
		//buffer-1
		//bufferReleFeature(simQSessionList);
		
		//buffer-2
		bufferMarFeature(simQSessionList);
	}
	
	
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
		mAnalyzer.getSimplifiedQSessions();
		*/
		
		//3 step-2
		// buffer rele and mar features
		/*
		MAnalyzer mAnalyzer = new MAnalyzer(true);
		mAnalyzer.bufferFeatureVectors();
		*/
		
		//4 step-3
		MAnalyzer mAnalyzer = new MAnalyzer(false);
		mAnalyzer.loadFeatureVectors(6);
		
		mAnalyzer.setSearchLogFile(FRoot._file_UsedSearchLog);
		mAnalyzer.loadSearchLog();
		
		mAnalyzer.iniClickModel();
		
	}
}
