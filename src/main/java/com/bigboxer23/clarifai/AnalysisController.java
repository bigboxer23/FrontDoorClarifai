package com.bigboxer23.clarifai;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

/** Controller to manage the HTTP requests coming in for analysis */
@RestController
@EnableAutoConfiguration
public class AnalysisController {
	private static final Logger myLogger = LoggerFactory.getLogger(AnalysisController.class);

	private ScheduledFuture myTask;

	private AnalysisManager myAnalysisManager;

	private List<File> myBatchedFiles = new ArrayList<>();

	private ScheduledExecutorService myExecutorService = Executors.newSingleThreadScheduledExecutor();

	// Don't allow reading files from anywhere on disk
	@Value("${basepath}")
	private String baseAnalysisPath;

	/**
	 * After a successful call, time until we'd immediately send another notification, otherwise
	 * we'll start collecting images to batch into a single notification
	 */
	@Value("${successThreshold}")
	private long mySuccessThreshhold;

	private long myLastSuccessfulCall = -1;

	private long myIsPaused = -1;

	public AnalysisController(AnalysisManager manager) {
		myAnalysisManager = manager;
	}

	private String sanitizeFileInput(String fileInput) {
		if (fileInput.split("\\.").length > 2) {
			String start = fileInput.substring(0, fileInput.lastIndexOf(".")).replace(".", "");
			String end = fileInput.substring(fileInput.lastIndexOf(".")).replace(".", "");
			fileInput = start + "." + end;
		}
		if (baseAnalysisPath != null && !fileInput.startsWith(baseAnalysisPath)) {
			fileInput = baseAnalysisPath + fileInput;
		}
		myLogger.info("sanitized " + fileInput);
		return fileInput;
	}

	/**
	 * Parse the file out of the request URL, send to clarifai to determine what we should do with
	 * them
	 *
	 * @param filePathToAnalyze
	 * @throws InterruptedException
	 */
	@GetMapping(path = "/analyze", produces = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			summary = "Triggers analysis of a file passed as part of the url as a `file` parameter",
			description = "Analyzes image found as part of the file parameter with clarifai's API.  If"
					+ " the image is determined to match the model, notification/email are"
					+ " sent.  The file is uploaded to S3 and removed from the local file"
					+ " system.")
	public void analyzeImage(
			@Parameter(description = "Path to the local file motion saves.") @RequestParam(value = "file")
					String filePathToAnalyze)
			throws IOException {
		if (myIsPaused > System.currentTimeMillis()) {
			myLogger.info("Paused, not running" + filePathToAnalyze);
			return;
		}
		myLogger.info("Starting " + filePathToAnalyze);
		filePathToAnalyze = sanitizeFileInput(filePathToAnalyze);
		File fileToAnalyze = new File(filePathToAnalyze);
		if (!fileToAnalyze.exists()) {
			myLogger.error(filePathToAnalyze + " does not exist.");
			return;
		}
		myAnalysisManager.sendToClarifai(
				fileToAnalyze,
				theSuccessFile -> {
					myBatchedFiles.add(theSuccessFile);
					if (myLastSuccessfulCall + (mySuccessThreshhold * 60 * 1000) < System.currentTimeMillis()) {
						myLastSuccessfulCall = System.currentTimeMillis();
						getTask(true).run();
						return;
					}
					myLogger.info("Adding " + theSuccessFile.getName() + " to batch notification.");
					if (myTask != null) {
						myTask.cancel(true);
					}
					myLastSuccessfulCall = System.currentTimeMillis();
					myTask = myExecutorService.schedule(getTask(false), mySuccessThreshhold, TimeUnit.MINUTES);
				},
				theFailureFile -> {
					myAnalysisManager.moveToS3(theFailureFile, "Failure/");
					myAnalysisManager.deleteFile(theFailureFile);
				});
		myLogger.info("Done " + filePathToAnalyze);
	}

	@PostMapping(path = "/pause/{delay}", produces = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			summary = "Pauses the application",
			description = "Passing in the number of seconds in the URL will cause the application to stop"
					+ " analyzingresults until that number of seconds has passed.")
	public long pause(
			@Parameter(description = "Number of seconds to pause analysis for.") @PathVariable(value = "delay")
					Long delay) {
		myIsPaused = System.currentTimeMillis() + delay * 1000;
		myLogger.info("Pausing for " + delay + " seconds");
		return isPaused();
	}

	@GetMapping(path = "/isPaused", produces = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			summary = "Checks to see if the analysis has been paused",
			description =
					"Returns either 0 (not paused or the number of seconds the application has" + " been paused for")
	public long isPaused() {
		return Math.max(0, (myIsPaused - System.currentTimeMillis()) / 1000);
	}

	@PostMapping(path = "/enable", produces = MediaType.APPLICATION_JSON_VALUE)
	@Operation(
			summary = "Enable the application, if paused",
			description = "Re-enables analysis if application was paused")
	public void enable() {
		myLogger.info("Enabling running again");
		myIsPaused = -1;
	}

	private SuccessTask getTask(boolean theFireNotification) {
		return new SuccessTask(
				Collections.unmodifiableList(myBatchedFiles), theFireNotification, myAnalysisManager, this);
	}

	public void clearBatchedFiles() {
		myBatchedFiles.clear();
	}
}
