package com.bigboxer23.clarifai;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.amazonaws.services.s3.AmazonS3;
import com.clarifai.grpc.api.*;
import com.clarifai.grpc.api.status.Status;
import com.clarifai.grpc.api.status.StatusCode;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class AnalysisManagerTest {
	@Mock
	private AmazonS3 s3Client;

	@Mock
	private V2Grpc.V2BlockingStub clarifaiStub;

	private AnalysisManager analysisManager;

	@BeforeEach
	void setUp() {
		MockitoAnnotations.openMocks(this);
		analysisManager = new AnalysisManager(s3Client, clarifaiStub);
	}

	@Test
	void testSendToClarifai_Success() throws IOException {
		File testFile = new File("test.jpg");
		Files.write(testFile.toPath(), "test image data".getBytes());

		MultiOutputResponse mockResponse = MultiOutputResponse.newBuilder()
				.setStatus(Status.newBuilder().setCode(StatusCode.SUCCESS))
				.addOutputs(Output.newBuilder()
						.setData(Data.newBuilder()
								.addConcepts(Concept.newBuilder()
										.setName("Test Concept")
										.setValue(0.80f))))
				.build();

		when(clarifaiStub.postModelOutputs(any(PostModelOutputsRequest.class))).thenReturn(mockResponse);

		Consumer<File> successConsumer = mock(Consumer.class);
		Consumer<File> failureConsumer = mock(Consumer.class);

		analysisManager.sendToClarifai(testFile, successConsumer, failureConsumer);

		verify(successConsumer).accept(testFile);
		verify(failureConsumer, never()).accept(testFile);

		testFile.delete();
	}

	@Test
	void testSendToClarifai_Failure() throws IOException {
		File testFile = new File("test.jpg");
		Files.write(testFile.toPath(), "test image data".getBytes());

		MultiOutputResponse mockResponse = MultiOutputResponse.newBuilder()
				.setStatus(Status.newBuilder().setCode(StatusCode.SUCCESS))
				.addOutputs(Output.newBuilder()
						.setData(Data.newBuilder()
								.addConcepts(Concept.newBuilder()
										.setName("Test Concept")
										.setValue(0.60f))))
				.build();

		when(clarifaiStub.postModelOutputs(any(PostModelOutputsRequest.class))).thenReturn(mockResponse);

		Consumer<File> successConsumer = mock(Consumer.class);
		Consumer<File> failureConsumer = mock(Consumer.class);

		analysisManager.sendToClarifai(testFile, successConsumer, failureConsumer);

		verify(failureConsumer).accept(testFile);
		verify(successConsumer, never()).accept(testFile);

		testFile.delete();
	}

	@Test
	void testSendToClarifai_ErrorResponse() throws IOException {
		File testFile = new File("test.jpg");
		Files.write(testFile.toPath(), "test image data".getBytes());

		MultiOutputResponse mockResponse = MultiOutputResponse.newBuilder()
				.setStatus(Status.newBuilder().setCode(StatusCode.FAILURE))
				.build();

		when(clarifaiStub.postModelOutputs(any(PostModelOutputsRequest.class)))
				.thenReturn(mockResponse);

		Consumer<File> successConsumer = mock(Consumer.class);
		Consumer<File> failureConsumer = mock(Consumer.class);

		try {
			analysisManager.sendToClarifai(testFile, successConsumer, failureConsumer);
			fail("Expected RuntimeException due to Clarifai API failure.");
		} catch (RuntimeException e) {
			assertTrue(e.getMessage().contains("Request failed, status:"));
		}

		testFile.delete();
	}

}
