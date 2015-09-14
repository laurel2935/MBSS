package org.archive.rms.data;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.archive.rms.BFRoot;
import org.archive.rms.MAnalyzer;
import org.archive.util.io.IOText;

import de.l3s.boilerpipe.extractors.ArticleExtractor;

public class DataAccessor {
	//for extracting the title segment
	private static Pattern titlePattern = Pattern.compile("<title>(.*?)</title>");
	private static HashSet<String> _acceptedUrlAndWithAvailableHtmlSet = null;
		
	//////////
	//Part-1
	//////////
	//load sessions
	public static ArrayList<BingQSession1> loadSearchLog(String file){
		
		ArrayList<BingQSession1> sessionList = new ArrayList<BingQSession1>();
		int count = 0;
				
		try {
			BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file)));

			String line = null;
			//10: userid, requestTime, sessionid, query, rguid, rank, url, isClick, clickTime, time  
			//11: pseudo-id, userid, requestTime, sessionid, query, rguid, rank, url, isClick, clickTime, time 
			//!!now the number of parts is 11, since we added the pseudo accepted session serial
			String [] parts;
			BingQSession1 qSession1 = null;		
			
			while(null !=(line=reader.readLine())){
				parts = line.split("\t");
				//i.e., serial number of acceptedSessions; userid ; rguid
				String userID_Rguid = parts[0]+"\t"+parts[1]+"\t"+parts[5];
				//String userid= parts[0];
				String query = parts[4];
				//String rguid = parts[4];
				String url = parts[7];
				int isClicked = Integer.parseInt(parts[8]);
				
				//System.out.println((count)+"\t"+parts.length+"\t"+query+"\t"+isClicked);
				
				if(null!=qSession1 && qSession1.getKey().equals(userID_Rguid)){
					qSession1.addDisplayedUrl(url, isClicked>0);
					qSession1.addRecord(line);
				}else{
					if(null != qSession1){
						count ++;
						sessionList.add(qSession1);	
						
						//new
						qSession1 = new BingQSession1(userID_Rguid, query);
						qSession1.addDisplayedUrl(url, isClicked>0);
						qSession1.addRecord(line);
						
					}else{
						
						qSession1 = new BingQSession1(userID_Rguid, query);
						qSession1.addDisplayedUrl(url, isClicked>0);
						qSession1.addRecord(line);						
					}					
				}				
			}
			
			//last session			
			sessionList.add(qSession1);
			count ++;
			
			reader.close();
			
		} catch (Exception e) {
			// TODO: handle exception
			e.printStackTrace();
		}
		
		System.out.println("Loaded Session Count:\t"+count);
		
		return sessionList;
		
		//for check only
		/*
		try {
			BufferedWriter writer = IOText.getBufferedWriter_UTF8(
					"C:/T/WorkBench/Corpus/DataSource_Analyzed/text.txt");
			
			BingQSession1 qSession1 = null;
			for(int i=0; i<sessionList.size(); i++){
				qSession1 = sessionList.get(i);
				ArrayList<String> recordList = qSession1.getRecords();
				for(String r: recordList){
					writer.write(StandardFormat.serialFormat((i+1), "000000")+r);
					writer.newLine();
				}				
			}
			
			writer.flush();
			writer.close();
			
		} catch (Exception e) {
			e.printStackTrace();
		}
		*/		
	}
	//load html source files. Of course, the input file should be well formatted.
	private static ArrayList<HtmlDoc> loadHtmlSourceFiles(String file){
		ArrayList<HtmlDoc> htmlDocList = new ArrayList<HtmlDoc>();
		
		try {			
			ArrayList<String> lineList = IOText.getLinesAsAList_UTF8(file);
			
			HtmlDoc htmlDoc = null;
			StringBuffer buffer = new StringBuffer();
			String url = null;
			
			for(String line: lineList){
				if(line.indexOf("</doc>") >=0){
					htmlDoc = new HtmlDoc(url, buffer.toString());
					htmlDocList.add(htmlDoc);					
					
				}else if(line.indexOf("</url>") >= 0){
					url = line.substring(5, line.length()-6);
					buffer.delete(0, buffer.length());
				}else if(line.indexOf("<doc>") >=0){
					
				}else{
					buffer.append(line+"\n");
				}
			}			
		} catch (Exception e) {
			e.printStackTrace();
		}
		
		//System.out.println("Count of Loaded Htmls:\t"+htmlDocList.size());
		//htmlDocList.get(943).sysOutput();
		return htmlDocList;
	}
	
	
	//extract meta data given a html source file
	public static String getTitle(String htmlStr){
		Matcher matcher = titlePattern.matcher(htmlStr);
		
		if(matcher.find()){
			String matStr = matcher.group();
			return matStr.substring(7, matStr.length()-8);
		}else{
			return null;
		}
	}
	private static void extractMeta(String file){
		try {
			ArrayList<HtmlPlainText> htmlPlainTextList = new ArrayList<>();
			
			ArrayList<HtmlDoc> htmlDocList = loadHtmlSourceFiles(file);		
			
			String plainText = null, url = null, title = null;
			
			int testCount = 0;
			
			for(HtmlDoc htmlDoc: htmlDocList){
				testCount++;				
				if(testCount > 10){
					break;
				}
				
				url = htmlDoc.getUrl();
				title = getTitle(htmlDoc.getHtmlStr());
				try {
					plainText = ArticleExtractor.INSTANCE.getText(htmlDoc.getHtmlStr());
				} catch (Exception e) {
					System.err.println("Extraction Eror!");
				}
				
				htmlPlainTextList.add(new HtmlPlainText(url, title, plainText.trim()));			
			}
			
			//putput
			File inFile = new File(file);
			String name = inFile.getName();
			String abPath = inFile.getAbsolutePath();
			System.out.println(abPath);
			String outFile = abPath.substring(0, abPath.lastIndexOf("\\"));
			BufferedWriter writer = IOText.getBufferedWriter_UTF8(outFile+"/"+"Meta_"+name);
			
			for(HtmlPlainText metaDoc: htmlPlainTextList){
				writer.write("<doc>");writer.newLine();
				writer.write("<url>"+metaDoc.getUrl()+"</url>");writer.newLine();
				writer.write("<title>"+metaDoc.getTitle()+"</title>");writer.newLine();
				writer.write("<text>\n"+metaDoc.getPlainText()+"\n</text>");writer.newLine();
				writer.write("</doc>");writer.newLine();
			}
			
			writer.flush();
			writer.close();			
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	//load meta-files
	private static ArrayList<HtmlPlainText> loadMetaFiles(String file){
		ArrayList<HtmlPlainText> htmlPlainTextList = new ArrayList<>();
		//int count = 0;
		
		try {
			ArrayList<String> lineList = IOText.getLinesAsAList_UTF8(file);
			
			HtmlPlainText htmlPlainText = null;
			StringBuffer buffer = new StringBuffer();
			String url=null, title=null;
			
			for(String line: lineList){
				if(line.indexOf("</doc>") >= 0){
					htmlPlainText = new HtmlPlainText(url, title, buffer.toString());
					htmlPlainTextList.add(htmlPlainText);
				}else if(line.indexOf("</url>") >= 0){
					url = line.substring(5, line.length()-6);
				}else if(line.indexOf("</title>") >=0){
					title = line.substring(7, line.length()-8);
					buffer.delete(0, buffer.length());
				}else if (line.indexOf("<doc>")>=0 || line.indexOf("<text>")>=0 || line.indexOf("</text>")>=0) {
					
				}else{
					buffer.append(line+"\n");
				}
			}			
		} catch (Exception e) {
			e.printStackTrace();
		}
		
		System.out.println("Count of Loaded HtmlPlainText:\t"+htmlPlainTextList.size());
		htmlPlainTextList.get(9).sysOutput();
		return htmlPlainTextList;
		
	}
	
	//step-1: By calling ClickThroughAnalyzer.getStatistics(k) for generating the desired accepted session data.
	//step-2: check the format of downloaded html files
	private static void checkDownloadedFileFormat(String dir){
		try {
			File dirFile = new File(dir);	
			ArrayList<File> fileList = new ArrayList<>();
			getFiles(dirFile, fileList);
			System.out.println("size:\t"+fileList.size());
			
			for(File file: fileList){
				String fPath = file.getAbsolutePath();
				if(fPath.indexOf("00000") >= 0){
					if(!isValidFile(fPath)){
						System.out.println(fPath);
					}
				}
			}			
		} catch (Exception e) {
			// TODO: handle exception
			e.printStackTrace();
		}
	}
	//check whether a file of downloaded htmls are well formatted.
	private static boolean isValidFile(String file){
		ArrayList<HtmlDoc> htmlDocList = new ArrayList<HtmlDoc>();
		
		int docNum=0, slashDocNum=0;
		try {			
			ArrayList<String> lineList = IOText.getLinesAsAList_UTF8(file);
			
			HtmlDoc htmlDoc = null;
			StringBuffer buffer = new StringBuffer();
			String url = null;
			
			for(String line: lineList){
				if(line.indexOf("</doc>") >=0){
					htmlDoc = new HtmlDoc(url, buffer.toString());
					htmlDocList.add(htmlDoc);	
					
					slashDocNum++;					
				}else if(line.indexOf("</url>") >= 0){
					url = line.substring(5, line.length()-6);
					buffer.delete(0, buffer.length());
				}else if(line.indexOf("<doc>") >=0){
					docNum++;
				}else{
					buffer.append(line+"\n");
				}
			}			
		} catch (Exception e) {
			e.printStackTrace();
		}
		
		if(docNum==slashDocNum && docNum==htmlDocList.size()){
			return true;
		}else{
			System.out.println(file);
			System.out.println("DocNum: "+docNum+"\tslashDocNum: "+slashDocNum+"\thtmlDocList: "+htmlDocList.size());
			return false;
		}
	}
	//
	private static void getFiles(File dirFile, ArrayList<File> fileList){
		for(File file: dirFile.listFiles()){
			if(file.isDirectory()){
				getFiles(file, fileList);
			}else{
				fileList.add(file);
			}
		}
	}
	//step-3: extract the set of urls w.r.t. the downloaded html files. The result set of urls is used as the base w.r.t. the function: isHtmlAccessible
	private static void extractAcceptedUrlsWithAvailableHtmls(String collectionDir){
		try {
			String targetFile = "/Users/dryuhaitao/WorkBench/Corpus/DataSource_Raw/WebPage/AcceptedUrlsWithAvailableHtmls_BasedOnAtLeast2Clicks.txt";
			BufferedWriter tWriter = IOText.getBufferedWriter_UTF8(targetFile);
			
			File dirFile = new File(collectionDir);	
			ArrayList<File> fileList = new ArrayList<>();
			getFiles(dirFile, fileList);
			System.out.println("size:\t"+fileList.size());
			
			for(File file: fileList){
				String fPath = file.getAbsolutePath();
				if(fPath.indexOf("00000") >= 0){
					ArrayList<HtmlDoc> htmlDocList = loadHtmlSourceFiles(fPath);
					for(HtmlDoc htmlDoc: htmlDocList){
						if(accept(htmlDoc._url)){
							tWriter.write(htmlDoc._url+MAnalyzer.NEWLINE);
						}
					}
				}
			}		
			
			tWriter.flush();
			tWriter.close();
		} catch (Exception e) {
			// TODO: handle exception
			e.printStackTrace();
		}
	}
	
	private static boolean accept(String urlString){
		if(urlString.endsWith(".doc") || urlString.endsWith(".DOC") 
				|| urlString.endsWith(".pdf") || urlString.endsWith(".PDF")
				|| urlString.endsWith(".docx") || urlString.endsWith(".DOCX")
				|| urlString.endsWith(".ppt") || urlString.endsWith(".PPT")){
			
			return false;
			
		}else {
			return true;
		}
	} 
	
	
	
	//////////
	//Part-2
	//////////
	//to do
 	public static boolean isAccetpedAndHtmlAccessible(String urlStr){
 		if(null == _acceptedUrlAndWithAvailableHtmlSet){
 			_acceptedUrlAndWithAvailableHtmlSet = new HashSet<>();
 			
 			ArrayList<String> lineList = IOText.getLinesAsAList_UTF8(BFRoot._file_AcceptedUrlAndWithAvailableHtml);
 			for(String line: lineList){
 				_acceptedUrlAndWithAvailableHtmlSet.add(line);
 			}
 		}
 		
 		if(_acceptedUrlAndWithAvailableHtmlSet.contains(urlStr)){
 			return true;
 		}else {
			return false;
		}
	}
	
	
	
	public static void main(String []args){
		//String root = "C:/T/WorkBench/Corpus/DataSource_Analyzed/FilteredBingOrganicSearchLog_AtLeast_2Click/";
		//1
		//String file = root+"AcceptedSessionData_AtLeast_2Click.txt";
		//DataAccessor.loadSearchLog(file);
		
		//2
		//String file = root+"Top100/HtmlSourceFiles_Top100/00000001.txt";
		//DataAccessor.loadHtmlSourceFiles(file);
		
		//3
		//String file = root+"Top100/HtmlSourceFiles_Top100/00000001.txt";
		//DataAccessor.extractMeta(file);
		
		//4
		/*
		String file = root+"Top100/HtmlSourceFiles_Top100/Meta_00000001.txt";
		DataAccessor.loadMetaFiles(file);
		*/
		
		//5 check
		//String dir = "/Users/dryuhaitao/WorkBench/Corpus/DataSource_Raw/WebPage/Collection_BasedOnAtLeast2Click/";
		//DataAccessor.checkDownloadedFileFormat(dir);
		//result
		// size:	665
		// /Users/dryuhaitao/WorkBench/Corpus/DataSource_Raw/WebPage/Collection_BasedOnAtLeast2Click/Fetch_Results_6thTime_Checked/0001/0001-00000010.txt
		//6 further check
		//DataAccessor.isValidFile("/Users/dryuhaitao/WorkBench/Corpus/DataSource_Raw/WebPage/Collection_BasedOnAtLeast2Click/Fetch_Results_6thTime_Checked/0001/0001-00000010.txt");
		
		//7
		//String dir = "/Users/dryuhaitao/WorkBench/Corpus/DataSource_Raw/WebPage/Collection_BasedOnAtLeast2Click/";
		//DataAccessor.extractAcceptedUrlsWithAvailableHtmls(dir);
		
		//8
		System.out.println(DataAccessor.isAccetpedAndHtmlAccessible("http://www.printfriendly.com/"));
		
	}
}
