package org.archive.rms;

public class BFRoot {
	private static final String _sysTage = "MAC";
	
	//the pre-filtered set of urls based on all the downloaded htmls w.r.t. sessionsAtLeast2Clicks
	public static String _file_AcceptedUrlAndWithAvailableHtml;
	
	//used subset of search log file
	public static String _file_UsedSearchLog;
	
	
	
	
	static{
		if(_sysTage.equals("MAC")){
			
			_file_AcceptedUrlAndWithAvailableHtml = "/Users/dryuhaitao/WorkBench/Corpus/DataSource_Raw/WebPage/Collection_BasedOnAtLeast2Clicks/AcceptedUrlsWithAvailableHtmls_BasedOnAtLeast2Clicks.txt";
			//
			_file_UsedSearchLog = "/Users/dryuhaitao/WorkBench/Corpus/DataSource_Analyzed/WeekOrganicLog/AtLeast_2Clicks/AcceptedSessionData_AtLeast_2Clicks.txt";

		}else {
		}
	}
}
