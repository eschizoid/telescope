package io.github.eschizoid.telescope.benchmarks;

import static io.github.eschizoid.telescope.mapping.MapExtractStep.extract;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.conversion.ForwardMapper;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/**
 * The gate benchmark for a positional {@code fromMap} bind: today's forward closure resolves each
 * target component by name per call ({@code byField.get(name)}, the defaults probe, and the
 * name-keyed {@code Records.construct} / bean-writer fill). A construction-time positional bind
 * would remove the name-keyed hops and leave only the irreducible source {@code map.get(key)}s.
 *
 * <p>How to read it: {@code handPositionalRecord} is the floor (pre-resolved converters + direct
 * constructor call — the shape a positional bind would compile down to). The gap between {@code
 * fromMapRecord} and that floor is the available win; the gap between the floor and zero is the
 * irreducible source-lookup cost that no binding strategy removes.
 *
 * <pre>{@code
 * ./gradlew :benchmarks:jmh -Pjmh.includes=FromMapBenchmark
 * }</pre>
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class FromMapBenchmark {

  /** Six-component record: four extracted rows, two backfilled by JLS defaults. */
  public record Payment(String id, String currency, int amountCents, String reference, long timestamp, int retries) {}

  /** Bean sibling for the setter-writer fill path. */
  public static final class PaymentBean {

    private String id;
    private String currency;
    private int amountCents;
    private String reference;

    public PaymentBean() {}

    public String getId() {
      return id;
    }

    public void setId(final String id) {
      this.id = id;
    }

    public String getCurrency() {
      return currency;
    }

    public void setCurrency(final String currency) {
      this.currency = currency;
    }

    public int getAmountCents() {
      return amountCents;
    }

    public void setAmountCents(final int amountCents) {
      this.amountCents = amountCents;
    }

    public String getReference() {
      return reference;
    }

    public void setReference(final String reference) {
      this.reference = reference;
    }
  }

  private Map<String, Object> source;
  private ForwardMapper<Map<String, Object>, Payment> recordMapper;
  private ForwardMapper<Map<String, Object>, PaymentBean> beanMapper;

  @Setup
  public void setup() {
    source = new HashMap<>();
    source.put("id", "pay-42");
    source.put("currency", "USD");
    source.put("amountCents", 1999);
    source.put("reference", "inv-7");

    recordMapper = Telescope.fromMap(
      Payment.class,
      extract("id", Payment::id, Object::toString),
      extract("currency", Payment::currency, Object::toString),
      extract("amountCents", Payment::amountCents, v -> v == null ? 0 : ((Number) v).intValue()),
      extract("reference", Payment::reference, Object::toString)
    );
    beanMapper = Telescope.fromMap(
      PaymentBean.class,
      extract("id", PaymentBean::getId, Object::toString),
      extract("currency", PaymentBean::getCurrency, Object::toString),
      extract("amountCents", PaymentBean::getAmountCents, v -> v == null ? 0 : ((Number) v).intValue()),
      extract("reference", PaymentBean::getReference, Object::toString)
    );
  }

  @Benchmark
  public Payment fromMapRecord() {
    return recordMapper.forward(source);
  }

  @Benchmark
  public PaymentBean fromMapBean() {
    return beanMapper.forward(source);
  }

  /** The positional floor: the same four source lookups, converters inlined, direct ctor. */
  @Benchmark
  public Payment handPositionalRecord() {
    final var amount = source.get("amountCents");
    return new Payment(
      String.valueOf(source.get("id")),
      String.valueOf(source.get("currency")),
      amount == null ? 0 : ((Number) amount).intValue(),
      String.valueOf(source.get("reference")),
      0L,
      0
    );
  }
}
