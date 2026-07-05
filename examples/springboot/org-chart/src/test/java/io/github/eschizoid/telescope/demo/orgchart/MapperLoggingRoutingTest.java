package io.github.eschizoid.telescope.demo.orgchart;

import static io.github.eschizoid.telescope.mapping.WriteHint.WriteStrategy.SETTERS;
import static io.github.eschizoid.telescope.mapping.WriteHint.writeBeans;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.demo.orgchart.domain.Employee;
import io.github.eschizoid.telescope.demo.orgchart.persistence.EmployeeEntity;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * End-to-end proof that a mapper's {@code explain()} / {@code trace()} auto-logging reaches the
 * <b>application's real logging backend</b> driven only by a log level — not just the JDK's default
 * JUL sink covered by core's {@code MapperLoggingTest}.
 *
 * <p>The routing under test is the actual adopter path in a Spring Boot app: telescope logs through
 * {@code java.lang.System.Logger} → (no custom {@code LoggerFinder}, so) {@code java.util.logging}
 * → the {@code jul-to-slf4j} bridge Spring Boot installs at startup → SLF4J → Logback. Booting the
 * context also registers Logback's {@code LevelChangePropagator}, so flipping the mapper's Logback
 * logger propagates down to the JUL level {@code System.Logger} gates on — which is exactly how an
 * adopter turns this on from {@code logback.xml} with no code change.
 *
 * <p><b>Bridge level note.</b> The {@code jul-to-slf4j} bridge maps {@code System.Logger.TRACE}
 * (JUL {@code FINER}) onto SLF4J/Logback {@code DEBUG} — only JUL {@code FINEST} becomes SLF4J
 * {@code TRACE}, and {@code System.Logger} cannot emit below {@code TRACE}. So through the default
 * bridge both lines <em>render</em> at {@code DEBUG}. What still separates them is the configured
 * threshold, and that is the knob adopters actually turn: at {@code DEBUG} only the structure fires
 * ({@code System.Logger.isLoggable(TRACE)} is false, so no value line); raising the logger to
 * {@code TRACE} additionally fires the per-forward values. These two tests pin exactly that toggle,
 * through the real backend.
 */
@SpringBootTest
class MapperLoggingRoutingTest {

  private static final String MAPPER_LOGGER = "io.github.eschizoid.telescope.mapper.Employee.EmployeeEntity";

  @Test
  @DisplayName("DEBUG routes the explain structure through Logback, with the per-forward values still gated off")
  void debugRoutesStructureOnly() {
    final var events = captureAt(Level.DEBUG, MapperLoggingRoutingTest::mapAndForward);
    assertThat(any(events, "Mapped:")).as("explain structure reaches Logback at DEBUG").isTrue();
    assertThat(any(events, "\"Ada\"")).as("values stay gated off until the logger is at TRACE").isFalse();
  }

  @Test
  @DisplayName("TRACE additionally routes the per-forward value trace through Logback")
  void traceRoutesStructureAndValues() {
    final var events = captureAt(Level.TRACE, MapperLoggingRoutingTest::mapAndForward);
    assertThat(any(events, "Mapped:")).as("the structure still reaches the backend").isTrue();
    assertThat(any(events, "\"Ada\"")).as("the real field value reaches Logback once at TRACE").isTrue();
  }

  // Build a fresh mapper (fires the DEBUG explain once, at construction) and run one conversion
  // (fires the per-forward value trace). Same shape EmployeeMappers wires as a bean.
  private static void mapAndForward() {
    final var mapper = Telescope.mapper(Employee.class, EmployeeEntity.class, writeBeans(SETTERS));
    mapper.forward(new Employee(1L, "Ada", Optional.empty(), List.of()));
  }

  // Attach a Logback ListAppender to the type-pair logger at the given level for the duration of
  // `body`, isolated from parent appenders, then restore the logger's prior state.
  private static List<ILoggingEvent> captureAt(final Level level, final Runnable body) {
    final Logger logger = (Logger) LoggerFactory.getLogger(MAPPER_LOGGER);
    final var appender = new ListAppender<ILoggingEvent>();
    appender.start();
    final var priorLevel = logger.getLevel();
    final var priorAdditive = logger.isAdditive();
    logger.setLevel(level);
    logger.setAdditive(false);
    logger.addAppender(appender);
    try {
      body.run();
    } finally {
      logger.detachAppender(appender);
      logger.setAdditive(priorAdditive);
      logger.setLevel(priorLevel);
    }
    return appender.list;
  }

  private static boolean any(final List<ILoggingEvent> events, final String needle) {
    return events.stream().anyMatch(e -> e.getFormattedMessage().contains(needle));
  }
}
