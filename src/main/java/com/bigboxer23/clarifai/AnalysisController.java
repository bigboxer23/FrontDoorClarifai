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

import javax.activation.DataHandler;
import javax.activation.FileDataSource;
import javax.mail.*;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.DecimalFormat;
import java.util.List;
import java.util.Properties;

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
	private String myNotificationURL;

	@Value("${notificationEmail}")
	private String myNotificationEmail;

	@Value("${sendingEmailAccount}")
	private String mySendingEmailAccount;

	@Value("${sendingEmailPassword}")
	private String mySendingEmailPassword;

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
					sendNotification(aFileToAnalyze.getName(), aConcept);
					sendGmail(moveFile(aFileToAnalyze, new File(myRenameDirectory + "Success/", aFileToAnalyze.getName())), aConcept);
				} else
				{
					moveFile(aFileToAnalyze, new File(myRenameDirectory + "Failure/", aFileToAnalyze.getName()));
				}
			});
		});
		myLogger.info("Done " + theFileToAnalyze);
	}

	private File moveFile(File theFile, File theDestination)
	{
		try
		{
			Files.move(theFile.toPath(), theDestination.toPath(), StandardCopyOption.REPLACE_EXISTING);
		}
		catch (IOException theE)
		{
			myLogger.error("copyFile:", theE);
		}
		return theDestination;
	}

	private void sendNotification(String theFileName, Concept theConcept)
	{
		if (myNotificationURL == null)
		{
			myLogger.info("Notification null, not sending.");
			return;
		}
		myLogger.info("Sending notification... " + theFileName);
		try
		{
			HttpClientUtil.getSSLDisabledHttpClient().execute(new HttpGet(myNotificationURL));
		}
		catch (Throwable e)
		{
			myLogger.error("Error sending notification", e);
		}
		myLogger.info("Notification Sent " + theFileName);
	}

	private void sendGmail(File theFile, Concept theConcept)
	{
		if (mySendingEmailAccount == null || mySendingEmailPassword == null || myNotificationEmail == null)
		{
			myLogger.info("Not sending email, not configured");
			return;
		}
		try
		{
			Message aMessage = new MimeMessage(getGmailSession());
			aMessage.setFrom(new InternetAddress(mySendingEmailAccount));
			aMessage.setRecipients(Message.RecipientType.TO, InternetAddress.parse(myNotificationEmail));
			aMessage.setSubject("Front Door Motion");
			MimeBodyPart aMimeBodyPart = new MimeBodyPart();
			aMimeBodyPart.setDataHandler(new DataHandler(new FileDataSource(theFile)));
			aMimeBodyPart.setFileName(theFile.getName());
			aMessage.setContent(new MimeMultipart(aMimeBodyPart));
			Transport.send(aMessage);
		} catch (MessagingException e)
		{
			myLogger.error("sendGmail:", e);
		}
	}

	private Session getGmailSession()
	{
		Properties aProperties = new Properties();
		aProperties.put("mail.smtp.auth", "true");
		aProperties.put("mail.smtp.starttls.enable", "true");
		aProperties.put("mail.smtp.host", "smtp.gmail.com");
		aProperties.put("mail.smtp.port", "587");
		return Session.getInstance(aProperties, new Authenticator()
		{
			protected PasswordAuthentication getPasswordAuthentication()
			{
				return new PasswordAuthentication(mySendingEmailAccount, mySendingEmailPassword);
			}
		});
	}
}
