package com.bigboxer23.clarifai;

import static org.mockito.Mockito.*;

import java.io.File;
import java.net.URL;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.*;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class SuccessTaskTest {

	private SuccessTask successTask;

	@Mock
	private AnalysisManager analysisManager;

	@Mock
	private AnalysisController analysisController;

	private List<File> mockFiles;

	@BeforeEach
	public void setup() {
		MockitoAnnotations.openMocks(this);
		mockFiles = List.of(new File("/tmp/test.jpg"));
		successTask = new SuccessTask(mockFiles, true, analysisManager, analysisController);
	}

	@Test
	public void testRun_Success() {
		Optional<URL> mockUrl = Optional.of(mock(URL.class));

		when(analysisManager.moveToS3(any(File.class), anyString())).thenReturn(mockUrl);

		successTask.run();

		verify(analysisManager).sendGmail(mockFiles);
		verify(analysisManager).sendAfterStored(mockUrl);
		verify(analysisManager).deleteFile(any(File.class));
		verify(analysisController).clearBatchedFiles();
	}

	@Test
	public void testRun_NoFiles() {
		successTask = new SuccessTask(List.of(), true, analysisManager, analysisController);
		doNothing().when(analysisManager).sendGmail(anyList());

		successTask.run();
		verify(analysisManager, atMostOnce()).sendGmail(anyList());
		verify(analysisManager, never()).sendAfterStored(any());
		verify(analysisManager, never()).deleteFile(any());
		verify(analysisController, atMostOnce()).clearBatchedFiles();
	}
}
