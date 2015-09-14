package org.archive.rms.data;

import java.util.ArrayList;

public class TUser {
	String _userID;
	
	ArrayList<TQuery> _queryList;
	
	public TUser(String userID){
		this._userID = userID;
		this._queryList = new ArrayList<>();
	}
	
	public void addTQuery(TQuery tQuery){
		this._queryList.add(tQuery);
	}
	
	public ArrayList<TQuery> getQueryList(){
		return this._queryList;
	}
}
