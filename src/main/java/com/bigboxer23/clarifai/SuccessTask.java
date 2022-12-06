package com.bigboxer23.clarifai;

import java.io.File;
import java.net.URL;
import java.util.List;
import java.util.Optional;

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
			Optional<URL> url = myAnalysisManager.moveToS3(theFile, "Success/");
			if (myFiles.size() == 1)
			{
				myAnalysisManager.sendAfterStored(url);
			}
			myAnalysisManager.deleteFile(theFile);
		});
		myAnalysisController.clearBatchedFiles();
	}
}
