package io.github.eschizoid.telescope.demo.spring;

import static io.github.eschizoid.telescope.mapping.Mapping.to;
import static io.github.eschizoid.telescope.mapping.Mapping.via;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.demo.api.ItemsV2;
import io.github.eschizoid.telescope.demo.api.NewLineV2;
import io.github.eschizoid.telescope.demo.api.PlaceOrder;
import io.github.eschizoid.telescope.demo.api.PlaceOrderV2;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * A mapper narrates through the application's own logging when its logger's level is turned up,
 * with no code change. Telescope logs through {@code System.Logger}, which in a Spring Boot app
 * reaches Logback through the {@code jul-to-slf4j} bridge Spring Boot installs, and Logback's level
 * change propagates back to the level {@code System.Logger} checks.
 *
 * <p>The bridge maps {@code System.Logger.TRACE} onto Logback's {@code DEBUG}, so both lines arrive
 * at {@code DEBUG}. What separates them is the threshold: at {@code DEBUG} only the structure is
 * logged, once, when the mapper is built; at {@code TRACE} each conversion's values are logged too.
 *
 * <p>The mappers are the version 2 request's rows built at run time: a {@code @Bridge} field runs
 * its generated bridge, whose report says only which class runs.
 */
@SpringBootTest
class MapperLoggingTest {

  private static final String LOGGER = "io.github.eschizoid.telescope.mapper.PlaceOrderV2.PlaceOrder";

  @Test
  @DisplayName("at DEBUG the mapper logs what it pairs, and no values")
  void debugLogsTheStructure() {
    final var events = captureAt(Level.DEBUG);
    assertThat(any(events, "buyerEmail")).as("the structure reaches Logback at DEBUG").isTrue();
    assertThat(any(events, "grace@example.com")).as("values stay off until TRACE").isFalse();
  }

  @Test
  @DisplayName("at TRACE the mapper also logs each conversion's values")
  void traceLogsTheValues() {
    final var events = captureAt(Level.TRACE);
    assertThat(any(events, "buyerEmail")).as("the structure still reaches Logback").isTrue();
    assertThat(any(events, "grace@example.com")).as("the value reaches Logback at TRACE").isTrue();
  }

  private static void mapOneRequest() {
    final var mapper = Telescope.mapper(
      PlaceOrderV2.class,
      PlaceOrder.class,
      to(PlaceOrderV2::buyerEmail, PlaceOrder::customerEmail),
      to(PlaceOrderV2::coupon, PlaceOrder::discountCode),
      via(PlaceOrderV2::items, PlaceOrder::lines, ItemsV2::forward, ItemsV2::backward)
    );
    mapper.forward(
      new PlaceOrderV2("grace@example.com", List.of(new NewLineV2("TEE-02", 1)), null, false, null, null, Set.of())
    );
  }

  private static List<ILoggingEvent> captureAt(final Level level) {
    final var logger = (Logger) LoggerFactory.getLogger(LOGGER);
    final var appender = new ListAppender<ILoggingEvent>();
    appender.start();
    final var priorLevel = logger.getLevel();
    final var priorAdditive = logger.isAdditive();
    logger.setLevel(level);
    logger.setAdditive(false);
    logger.addAppender(appender);
    try {
      mapOneRequest();
    } finally {
      logger.detachAppender(appender);
      logger.setAdditive(priorAdditive);
      logger.setLevel(priorLevel);
    }
    return appender.list;
  }

  private static boolean any(final List<ILoggingEvent> events, final String needle) {
    return events.stream().anyMatch(event -> event.getFormattedMessage().contains(needle));
  }
}
