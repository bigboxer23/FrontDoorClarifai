package com.bigboxer23.clarifai;

import clarifai2.api.ClarifaiBuilder;
import clarifai2.api.ClarifaiClient;
import clarifai2.dto.input.ClarifaiInput;
import clarifai2.dto.model.output.ClarifaiOutput;
import clarifai2.dto.prediction.Concept;
import clarifai2.dto.prediction.Prediction;
import clarifai2.exception.ClarifaiException;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.PutObjectRequest;
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
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
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

	@Value("${s3BucketName}")
	private String myS3BucketName;

	@Value("${s3Region}")
	private String myS3Region;

	private AmazonS3 myAmazonS3Client;

	private ClarifaiClient myClarifaiClient;

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
		try
		{
			sendToClarifai(aFileToAnalyze);
		} catch (ClarifaiException theException)
		{
			myLogger.error("Error sending to clarifai, trying again ", theException);
			Thread.sleep(5000);
			sendToClarifai(aFileToAnalyze);
		}
		myLogger.info("Done " + theFileToAnalyze);
	}

	private void sendToClarifai(File theFileToAnalyze)
	{
		if (myClarifaiClient == null)
		{
			myClarifaiClient = new ClarifaiBuilder(myClarifaiAPIKey).buildSync();
		}
		List<ClarifaiOutput<Prediction>> aResults = myClarifaiClient.predict(myModelId).
				withInputs(ClarifaiInput.forImage(theFileToAnalyze)).
				executeSync().get();
		aResults.forEach(thePrediction ->
		{
			thePrediction.data().forEach(theValue ->
			{
				Concept aConcept = theValue.asConcept();
				myLogger.info(theFileToAnalyze + " " + (new DecimalFormat("##.00").format(aConcept.value() * 100)));
				if (aConcept.value() >= myThreshold)
				{
					sendNotification(theFileToAnalyze.getName(), aConcept);
					sendGmail(theFileToAnalyze, aConcept);
					moveToS3(theFileToAnalyze, "Success/");
					deleteFile(theFileToAnalyze);
				} else
				{
					moveToS3(theFileToAnalyze, "Failure/");
					deleteFile(theFileToAnalyze);
				}
			});
		});
	}

	private void moveToS3(File theFile, String theDirectory)
	{
		myLogger.info("Moving " + theFile + " to S3.");
		if (myAmazonS3Client == null)
		{
			myAmazonS3Client = AmazonS3ClientBuilder.standard().withRegion(myS3Region).build();
		}
		try
		{
			myAmazonS3Client.putObject(new PutObjectRequest(myS3BucketName, theDirectory + getDateString() + theFile.getName(), theFile));
		} catch (Exception e)
		{
			myLogger.error("Problem sending to S3", e);
			myAmazonS3Client = null;
		}
	}

	private String getDateString()
	{
		return new SimpleDateFormat("yyyy-MM").format(new Date()) + "/";
	}

	private void deleteFile(File theFile)
	{
		try
		{
			Files.delete(theFile.toPath());
		}
		catch (IOException theE)
		{
			myLogger.error("deleteFile:", theE);
		}
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
