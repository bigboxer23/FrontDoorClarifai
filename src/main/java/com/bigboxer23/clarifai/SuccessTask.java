package com.bigboxer23.clarifai;

import java.io.File;
import java.util.List;
import java.util.TimerTask;

/**
 * Task to run on getting a successful image from clarifai
 */
public class SuccessTask extends TimerTask
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
		myAnalysisManager.sendGmail(myFiles);
		myFiles.forEach(theFile ->
		{
			if (myFireNotification)
			{
				myAnalysisManager.sendNotification(theFile.getName());
			}
			myAnalysisManager.moveToS3(theFile, "Success/");
			myAnalysisManager.deleteFile(theFile);
		});
		myAnalysisController.clearBatchedFiles();
	}
}
