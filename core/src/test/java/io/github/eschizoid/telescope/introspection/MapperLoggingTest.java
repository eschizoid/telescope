package io.github.eschizoid.telescope.introspection;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.Telescope;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for the {@code System.Logger} auto-logging: a mapper logs its {@code explain()}
 * structure once at DEBUG on construction and its {@code trace()} value column at TRACE on each
 * {@code forward()}. Captured through the default JUL-backed {@code System.Logger} (TRACE maps to
 * JUL {@code FINER}). The point is that flipping a log level surfaces the same introspection output
 * with no code change — and that it is silent (and free) when the level is off.
 */
class MapperLoggingTest {

  record Source(String name, String city) {}

  record Target(String name, String city) {}

  private static final String LOGGER = "io.github.eschizoid.telescope.mapper.Source.Target";

  // Attach a capturing JUL handler at the given level for the duration of `body`, then restore.
  private static List<LogRecord> capture(final Level level, final Runnable body) {
    final var jul = Logger.getLogger(LOGGER);
    final var priorLevel = jul.getLevel();
    final var priorParent = jul.getUseParentHandlers();
    final var records = new ArrayList<LogRecord>();
    final var handler = new Handler() {
      @Override
      public void publish(final LogRecord record) {
        records.add(record);
      }

      @Override
      public void flush() {}

      @Override
      public void close() {}
    };
    jul.setLevel(level);
    // Isolate: don't let records propagate to the root logger's handlers (keeps CI logs quiet).
    jul.setUseParentHandlers(false);
    jul.addHandler(handler);
    try {
      body.run();
    } finally {
      jul.removeHandler(handler);
      jul.setLevel(priorLevel);
      jul.setUseParentHandlers(priorParent);
    }
    return records;
  }

  private static boolean any(final List<LogRecord> records, final String needle) {
    return records.stream().anyMatch(r -> String.valueOf(r.getMessage()).contains(needle));
  }

  @Test
  @DisplayName("DEBUG logs the explain structure once at construction; TRACE logs the value trace per forward")
  void logsExplainAndTrace() {
    final var records = capture(Level.ALL, () -> {
      final var mapper = Telescope.mapper(Source.class, Target.class);
      mapper.forward(new Source("Ada", "Paris"));
    });
    // Structure (explain) from the DEBUG build log, values (trace) from the TRACE forward log.
    assertTrue(any(records, "Mapped:"), () -> "expected the explain structure at DEBUG; got " + messages(records));
    assertTrue(any(records, "\"Ada\""), () -> "expected the value trace at TRACE; got " + messages(records));
  }

  @Test
  @DisplayName("nothing is logged (and no trace is rendered) when the level is off")
  void silentWhenOff() {
    final var records = capture(Level.OFF, () -> {
      final var mapper = Telescope.mapper(Source.class, Target.class);
      mapper.forward(new Source("Ada", "Paris"));
    });
    assertTrue(records.isEmpty(), () -> "expected no records when the level is off; got " + messages(records));
  }

  private static List<String> messages(final List<LogRecord> records) {
    return records.stream().map(LogRecord::getMessage).toList();
  }
}
