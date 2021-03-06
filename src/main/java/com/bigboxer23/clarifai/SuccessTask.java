package com.bigboxer23.clarifai;

import java.io.File;
import java.util.List;

/**
 * Task to run on getting a successful image from clarifai
 */
public class SuccessTask implements Runnable
{
	private AnalysisManager myAnalysisManager;

	private AnalysisController myAnalysisController;

	private boolean myFireNotification;

	private List<File> myFiles;

	public SuccessTask(List<File> theFiles, boolean theFireNotification, AnalysisManager theAnalysisManager, AnalysisController theController)
	{
		myAnalysisManager = theAnalysisManager;
		myFiles = theFiles;
		myAnalysisController = theController;
		myFireNotification = theFireNotification;
	}

	@Override
	public void run()
	{
		if (myFireNotification)
		{
			myAnalysisManager.sendNotification();
		}
		myAnalysisManager.sendGmail(myFiles);
		myFiles.forEach(theFile ->
		{
			myAnalysisManager.moveToS3(theFile, "Success/");
			myAnalysisManager.deleteFile(theFile);
		});
		myAnalysisController.clearBatchedFiles();
	}
}
