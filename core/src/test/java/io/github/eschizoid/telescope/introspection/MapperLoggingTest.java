package io.github.eschizoid.telescope.introspection;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    return capture(LOGGER, level, body);
  }

  // Same, on an explicit logger name — used for the ForwardMapper (Source.Target too) and the
  // lifted-shell (List.List) cases, which log under different type-pair names.
  private static List<LogRecord> capture(final String loggerName, final Level level, final Runnable body) {
    final var jul = Logger.getLogger(loggerName);
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
  @DisplayName("a forward-only mapper logs the same explain structure and value trace")
  void forwardMapperLogsExplainAndTrace() {
    // ForwardMapper carries its own copy of the logging blocks (not a shared helper), so it needs
    // its own coverage — it must behave identically to the bidirectional Mapper.
    final var records = capture(Level.ALL, () -> {
      final var mapper = Telescope.mapperForward(Source.class, Target.class);
      mapper.forward(new Source("Ada", "Paris"));
    });
    assertTrue(any(records, "Mapped:"), () -> "expected the explain structure at DEBUG; got " + messages(records));
    assertTrue(any(records, "\"Ada\""), () -> "expected the value trace at TRACE; got " + messages(records));
  }

  @Test
  @DisplayName("DEBUG surfaces the structure but not the per-forward values — the levels gate independently")
  void debugLogsStructureNotValues() {
    // JUL FINE == System.Logger DEBUG: explain (construction) is loggable, trace (forward) is not.
    final var records = capture(Level.FINE, () -> {
      final var mapper = Telescope.mapper(Source.class, Target.class);
      mapper.forward(new Source("Ada", "Paris"));
    });
    // Exactly one record: the construction explain. No per-forward value trace leaks in at DEBUG.
    assertEquals(1, records.size(), () -> "expected only the construction explain at DEBUG; got " + messages(records));
    assertEquals(Level.FINE, records.get(0).getLevel(), "the construction log must be at DEBUG (JUL FINE)");
    assertTrue(any(records, "Mapped:"), () -> "expected the explain structure; got " + messages(records));
    assertFalse(any(records, "\"Ada\""), () -> "the value trace must not appear at DEBUG; got " + messages(records));
  }

  @Test
  @DisplayName("a trail-less lifted shell logs nothing even with the level fully on")
  void silentForTrailLessShellEvenAtTrace() {
    // A lifted mapper (List<A> → List<B>) has an empty explain trail, so the !isEmpty() guard keeps
    // it silent at every level — construction and forward alike, even at ALL.
    final var records = capture("io.github.eschizoid.telescope.mapper.List.List", Level.ALL, () -> {
      final var lifted = Telescope.mapperForward(Source.class, Target.class).liftList();
      lifted.forward(List.of(new Source("Ada", "Paris")));
    });
    assertTrue(records.isEmpty(), () -> "a trail-less shell must stay silent even at ALL; got " + messages(records));
  }

  record Fragile(Boom value) {}

  // A field value whose toString() throws an Error (not just a RuntimeException) — the trace render
  // must still degrade to a sentinel, never letting the throwable escape forward() and fail the
  // conversion the log was only meant to observe.
  static final class Boom {

    @Override
    public String toString() {
      throw new AssertionError("toString blew up");
    }
  }

  @Test
  @DisplayName("a field whose toString() throws does not break the conversion at TRACE")
  void traceSurvivesThrowingToString() {
    final var value = new Boom();
    final var records = capture("io.github.eschizoid.telescope.mapper.Fragile.Fragile", Level.ALL, () -> {
      final var mapper = Telescope.mapper(Fragile.class, Fragile.class);
      final var out = assertDoesNotThrow(() -> mapper.forward(new Fragile(value)), "logging must never fail forward()");
      assertEquals(value, out.value(), "the conversion still copies the field through unchanged");
    });
    assertTrue(
      any(records, "(n/a)"),
      () -> "the throwing value renders as the (n/a) sentinel; got " + messages(records)
    );
  }

  private static List<String> messages(final List<LogRecord> records) {
    return records.stream().map(LogRecord::getMessage).toList();
  }
}
