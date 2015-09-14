package org.archive.rms.data;

public class HtmlDoc {
	
	String _url;
	String _htmlStr;

	public HtmlDoc(String url, String htmlStr) {
		this._url = url;
		this._htmlStr = htmlStr;
	}
	
	public void sysOutput(){
		System.out.println(this._url);
		System.out.println(this._htmlStr);
	}
	
	public String getHtmlStr(){
		return this._htmlStr;
	}
	
	public String getUrl(){
		return this._url;
	}
}
