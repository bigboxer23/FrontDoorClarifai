package com.bigboxer23.clarifai;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.File;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@WebMvcTest(AnalysisController.class)
public class AnalysisControllerTest {

	@InjectMocks
	private AnalysisController analysisController;

	@MockitoBean
	private AnalysisManager analysisManager;

	@Mock
	private MockMvc mockMvc;

	@Value("${basepath}")
	private String baseAnalysisPath;

	@BeforeEach
	public void setup() {
		MockitoAnnotations.openMocks(this);
		analysisController = new AnalysisController(analysisManager);
		mockMvc = MockMvcBuilders.standaloneSetup(analysisController).build();
	}

	@Test
	void testAnalyzeImage_FileNotFound() throws Exception {
		String filePath = "/invalid/path/to/file.jpg";

		File mockFile = mock(File.class);
		when(mockFile.exists()).thenReturn(false);

		analysisController.analyzeImage(filePath);
		verify(analysisManager, never()).sendToClarifai(any(), any(), any());
	}

	@Test
	void testPause() {
		long delay = 10;
		analysisController.pause(delay);
		assertTrue(analysisController.isPaused() > 0);
	}

	@Test
	void testEnable() {
		analysisController.enable();
		assertEquals(0, analysisController.isPaused());
	}
}
