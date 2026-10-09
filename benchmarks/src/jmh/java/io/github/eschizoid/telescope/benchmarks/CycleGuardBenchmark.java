package io.github.eschizoid.telescope.benchmarks;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.conversion.Mapper;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/**
 * Converting a type that reaches itself, whose generated bridge tracks the objects it is
 * converting. {@code chain} is {@code length} employees each managed by the next, the last by
 * nobody; {@code ring} is the same with the last managed by the first, so the conversion meets an
 * object it is already converting and ends there.
 *
 * <p>{@code chain_handwritten} copies the chain with a plain recursive method and no tracking. It
 * is the control: the generated and runtime rows share its work, and it does not move between runs
 * on runners of the same speed.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class CycleGuardBenchmark {

  @Param({ "8", "64" })
  public int length;

  private CycleEmployee chain;
  private CycleEmployee ring;
  private Mapper<CycleEmployee, CycleEmployeeDto> mapper;

  @Setup
  public void setup() {
    chain = employees(false);
    ring = employees(true);
    mapper = Telescope.mapper(CycleEmployee.class, CycleEmployeeDto.class);
  }

  private CycleEmployee employees(final boolean closed) {
    final var first = new CycleEmployee();
    first.setName("e0");
    var last = first;
    for (var i = 1; i < length; i++) {
      final var next = new CycleEmployee();
      next.setName("e" + i);
      last.setManager(next);
      last = next;
    }
    if (closed) last.setManager(first);
    return first;
  }

  private static CycleEmployeeDto copy(final CycleEmployee employee) {
    if (employee == null) return null;
    final var out = new CycleEmployeeDto();
    out.setName(employee.getName());
    out.setManager(copy(employee.getManager()));
    return out;
  }

  @Benchmark
  public CycleEmployeeDto chain_handwritten() {
    return copy(chain);
  }

  @Benchmark
  public CycleEmployeeDto chain_codegen() {
    return CycleEmployeeBridge.forward(chain);
  }

  @Benchmark
  public CycleEmployeeDto chain_runtime() {
    return mapper.forward(chain);
  }

  @Benchmark
  public CycleEmployeeDto ring_codegen() {
    return CycleEmployeeBridge.forward(ring);
  }

  @Benchmark
  public CycleEmployeeDto ring_runtime() {
    return mapper.forward(ring);
  }
}
