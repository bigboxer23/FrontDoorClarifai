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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Controller to manage the HTTP requests coming in for analysis
 */
@RestController
@EnableAutoConfiguration
public class AnalysisController
{
	private static final Logger myLogger = LoggerFactory.getLogger(AnalysisController.class);

	private ScheduledFuture myTask;

	private AnalysisManager myAnalysisManager;

	private List<File> myBatchedFiles = new ArrayList<>();

	private ScheduledExecutorService myExecutorService = Executors.newSingleThreadScheduledExecutor();

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
				getTask(true).run();
				return;
			}
			myLogger.info("Adding " + theSuccessFile.getName() + " to batch notification.");
			if (myTask != null)
			{
				myTask.cancel(true);
			}
			myLastSuccessfulCall = System.currentTimeMillis();
			myTask = myExecutorService.schedule(getTask(false), mySuccessThreshhold, TimeUnit.MINUTES);
		}, theFailureFile ->
		{
			myAnalysisManager.moveToS3(theFailureFile, "Failure/");
			myAnalysisManager.deleteFile(theFailureFile);
		});
		myLogger.info("Done " + theFileToAnalyze);
	}

	private SuccessTask getTask(boolean theFireNotification)
	{
		return new SuccessTask(Collections.unmodifiableList(myBatchedFiles), theFireNotification, myAnalysisManager, this);
	}

	public void clearBatchedFiles()
	{
		myBatchedFiles.clear();
	}
}
