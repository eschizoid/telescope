package io.github.eschizoid.telescope.benchmarks;

import io.github.eschizoid.telescope.jackson.JsonBinder;
import io.github.eschizoid.telescope.jackson.TelescopeJson;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.ObjectReadContext;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reading one JSON order into the same record four ways: telescope-jackson's streaming binder,
 * Jackson databind, a loop written by hand over the same {@code jackson-core} parser, and the
 * parser alone.
 *
 * <p>How to read it: {@code tokensOnly} is the parse every row pays and nothing more, and {@code
 * handWritten} is the parse plus the building of the record with every decision made in source, the
 * floor a binder can approach. Both are controls: neither changes with telescope's code, so a
 * difference in either between two runs is a difference in the runners, and every ratio in that
 * pair moves with it. {@code telescope} and {@code databind} are the two being compared. Allocation
 * ({@code -Pjmh.profilers=gc}) does not depend on the runner and is the durable comparison.
 *
 * <pre>{@code
 * ./gradlew :benchmarks:jmh -Pjmh.includes=JsonBindBenchmark -Pjmh.profilers=gc
 * }</pre>
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class JsonBindBenchmark {

  /** A status. */
  public enum JbStatus {
    PLACED,
    PAID,
    SHIPPED,
  }

  /** One line of an order. */
  public record JbLine(String sku, int quantity, BigDecimal price) {}

  /** An order, nested one level. */
  public record JbOrder(
    String number,
    String customerEmail,
    JbStatus status,
    long placedAt,
    boolean gift,
    BigDecimal total,
    List<JbLine> lines
  ) {}

  private static final String JSON =
    "{\"number\":\"SO-1001\",\"customerEmail\":\"ada@example.org\",\"status\":\"PAID\"," +
    "\"placedAt\":1767225600000,\"gift\":false,\"total\":61.50,\"lines\":[" +
    "{\"sku\":\"MUG-01\",\"quantity\":2,\"price\":12.50}," +
    "{\"sku\":\"TEE-02\",\"quantity\":1,\"price\":24.00}," +
    "{\"sku\":\"CAP-03\",\"quantity\":1,\"price\":12.50}]}";

  private static final JsonFactory FACTORY = new JsonFactory();
  private static final JsonBinder<JbOrder> BINDER = TelescopeJson.fromJson(JbOrder.class);
  private static final JsonMapper DATABIND = JsonMapper.builder().build();

  @Benchmark
  public JbOrder telescope() {
    return BINDER.read(JSON);
  }

  @Benchmark
  public JbOrder databind() {
    return DATABIND.readValue(JSON, JbOrder.class);
  }

  @Benchmark
  public JbOrder handWritten() {
    try (final var parser = FACTORY.createParser(ObjectReadContext.empty(), JSON)) {
      parser.nextToken();
      return order(parser);
    }
  }

  @Benchmark
  public void tokensOnly(final Blackhole sink) {
    try (final var parser = FACTORY.createParser(ObjectReadContext.empty(), JSON)) {
      for (var token = parser.nextToken(); token != null; token = parser.nextToken()) {
        sink.consume(token);
      }
    }
  }

  private static JbOrder order(final JsonParser parser) {
    String number = null;
    String email = null;
    JbStatus status = null;
    long placedAt = 0L;
    var gift = false;
    BigDecimal total = null;
    List<JbLine> lines = List.of();
    for (var name = parser.nextName(); name != null; name = parser.nextName()) {
      parser.nextToken();
      switch (name) {
        case "number" -> number = parser.getString();
        case "customerEmail" -> email = parser.getString();
        case "status" -> status = JbStatus.valueOf(parser.getString());
        case "placedAt" -> placedAt = parser.getLongValue();
        case "gift" -> gift = parser.getBooleanValue();
        case "total" -> total = parser.getDecimalValue();
        case "lines" -> {
          final var read = new ArrayList<JbLine>();
          while (parser.nextToken() != JsonToken.END_ARRAY) {
            read.add(line(parser));
          }
          lines = read;
        }
        default -> parser.skipChildren();
      }
    }
    return new JbOrder(number, email, status, placedAt, gift, total, lines);
  }

  private static JbLine line(final JsonParser parser) {
    String sku = null;
    var quantity = 0;
    BigDecimal price = null;
    for (var name = parser.nextName(); name != null; name = parser.nextName()) {
      parser.nextToken();
      switch (name) {
        case "sku" -> sku = parser.getString();
        case "quantity" -> quantity = parser.getIntValue();
        case "price" -> price = parser.getDecimalValue();
        default -> parser.skipChildren();
      }
    }
    return new JbLine(sku, quantity, price);
  }
}
