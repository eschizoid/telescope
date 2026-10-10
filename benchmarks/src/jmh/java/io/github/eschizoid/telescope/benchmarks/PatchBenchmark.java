package io.github.eschizoid.telescope.benchmarks;

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
 * {@code patch} with a partial whose fields are all null, which overlays nothing, against a partial
 * that overlays one field.
 *
 * <p>The {@code one_field} rows rebuild the base whatever the all-null rows do, and {@code
 * copy_handwritten} is the same rebuild written by hand. They are the controls: none of them moves
 * between runs on runners of the same speed.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class PatchBenchmark {

  private PatchUser base;
  private PatchUserDto allNull;
  private PatchUserDto oneField;
  private Mapper<PatchUser, PatchUserDto> mapper;

  @Setup
  public void setup() {
    base = new PatchUser("u1", "ann@example.com", "ann", "Lima", "admin");
    allNull = new PatchUserDto(null, null, null, null, null);
    oneField = new PatchUserDto(null, "new@example.com", null, null, null);
    mapper = Telescope.mapper(PatchUser.class, PatchUserDto.class);
  }

  @Benchmark
  public PatchUser copy_handwritten() {
    return new PatchUser(base.id(), base.email(), base.name(), base.city(), base.role());
  }

  @Benchmark
  public PatchUser all_null_codegen() {
    return PatchUserBridge.patch(base, allNull);
  }

  @Benchmark
  public PatchUser all_null_runtime() {
    return mapper.patch(base, allNull);
  }

  @Benchmark
  public PatchUser one_field_codegen() {
    return PatchUserBridge.patch(base, oneField);
  }

  @Benchmark
  public PatchUser one_field_runtime() {
    return mapper.patch(base, oneField);
  }
}
