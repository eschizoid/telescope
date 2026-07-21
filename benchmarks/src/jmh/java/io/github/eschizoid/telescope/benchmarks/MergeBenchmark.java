package io.github.eschizoid.telescope.benchmarks;

import static io.github.eschizoid.telescope.mapping.MergeStep.auto;
import static io.github.eschizoid.telescope.mapping.MergeStep.from;

import io.github.eschizoid.telescope.Sources;
import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.conversion.Mapper;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/**
 * The gate benchmark for build-time binding in {@code Telescope.merge}: today's forward path stages
 * every field through a per-call {@code HashMap}, and {@code auto()}-matched fields additionally
 * pay a per-call name→reader resolution that explicit {@code from(...)} rows already avoid.
 *
 * <p>How to read it: the {@code explicitRows} vs {@code autoRows} split isolates the auto-row
 * resolution tax; {@code handMerge} is the floor (two direct reads per source, one constructor
 * call). The gap between {@code explicitRows} and the floor is the staging-map + name-keyed
 * construct cost a positional bind would target.
 *
 * <pre>{@code
 * ./gradlew :benchmarks:jmh -Pjmh.includes=MergeBenchmark -Pjmh.fork=3   # fork >= 3 for gating reads
 * }</pre>
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class MergeBenchmark {

  public record Customer(String id, String email, String name) {}

  public record Audit(String createdBy, long createdAt, String region) {}

  public record Profile(String id, String email, String name, String createdBy, long createdAt, String region) {}

  private Sources sources;
  private Mapper<Sources, Profile> explicitMerge;
  private Mapper<Sources, Profile> autoMerge;
  private Customer customer;
  private Audit audit;

  @Setup
  public void setup() {
    customer = new Customer("c-1", "a@acme.com", "Ada");
    audit = new Audit("system", 1_700_000_000L, "emea");
    sources = Sources.of(customer, audit);

    explicitMerge = Telescope.merge(
      Profile.class,
      from(Customer::id, Profile::id),
      from(Customer::email, Profile::email),
      from(Customer::name, Profile::name),
      from(Audit::createdBy, Profile::createdBy),
      from(Audit::createdAt, Profile::createdAt),
      from(Audit::region, Profile::region)
    );
    autoMerge = Telescope.merge(Profile.class, auto(Customer.class), auto(Audit.class));
  }

  @Benchmark
  public Profile explicitRows() {
    return explicitMerge.forward(sources);
  }

  @Benchmark
  public Profile autoRows() {
    return autoMerge.forward(sources);
  }

  /** The floor: direct reads, one constructor call, no staging map. */
  @Benchmark
  public Profile handMerge() {
    return new Profile(
      customer.id(),
      customer.email(),
      customer.name(),
      audit.createdBy(),
      audit.createdAt(),
      audit.region()
    );
  }
}
