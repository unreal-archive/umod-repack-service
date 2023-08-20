package org.unrealarchive.umodrepack;

import java.beans.ConstructorProperties;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.unrealarchive.common.Util;

public class Submissions {

	public enum LogType {
		INFO,
		WARN,
		ERROR,
		GOOD
	}

	public enum JobState {
		CREATED,
		BUSY,
		NO_UMOD,
		FAILED,
		COMPLETED;

		private static final Set<JobState> DONE_STATES = Set.of(
			NO_UMOD, FAILED, COMPLETED
		);

		public boolean done() {
			return DONE_STATES.contains(this);
		}
	}

	public static class Job {

		private static final Logger logger = LoggerFactory.getLogger(Job.class);

		public final String id;
		public final List<LogEntry> log;
		public JobState state;
		public boolean done;

		public final Set<String> files;

		public final transient Map<String, Path> repackedFiles = new HashMap<>();

		public final transient BlockingQueue<LogEntry> logEvents;

		@ConstructorProperties({ "id", "log", "state", "files" })
		public Job(String id, List<LogEntry> log, JobState state, Set<String> files) {
			this.id = id;
			this.log = log;
			this.state = state;
			this.files = files;
			this.done = false;

			this.logEvents = new ArrayBlockingQueue<>(20);
		}

		public Job() {
			this(Long.toHexString(Double.doubleToLongBits(Math.random())).substring(8), new ArrayList<>(), JobState.CREATED,
				 new HashSet<>());
			log("Job created with ID " + id);
		}

		public Job repackedFile(Path file) {
			repackedFiles.put(Util.fileName(file), file);
			files.add(Util.fileName(file));

			return this;
		}

		public Job log(JobState state, LogEntry log) {
			this.log.add(log);
			this.state = state;
			this.logEvents.offer(log);

			logger.info("{}: {}", state, log);

			return this;
		}

		public Job log(JobState state, String message) {
			return log(state, new LogEntry(message, LogType.INFO));
		}

		public Job log(JobState state, String message, LogType type) {
			return log(state, new LogEntry(message, type));
		}

		public Job log(JobState state, String message, Throwable error) {
			return log(state, new LogEntry(message, error));
		}

		public Job log(String message) {
			return log(state, new LogEntry(message));
		}

		public Job log(String message, LogType type) {
			return log(state, new LogEntry(message, type));
		}

		public Job log(String message, Throwable error) {
			return log(state, new LogEntry(message, error));
		}

		public List<LogEntry> log() {
			return Collections.unmodifiableList(log);
		}

		public Job pollLog(Duration timeout) throws InterruptedException {
			final LogEntry head = logEvents.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
			final List<LogEntry> polledLogs = new ArrayList<>();
			if (head != null) polledLogs.add(head);
			logEvents.drainTo(polledLogs);

			if (!done && state.done()) done = true;

			return new Job(id, polledLogs, state, files);
		}

		public LogEntry logHead() {
			return log.get(0);
		}

		public LogEntry logTail() {
			return log.get(log.size() - 1);
		}
	}

	public static class LogEntry {

		public final long time;
		public final String message;
		public final Throwable error;
		public final LogType type;

		public LogEntry(String message) {
			this(System.currentTimeMillis(), message, null, LogType.INFO);
		}

		public LogEntry(String message, LogType type) {
			this(System.currentTimeMillis(), message, null, type);
		}

		public LogEntry(String message, Throwable error) {
			this(System.currentTimeMillis(), message, error, LogType.ERROR);
		}

		@ConstructorProperties({ "time", "message", "error", "type" })
		public LogEntry(long time, String message, Throwable error, LogType type) {
			this.time = time;
			this.message = message;
			this.error = error;
			this.type = type;
		}

		@Override
		public String toString() {
			return String.format("[%s] %s %s", time, type, message);
		}
	}
}
