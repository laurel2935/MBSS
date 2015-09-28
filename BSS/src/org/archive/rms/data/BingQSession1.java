package org.archive.rms.data;

import java.util.ArrayList;

public class BingQSession1 {
	//the original format is: serial number of acceptedSessions; userid ; rguid
	//separated by " "; 
	String _UserID_Rguid;
	String _queryText;
	
	ArrayList<String> _recordList;
	
	ArrayList<String> _displayedUrlList;
	ArrayList<Boolean> _clickVector;
	ArrayList<Integer> _rankOfClicks;
	
	public BingQSession1(String UserID_Rguid, String q){
		this._UserID_Rguid = UserID_Rguid;		
		this._queryText = q;
		this._displayedUrlList = new ArrayList<String>();
		this._clickVector = new ArrayList<>();
		this._rankOfClicks = new ArrayList<Integer>();
		this._recordList = new ArrayList<String>();
	}
	
	//say included displayed urls
	public boolean isValid(){
		return this._displayedUrlList.size()>0;		
	}
	
	public boolean isClicked(){
		return this._rankOfClicks.size()>0;
	}
	
	public int getClickNum(){
		return this._rankOfClicks.size();
	}
	
	public String getQuery(){
		return this._queryText;
	}
	
	public ArrayList<String> getDisplayedUrlList(){
		return this._displayedUrlList;
	}
	
	public ArrayList<Boolean> getClickVector(){
		return this._clickVector;
	}
	
	public ArrayList<String> getRecords(){
		return this._recordList;
	}
	
	public String getKey(){
		return this._UserID_Rguid;
	}
	
	public void addDisplayedUrl(String url, boolean clicked){
		this._displayedUrlList.add(url);
		
		if(clicked){
			this._rankOfClicks.add(this._displayedUrlList.size());
			this._clickVector.add(true);
		}else{
			this._clickVector.add(false);
		}
	}
	
	public void addRecord(String record){
		this._recordList.add(record);
	}
	
	public String toString(){
		StringBuffer buffer = new StringBuffer();
		buffer.append(this.isClicked()+"\t"+this._queryText+"\t"+this._recordList.size()+"\n");
		
		for(String r: this._recordList){
			buffer.append("\t"+r+"\n");
		}
		
		return buffer.toString();
	}
}
