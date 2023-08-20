package org.unrealarchive.umodrepack;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;
import static org.unrealarchive.umodrepack.SubmissionProcessor.MAPPER;
import static org.unrealarchive.umodrepack.Submissions.JobState.CREATED;
import static org.wildfly.common.Assert.assertFalse;

public class WebAppTest {

	private static final int APP_PORT = 58974 + (int)(Math.random() * 1000);

	private final SubmissionProcessor mockProcessor;

	public WebAppTest() {
		this.mockProcessor = Mockito.mock(SubmissionProcessor.class);
	}

	@Test
	void testUploadWithForcedType() throws IOException, InterruptedException {
		HttpClient c = HttpClient.newHttpClient();

		Path uploadPath = Files.createTempDirectory("ua-test-upload");

		try (WebApp ignored = new WebApp(InetSocketAddress.createUnresolved("127.0.0.1", APP_PORT),
										 mockProcessor, uploadPath, "*")) {

			MultiPartBodyPublisher bp = new MultiPartBodyPublisher();
			bp.addPart("files", () -> getClass().getResourceAsStream("test.txt"), "test.txt", "text/plain")
			  .addPart("forceType", "map");

			HttpRequest uploadReq = HttpRequest.newBuilder()
											   .uri(URI.create("http://127.0.0.1:" + APP_PORT + "/upload"))
											   .header("Content-Type", "multipart/form-data; boundary=" + bp.getBoundary())
											   .POST(bp.build())
											   .build();
			String result = c.send(uploadReq, HttpResponse.BodyHandlers.ofString()).body();
			assertFalse(result.isBlank());

			ArgumentCaptor<Submissions.Job> jobCapture = ArgumentCaptor.forClass(Submissions.Job.class);
			Mockito.verify(mockProcessor).trackJob(jobCapture.capture());

			assertEquals(CREATED, jobCapture.getValue().state);

			when(mockProcessor.job(jobCapture.getValue().id)).thenReturn(jobCapture.getValue());

			HttpRequest jobReq = HttpRequest.newBuilder()
											.uri(URI.create("http://127.0.0.1:" + APP_PORT + "/job/" + jobCapture.getValue().id))
											.GET()
											.build();
			result = c.send(jobReq, HttpResponse.BodyHandlers.ofString()).body();
			assertFalse(result.isBlank());

			Submissions.Job gotJob = MAPPER.readValue(result, Submissions.Job.class);
			assertEquals(jobCapture.getValue().id, gotJob.id);
		} finally {
			Files.deleteIfExists(uploadPath);
		}
	}
}
