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
import com.bigboxer23.util.http.InternalSSLHttpClient;
import org.apache.http.client.methods.HttpGet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
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
import java.util.*;
import java.util.function.Consumer;

/**
 * Various actions reside here.
 */
@RestController
@EnableAutoConfiguration
public class AnalysisManager
{
	private static final Logger myLogger = LoggerFactory.getLogger(AnalysisController.class);

	@Value("${notificationEmail}")
	private String myNotificationEmail;

	@Value("${sendingEmailAccount}")
	private String mySendingEmailAccount;

	@Value("${sendingEmailPassword}")
	private String mySendingEmailPassword;

	@Value("${notificationUrl}")
	private String myNotificationURL;

	@Value("${s3BucketName}")
	private String myS3BucketName;

	@Value("${s3Region}")
	private String myS3Region;

	@Value("${APIKey}")
	private String myClarifaiAPIKey;

	@Value("${modelId}")
	private String myModelId;

	@Value("${threshold}")
	private double myThreshold = .75;

	private AmazonS3 myAmazonS3Client;

	private ClarifaiClient myClarifaiClient;

	private Session myMailSession;

	private InternalSSLHttpClient myHttpClient;

	/**
	 * send file to clarifai for analysis
	 *
	 * @param theFileToAnalyze the file to send
	 * @param theSuccess method to call if clarifai says the image is noteworthy
	 * @param theFailure method to call if clarifai says the image is not noteworthy
	 * @throws InterruptedException
	 */
	public void sendToClarifai(File theFileToAnalyze, Consumer<? super File> theSuccess, Consumer<? super File> theFailure) throws InterruptedException
	{
		if (myClarifaiClient == null)
		{
			myClarifaiClient = new ClarifaiBuilder(myClarifaiAPIKey).buildSync();
		}
		try
		{
			List<ClarifaiOutput<Prediction>> aResults = myClarifaiClient.predict(myModelId).
					withInputs(ClarifaiInput.forImage(theFileToAnalyze)).
					executeSync().get();
			aResults.forEach(thePrediction ->
			{
				thePrediction.data().forEach(theValue ->
				{
					Concept aConcept = theValue.asConcept();
					myLogger.info("Clarifai analysis: " + theFileToAnalyze + " " + (new DecimalFormat("##.00").format(aConcept.value() * 100)) + "%");
					if (aConcept.value() >= myThreshold)
					{
						myLogger.info("Clarifai success " + theFileToAnalyze.getName());
						theSuccess.accept(theFileToAnalyze);
					} else
					{
						myLogger.info("Clarifai failure " + theFileToAnalyze.getName());
						theFailure.accept(theFileToAnalyze);
					}
				});
			});
		} catch (ClarifaiException | NoSuchElementException theException)
		{
			myLogger.error("Error sending to clarifai, trying again ", theException);
			myClarifaiClient = null;
			Thread.sleep(5000);
			sendToClarifai(theFileToAnalyze, theSuccess, theFailure);
		}
	}

	/**
	 * Send the file to S3 for storage
	 *
	 * @param theFile
	 * @param theDirectory
	 */
	public void moveToS3(File theFile, String theDirectory)
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

	/**
	 * Delete the file
	 *
	 * @param theFile
	 */
	public void deleteFile(File theFile)
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

	/**
	 * Notify a webhook of a "good" file
	 *
	 * @param theFileName
	 */
	public void sendNotification(String theFileName)
	{
		if (myNotificationURL == null)
		{
			myLogger.info("Notification null, not sending.");
			return;
		}
		if (myHttpClient == null)
		{
			myHttpClient = new InternalSSLHttpClient();
		}
		myLogger.info("Sending notification... " + theFileName);
		try
		{
			myHttpClient.execute(new HttpGet(myNotificationURL));
		}
		catch (Throwable e)
		{
			myLogger.error("Error sending notification", e);
			myHttpClient = null;
		}
		myLogger.info("Notification Sent " + theFileName);
	}

	/**
	 * Send a email with the attached file list
	 *
	 * @param theFiles
	 */
	public void sendGmail(List<File> theFiles)
	{
		if (mySendingEmailAccount == null || mySendingEmailPassword == null || myNotificationEmail == null)
		{
			myLogger.info("Not sending email, not configured");
			return;
		}
		if (myMailSession == null)
		{
			Properties aProperties = new Properties();
			aProperties.put("mail.smtp.auth", "true");
			aProperties.put("mail.smtp.starttls.enable", "true");
			aProperties.put("mail.smtp.host", "smtp.gmail.com");
			aProperties.put("mail.smtp.port", "587");
			myMailSession = Session.getInstance(aProperties, new Authenticator()
			{
				@Override
				protected PasswordAuthentication getPasswordAuthentication()
				{
					return new PasswordAuthentication(mySendingEmailAccount, mySendingEmailPassword);
				}
			});
		}
		myLogger.info("Sending mail... " + theFiles.get(0));
		try
		{
			Message aMessage = new MimeMessage(myMailSession);
			aMessage.setFrom(new InternetAddress(mySendingEmailAccount));
			aMessage.setRecipients(Message.RecipientType.TO, InternetAddress.parse(myNotificationEmail));
			aMessage.setSubject("Front Door Motion");
			List<MimeBodyPart> aFiles = new ArrayList<>();
			for (File aFile : theFiles)
			{
				MimeBodyPart aMimeBodyPart = new MimeBodyPart();
				aMimeBodyPart.setDataHandler(new DataHandler(new FileDataSource(aFile)));
				aMimeBodyPart.setFileName(aFile.getName());
				aFiles.add(aMimeBodyPart);
			}
			aMessage.setContent(new MimeMultipart(aFiles.toArray(new MimeBodyPart[0])));
			Transport.send(aMessage);
		} catch (MessagingException e)
		{
			myLogger.error("sendGmail:", e);
			myMailSession = null;
		}
	}

	private String getDateString()
	{
		return new SimpleDateFormat("yyyy-MM").format(new Date()) + "/";
	}
}
