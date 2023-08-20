package org.unrealarchive.umodrepack;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

public class Main {

	public static void main(String[] args) throws IOException {
		final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

		final Path jobsPath = Files.createDirectories(Paths.get(
			System.getenv().getOrDefault("JOBS_PATH", "/tmp")
		));
		final Path uploadPath = Files.createDirectories(Paths.get(
			System.getenv().getOrDefault("UPLOAD_PATH", "/tmp/ua-repack-in")
		));

		final SubmissionProcessor subProcessor = new SubmissionProcessor(5, scheduler, jobsPath);

		final WebApp webApp = new WebApp(InetSocketAddress.createUnresolved(
			System.getenv().getOrDefault("BIND_HOST", "localhost"),
			Integer.parseInt(System.getenv().getOrDefault("BIND_PORT", "8081"))
		), subProcessor, uploadPath, System.getenv().getOrDefault("ALLOWED_ORIGIN", "*"));

		// shutdown hook to cleanup repo
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			webApp.close();
			scheduler.shutdownNow();
		}));

	}
}
