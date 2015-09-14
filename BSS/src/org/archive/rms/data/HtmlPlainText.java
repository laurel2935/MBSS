package org.archive.rms.data;

public class HtmlPlainText {
	String _url;
	String _title;
	String _plainText;
	
	HtmlPlainText(String url, String title, String plainText){
		this._url = url;
		this._title = title;
		this._plainText = plainText;
	}
	
	public String getUrl(){
		return this._url;
	}
	public String getTitle(){
		return this._title;
	}
	public String getPlainText(){
		return this._plainText;
	}
	public void sysOutput(){
		System.out.println(this._url);
		System.out.println(this._title);
		System.out.println(this._plainText);
	}

}
