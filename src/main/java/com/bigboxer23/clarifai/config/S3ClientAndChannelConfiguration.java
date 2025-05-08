package com.bigboxer23.clarifai.config;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.clarifai.channel.ClarifaiChannel;
import com.clarifai.credentials.ClarifaiCallCredentials;
import com.clarifai.grpc.api.V2Grpc;
import io.grpc.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class S3ClientAndChannelConfiguration {

	private static final Logger logger = LoggerFactory.getLogger(S3ClientAndChannelConfiguration.class);

	@Value("${s3Region}")
	private String myS3Region;

	@Value("${ClarifaiPAT}")
	private String clarifaiPAT;

	@Bean
	public AmazonS3 s3Client() {
		try {
			logger.info("Creating S3 client...");
			return AmazonS3ClientBuilder.standard().withRegion(myS3Region).build();
		} catch (Exception e) {
			logger.error("Failed to create S3 client: {}", e.getMessage(), e);
			return null;
		}
	}

	@Bean
	public Channel clarifaiChannel() {
		logger.info("Creating Clarifai gRPC channel...");
		return ClarifaiChannel.INSTANCE.getGrpcChannel();
	}

	@Bean
	public V2Grpc.V2BlockingStub clarifaiStub(Channel clarifaiChannel) {
		return V2Grpc.newBlockingStub(clarifaiChannel).withCallCredentials(new ClarifaiCallCredentials(clarifaiPAT));
	}
}
