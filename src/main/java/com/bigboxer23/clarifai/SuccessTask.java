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

	private List<File> myFiles;

	public SuccessTask(List<File> theFiles, AnalysisManager theAnalysisManager, AnalysisController theController)
	{
		myAnalysisManager = theAnalysisManager;
		myFiles = theFiles;
		myAnalysisController = theController;
	}

	@Override
	public void run()
	{
		myAnalysisManager.sendGmail(myFiles);
		myFiles.forEach(theFile ->
		{
			myAnalysisManager.sendNotification(theFile.getName());
			myAnalysisManager.moveToS3(theFile, "Success/");
			myAnalysisManager.deleteFile(theFile);
		});
		myAnalysisController.clearBatchedFiles();
	}
}
