package com.bigboxer23.clarifai;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.bigboxer23.utils.file.FilePersistentIndex;
import com.bigboxer23.utils.http.OkHttpCallback;
import com.bigboxer23.utils.http.OkHttpUtil;
import com.bigboxer23.utils.mail.MailSender;
import com.clarifai.channel.ClarifaiChannel;
import com.clarifai.credentials.ClarifaiCallCredentials;
import com.clarifai.grpc.api.*;
import com.clarifai.grpc.api.status.StatusCode;
import com.google.protobuf.ByteString;
import io.grpc.Channel;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Various actions reside here. */
@Component
@EnableAutoConfiguration
public class AnalysisManager {
	private static final int kMaxApiLimit = 5000;

	private static final Logger myLogger = LoggerFactory.getLogger(AnalysisController.class);

	@Value("${notificationEmail}")
	private String myNotificationEmail;

	@Value("${sendingEmailAccount}")
	private String mySendingEmailAccount;

	@Value("${sendingEmailPassword}")
	private String mySendingEmailPassword;

	@Value("${notificationUrl}")
	private String myNotificationURL;

	@Value("${afterStoredCallback}")
	private String afterStoredCallback;

	@Value("${s3BucketName}")
	private String myS3BucketName;

	@Value("${s3Region}")
	private String myS3Region;

	@Value("${ClarifaiPAT}")
	private String clarifaiPAT;

	@Value("${ClarifaiAppId}")
	private String clarifaiAppId;

	@Value("${ClarifaiUserId}")
	private String clarifaiUserId;

	@Value("${modelId}")
	private String myModelId;

	@Value("${threshold}")
	private double myThreshold = .75;

	private AmazonS3 myAmazonS3Client;

	private Channel myChannel;

	private FilePersistentIndex monthlyAPICount = new FilePersistentIndex("api");

	@Scheduled(cron = "0 0 0 1 1/1 *") // Run first of month at 12am
	private void resetMonthlyCounter() {
		myLogger.info("Resetting monthly API count");
		monthlyAPICount.set(0);
	}

	private int getMaxApiLimitByDayOfMonth(int theDayOfMonth) {
		LocalDate aDate = LocalDate.now();
		int aDayCount = aDate.withDayOfMonth(aDate.getMonth().length(aDate.isLeapYear()))
				.getDayOfMonth();
		return theDayOfMonth * (kMaxApiLimit / aDayCount);
	}

	/**
	 * Since we have a finite number of free calls, limit ourselves by available number based on
	 * number per day
	 *
	 * @return true if we've hit limit and shouldn't allow running
	 */
	private boolean shouldLimitCall() {
		return monthlyAPICount.get()
				> getMaxApiLimitByDayOfMonth(LocalDate.now().getDayOfMonth());
	}

	/**
	 * send file to clarifai for analysis
	 *
	 * @param theFileToAnalyze the file to send
	 * @param theSuccess method to call if clarifai says the image is noteworthy
	 * @param theFailure method to call if clarifai says the image is not noteworthy
	 * @throws InterruptedException
	 */
	public void sendToClarifai(
			File theFileToAnalyze, Consumer<? super File> theSuccess, Consumer<? super File> theFailure)
			throws IOException {
		if (shouldLimitCall()) {
			myLogger.warn("Monthly Clarifai API limit has been hit " + monthlyAPICount.get());
			return;
		}
		if (myChannel == null) {
			myChannel = ClarifaiChannel.INSTANCE.getGrpcChannel();
		}

		V2Grpc.V2BlockingStub aStub =
				V2Grpc.newBlockingStub(myChannel).withCallCredentials(new ClarifaiCallCredentials(clarifaiPAT));
		monthlyAPICount.increment();
		MultiOutputResponse aResponse = aStub.postModelOutputs(PostModelOutputsRequest.newBuilder()
				.setModelId(myModelId)
				.setUserAppId(
						UserAppIDSet.newBuilder().setUserId(clarifaiUserId).setAppId(clarifaiAppId))
				.addInputs(Input.newBuilder()
						.setData(Data.newBuilder()
								.setImage(Image.newBuilder()
										.setBase64(
												ByteString.copyFrom(Files.readAllBytes(theFileToAnalyze.toPath()))))))
				.build());

		if (aResponse.getStatus().getCode() != StatusCode.SUCCESS) {
			throw new RuntimeException("Request failed, status: " + aResponse.getStatus());
		}

		for (Concept aConcept : aResponse.getOutputs(0).getData().getConceptsList()) {
			myLogger.info("Clarifai analysis: "
					+ theFileToAnalyze
					+ " "
					+ (new DecimalFormat("##.00").format(aConcept.getValue() * 100))
					+ "%");
			if (aConcept.getValue() >= myThreshold) {
				myLogger.info("Clarifai success " + theFileToAnalyze.getName());
				theSuccess.accept(theFileToAnalyze);
			} else {
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
	public Optional<URL> moveToS3(File theFile, String theDirectory) {
		myLogger.info("Moving " + theFile + " to S3.");
		if (myAmazonS3Client == null) {
			myLogger.info("creating new client");
			myAmazonS3Client =
					AmazonS3ClientBuilder.standard().withRegion(myS3Region).build();
		}
		String key = theDirectory + getDateString() + theFile.getName();
		try {
			myAmazonS3Client.putObject(new PutObjectRequest(myS3BucketName, key, theFile));
			return Optional.ofNullable(myAmazonS3Client.getUrl(myS3BucketName, key));
		} catch (Exception e) {
			myLogger.error("Problem sending to S3", e);
			myAmazonS3Client = null;
		}
		return Optional.empty();
	}

	/**
	 * Delete the file
	 *
	 * @param theFile
	 */
	public void deleteFile(File theFile) {
		try {
			Files.delete(theFile.toPath());
		} catch (IOException theE) {
			myLogger.error("deleteFile:", theE);
		}
	}

	/**
	 * Notify a webhook of a "good" file
	 *
	 * @param theFileName
	 */
	public void sendNotification() {
		if (myNotificationURL == null) {
			myLogger.info("Notification null, not sending.");
			return;
		}
		myLogger.info("Sending notification... ");
		OkHttpUtil.get(myNotificationURL, new OkHttpCallback());
		myLogger.info("Notification Sent ");
	}

	public void sendAfterStored(Optional<URL> url) {
		if (afterStoredCallback.equals("") || !url.isPresent()) {
			myLogger.info("no after stored callback, returning");
			return;
		}
		String urlString = url.get().toString();
		String bucketURL = myS3BucketName + ".s3.amazonaws.com/";
		urlString = urlString.substring(urlString.indexOf(bucketURL) + bucketURL.length());
		URL preSignedUrl = myAmazonS3Client.generatePresignedUrl(
				myS3BucketName, urlString, Date.from(Instant.now().plus(5, ChronoUnit.MINUTES)));
		OkHttpUtil.post(
				String.format(afterStoredCallback, URLEncoder.encode(preSignedUrl.toString())), new OkHttpCallback());
	}

	/**
	 * Send a email with the attached file list
	 *
	 * @param theFiles
	 */
	public void sendGmail(List<File> theFiles) {
		myLogger.info("Sending mail... " + theFiles.get(0));
		MailSender.sendGmail(
				myNotificationEmail,
				mySendingEmailAccount,
				mySendingEmailPassword,
				"Front Door Motion " + monthlyAPICount.get(),
				null,
				theFiles);
	}

	/**
	 * @return yyyy/MM/ folder, for path sorting
	 */
	private String getDateString() {
		return new SimpleDateFormat("yyyy").format(new Date())
				+ "/"
				+ new SimpleDateFormat("MM").format(new Date())
				+ "/";
	}
}
