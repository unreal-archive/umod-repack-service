package org.unrealarchive.umodrepack;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.shrimpworks.unreal.packages.Umod;

import org.unrealarchive.common.ArchiveUtil;
import org.unrealarchive.common.Util;

public class SubmissionProcessor implements Closeable {

	private static final Logger logger = LoggerFactory.getLogger(SubmissionProcessor.class);

	private static final PendingSubmission[] PENDING_ARRAY = {};
	private static final Duration POLL_WAIT = Duration.ofSeconds(5);
	private static final Duration SWEEP_RATE = Duration.ofSeconds(120);
	private static final Duration SWEEP_AGE = Duration.ofHours(36);
	private static final Duration FILE_AGE = Duration.ofHours(12);

	private static final Duration EXTRACT_TIMEOUT = Duration.ofSeconds(30);
	private static final Set<String> UMOD_FILES = Set.of("umod", "ut2mod", "ut4mod");

	static final ObjectMapper MAPPER = new ObjectMapper();

	static {
		MAPPER.configure(SerializationFeature.INDENT_OUTPUT, true);
	}

	private final BlockingDeque<PendingSubmission> pending;
	private final Path jobsPath;
	private final Map<String, Submissions.Job> jobs;

	private volatile boolean stopped;

	public SubmissionProcessor(int queueSize, ScheduledExecutorService executor, Path jobsPath) {
		this.jobs = new HashMap<>();
		this.pending = new LinkedBlockingDeque<>(queueSize);
		this.jobsPath = jobsPath;

		this.stopped = false;

		final Runnable processor = new Runnable() {
			@Override
			public void run() {
				if (stopped) return;

				try {
					PendingSubmission sub = pending.pollFirst(POLL_WAIT.toMillis(), TimeUnit.MILLISECONDS);
					if (sub != null) {
						try {
							sub.job.log("Picked up for processing");
							process(sub);
						} catch (Exception e) {
							sub.job.log(Submissions.JobState.FAILED, String.format("Failed to process submission: %s", e.getMessage()), e);
							logger.warn("Submission processing failure", e);
						} finally {
							writeJob(sub);
						}
					}
				} catch (InterruptedException e) {
					logger.warn("Submission queue processing failure", e);
				}

				if (!stopped) executor.submit(this);
			}
		};

		final Runnable cleaner = () -> {
			if (stopped) return;
			jobs.entrySet().removeIf(e -> {
				Submissions.Job job = e.getValue();
				Submissions.LogEntry last = job.log.get(job.log.size() - 1);

				// clean up old repacked files
				job.repackedFiles.entrySet().removeIf(f -> {
					if (last.time < System.currentTimeMillis() - FILE_AGE.toMillis()) {
						try {
							Files.deleteIfExists(f.getValue());
						} catch (IOException ex) {
							logger.warn("Failed to delete old file " + f.getValue(), ex);
						}
						return true;
					}
					return false;
				});

				return last.time < System.currentTimeMillis() - SWEEP_AGE.toMillis();
			});
		};

		executor.submit(processor);
		executor.scheduleAtFixedRate(cleaner, SWEEP_RATE.toMillis(), SWEEP_RATE.toMillis(), TimeUnit.MILLISECONDS);

		logger.info("Submission processor started");
	}

	// --- public methods

	public PendingSubmission[] pending() {
		return pending.toArray(PENDING_ARRAY);
	}

	public boolean trackJob(Submissions.Job job) {
		return this.jobs.put(job.id, job) == null;
	}

	public Collection<Submissions.Job> jobs() {
		return Collections.unmodifiableCollection(jobs.values());
	}

	public Submissions.Job job(String jobId) {
		return jobs.get(jobId);
	}

	public boolean add(PendingSubmission submission) {
		return pending.offerLast(submission);
	}

	@Override
	public void close() {
		stopped = true;
	}

	// --- private helpers

	private void writeJob(PendingSubmission submission) {
		try {
			final String fName = String.format("%d-%s.json", submission.submitTime, submission.job.id);
			Files.write(jobsPath.resolve(fName), MAPPER.writeValueAsBytes(submission));
		} catch (Exception e) {
			logger.warn("Failed to write job file", e);
		}
	}

	private void process(PendingSubmission submission) {
		switch (submission.job.state) {
			case CREATED -> repack(submission);
			default -> submission.job.log("Invalid processing state " + submission.job.state, Submissions.LogType.ERROR);
		}
	}

	private void repack(PendingSubmission submission) {
		try {
			// keep track of whether there were actually any UMOD files submitted, and use to report final status
			boolean hasUmods = true;
			for (Path in : submission.files) {
				hasUmods = hasUmods && repackFile(submission.job, in);
			}
			if (hasUmods) submission.job.log(Submissions.JobState.COMPLETED, "File processing completed", Submissions.LogType.GOOD);
			else submission.job.log(Submissions.JobState.NO_UMOD, "There were no UMOD files to process", Submissions.LogType.WARN);
		} catch (Exception e) {
			submission.job.log(Submissions.JobState.FAILED, "There was an error", e);
		} finally {
			fileCleanup(submission);
		}
	}

	private boolean repackFile(Submissions.Job job, Path in) throws IOException, InterruptedException {
		Path f = in;
		boolean extracted = false;
		boolean[] hasUmod = { false };
		try {
			if (ArchiveUtil.isArchive(in)) {
				extracted = true;
				f = ArchiveUtil.extract(in, Files.createTempDirectory("ua-umod-extract"), EXTRACT_TIMEOUT);
			}

			Files.walkFileTree(f, new SimpleFileVisitor<>() {
				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
					if (UMOD_FILES.contains(Util.extension(file))) {
						hasUmod[0] = true;
						job.log(Submissions.JobState.BUSY, "Found a UMOD file " + Util.fileName(file));
						Path unpacked = null;
						try {
							unpacked = unpackUmod(file);

							Path zipped = Files.createTempDirectory("ua-umod-out")
											   .resolve(Util.safeFileName(file.getFileName().toString()) + ".zip");

							job.log("Creating zip file " + Util.fileName(zipped));

							job.repackedFile(ArchiveUtil.createZip(unpacked, zipped, EXTRACT_TIMEOUT));
						} catch (InterruptedException e) {
							throw new RuntimeException(e);
						} finally {
							if (unpacked != null) ArchiveUtil.cleanPath(unpacked);
						}
					}
					return FileVisitResult.CONTINUE;
				}
			});
		} finally {
			// if we had to extract an archive, clean up the extracted files
			if (extracted) ArchiveUtil.cleanPath(f);
		}

		return hasUmod[0];
	}

	private Path unpackUmod(Path umodFile) throws IOException {
		Path dest = Files.createTempDirectory("ua-umod-unpacked").resolve(Util.safeFileName(umodFile.getFileName().toString()));

		try (Umod umod = new Umod(umodFile)) {
			ByteBuffer buffer = ByteBuffer.allocate(1024 * 8);
			for (Umod.UmodFile f : umod.files) {
				if (f.name.startsWith("System\\Manifest")) continue;

				Path out = dest.resolve(Util.filePath(f.name));

				if (!Files.exists(out)) Files.createDirectories(out);

				out = out.resolve(Util.fileName(f.name));

				try (FileChannel fileChannel = FileChannel.open(out, StandardOpenOption.WRITE, StandardOpenOption.CREATE,
																StandardOpenOption.TRUNCATE_EXISTING);
					 SeekableByteChannel fileData = f.read()) {

					while (fileData.read(buffer) > 0) {
						fileData.read(buffer);
						buffer.flip();
						fileChannel.write(buffer);
						buffer.clear();
					}
				}
			}
		}

		return dest;
	}

	private void fileCleanup(PendingSubmission submission) {
		for (Path file : submission.files) {
			try {
				Files.deleteIfExists(file);
			} catch (IOException e) {
				logger.warn("Failed to delete file {} for job {}", file, submission.job.id);
			}
		}
	}

	public record PendingSubmission(Submissions.Job job, long submitTime, String name, Path[] files) {
	}
}
