package com.bigboxer23.clarifai;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.bigboxer23.util.http.HttpClientUtils;
import com.clarifai.channel.ClarifaiChannel;
import com.clarifai.credentials.ClarifaiCallCredentials;
import com.clarifai.grpc.api.*;
import com.clarifai.grpc.api.status.StatusCode;
import com.google.protobuf.ByteString;
import io.grpc.Channel;
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

	private Channel myChannel;

	private Session myMailSession;

	/**
	 * send file to clarifai for analysis
	 *
	 * @param theFileToAnalyze the file to send
	 * @param theSuccess method to call if clarifai says the image is noteworthy
	 * @param theFailure method to call if clarifai says the image is not noteworthy
	 * @throws InterruptedException
	 */
	public void sendToClarifai(File theFileToAnalyze, Consumer<? super File> theSuccess, Consumer<? super File> theFailure) throws IOException
	{
		if (myChannel == null)
		{
			myChannel = ClarifaiChannel.INSTANCE.getInsecureGrpcChannel();
		}

		V2Grpc.V2BlockingStub aStub = V2Grpc.newBlockingStub(myChannel)
				.withCallCredentials(new ClarifaiCallCredentials(myClarifaiAPIKey));

		MultiOutputResponse aResponse = aStub.postModelOutputs(
				PostModelOutputsRequest.newBuilder()
						.setModelId(myModelId)
						.addInputs(
								Input.newBuilder().setData(
										Data.newBuilder().setImage(
												Image.newBuilder()
														.setBase64(ByteString.copyFrom(Files.readAllBytes(
																theFileToAnalyze.toPath()
														)))
										)
								)
						)
						.build()
		);

		if (aResponse.getStatus().getCode() != StatusCode.SUCCESS) {
			throw new RuntimeException("Request failed, status: " + aResponse.getStatus());
		}

		for (Concept aConcept : aResponse.getOutputs(0).getData().getConceptsList()) {
			myLogger.info("Clarifai analysis: " + theFileToAnalyze + " " + (new DecimalFormat("##.00").format(aConcept.getValue() * 100)) + "%");
			if (aConcept.getValue() >= myThreshold)
			{
				myLogger.info("Clarifai success " + theFileToAnalyze.getName());
				theSuccess.accept(theFileToAnalyze);
			} else
			{
				myLogger.info("Clarifai failure " + theFileToAnalyze.getName());
				theFailure.accept(theFileToAnalyze);
			}
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
	public void sendNotification()
	{
		if (myNotificationURL == null)
		{
			myLogger.info("Notification null, not sending.");
			return;
		}
		myLogger.info("Sending notification... ");
		HttpClientUtils.execute(new HttpGet(myNotificationURL));
		myLogger.info("Notification Sent ");
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
