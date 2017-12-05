package com.bigboxer23.clarifai;

import clarifai2.api.ClarifaiBuilder;
import clarifai2.api.ClarifaiClient;
import clarifai2.dto.input.ClarifaiInput;
import clarifai2.dto.model.output.ClarifaiOutput;
import clarifai2.dto.prediction.Concept;
import clarifai2.dto.prediction.Prediction;
import com.bigboxer23.util.http.HttpClientUtil;
import org.apache.http.client.methods.HttpGet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.DecimalFormat;
import java.util.List;

/**
 *
 */
@RestController
@EnableAutoConfiguration
public class AnalysisController
{
	@Value("${APIKey}")
	private String myClarifaiAPIKey;

	@Value("${modelId}")
	private String myModelId;

	@Value("${threshold}")
	private double myThreshold = .75;

	@Value("${renameDirectory}")
	private String myRenameDirectory;

	private static final Logger myLogger = LoggerFactory.getLogger(AnalysisController.class);

	@Value("${notificationUrl}")
	private String kNotificationURL;

	@Value("${notificationEmail}")
	private String myNotifcationEmail;

	@RequestMapping("/analyze")
	public void analyzeImage(@RequestParam(value="file") String theFileToAnalyze)
	{
		myLogger.info("Starting " + theFileToAnalyze);
		File aFileToAnalyze = new File(theFileToAnalyze);
		if (!aFileToAnalyze.exists())
		{
			myLogger.error(theFileToAnalyze + " does not exist.");
			return;
		}
		ClarifaiClient aClient = new ClarifaiBuilder(myClarifaiAPIKey).buildSync();
		List<ClarifaiOutput<Prediction>> aResults = aClient.predict(myModelId).
				withInputs(ClarifaiInput.forImage(aFileToAnalyze)).
				executeSync().get();
		aResults.forEach(thePrediction ->
		{
			thePrediction.data().forEach(theValue ->
			{
				Concept aConcept = theValue.asConcept();
				myLogger.info(theFileToAnalyze + " " + (new DecimalFormat("##.00").format(aConcept.value() * 100)));
				if (aConcept.value() >= myThreshold)
				{
					sendMail(aFileToAnalyze.getAbsolutePath(), aConcept);
					copyFile(aFileToAnalyze, new File(myRenameDirectory + "Success/", aFileToAnalyze.getName()));
				} else
				{
					copyFile(aFileToAnalyze, new File(myRenameDirectory + "Failure/", aFileToAnalyze.getName()));
				}
			});
		});
		myLogger.info("Done " + theFileToAnalyze);
	}

	private File copyFile(File theFile, File theDestination)
	{
		try
		{
			Files.copy(theFile.toPath(), theDestination.toPath(), StandardCopyOption.REPLACE_EXISTING);
		}
		catch (IOException theE)
		{
			myLogger.error("copyFile:", theE);
		}
		return theDestination;
	}

	private void sendMail(String theFileName, Concept theConcept)
	{
		myLogger.info("Sending notification... " + theFileName);
		try
		{
			HttpClientUtil.getSSLDisabledHttpClient().execute(new HttpGet(kNotificationURL));
		}
		catch (Throwable e)
		{

		}
		myLogger.info("Notification Sent " + theFileName);
		try
		{
			new ProcessBuilder("mail",
					"-a", theFileName,
					"-s", "Front Door Motion",
					myNotifcationEmail
					).start();
		}
		catch (IOException theE)
		{
			myLogger.error("Error sending mail", theE);
		}
	}
}
