package com.bigboxer23.clarifai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.util.*;

/**
 * Controller to manage the HTTP requests coming in for analysis
 */
@RestController
@EnableAutoConfiguration
public class AnalysisController
{
	private static final Logger myLogger = LoggerFactory.getLogger(AnalysisController.class);

	private Timer myBatchTimer = new Timer();

	private AnalysisManager myAnalysisManager;

	private List<File> myBatchedFiles = new ArrayList<>();

	/**
	 * After a successful call, time until we'd immediately send another notification, otherwise we'll start collecting
	 * images to batch into a single notification
	 */
	@Value("${successThreshold}")
	private long mySuccessThreshhold;

	private long myLastSuccessfulCall = -1;

	@Autowired
	public void setAnalysisManager(AnalysisManager theAnalysisManager)
	{
		myAnalysisManager = theAnalysisManager;
	}

	/**
	 * Parse the file out of the request URL, send to clarifai to determine what we should do with them
	 *
	 * @param theFileToAnalyze
	 * @throws InterruptedException
	 */
	@RequestMapping("/analyze")
	public void analyzeImage(@RequestParam(value="file") String theFileToAnalyze) throws InterruptedException
	{
		myLogger.info("Starting " + theFileToAnalyze);
		File aFileToAnalyze = new File(theFileToAnalyze);
		if (!aFileToAnalyze.exists())
		{
			myLogger.error(theFileToAnalyze + " does not exist.");
			return;
		}
		myAnalysisManager.sendToClarifai(aFileToAnalyze, theSuccessFile ->
		{
			myBatchedFiles.add(theSuccessFile);
			if (myLastSuccessfulCall + mySuccessThreshhold < System.currentTimeMillis())
			{
				myLastSuccessfulCall = System.currentTimeMillis();
				getTask().run();
				return;
			}
			myLogger.info("Adding " + theSuccessFile.getName() + " to batch notification.");
			myBatchTimer.cancel();
			myBatchTimer = new Timer();
			myLastSuccessfulCall = System.currentTimeMillis();
			myBatchTimer.schedule(getTask(), mySuccessThreshhold);
		}, theFailureFile ->
		{
			myAnalysisManager.moveToS3(theFailureFile, "Failure/");
			myAnalysisManager.deleteFile(theFailureFile);
		});
		myLogger.info("Done " + theFileToAnalyze);
	}

	private SuccessTask getTask()
	{
		return new SuccessTask(Collections.unmodifiableList(myBatchedFiles), myAnalysisManager, this);
	}

	public void clearBatchedFiles()
	{
		myBatchedFiles.clear();
	}
}
