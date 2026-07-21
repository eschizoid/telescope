package io.github.eschizoid.telescope.benchmarks;

import static io.github.eschizoid.telescope.Edit.over;

import io.github.eschizoid.telescope.Telescope;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/**
 * The decision benchmark for multi-edit spine fusion: {@code Telescope.all(over(...), ...)} runs
 * one full structural pass per edit today, so k edits pay k rebuilds of the same spine. This
 * measures what that N-pass tax actually is, against a hand-fused single rebuild as the floor.
 *
 * <p>How to read it: if {@code allFlat4Edits} sits near 4× {@code allFlat1Edit} while {@code
 * handFusedFlat4} sits near 1×, the redundancy is real and a lattice-level fusion (the {@code
 * Traversal#structure()} extension) has that headroom. If the per-edit increment is small next to
 * fixed dispatch overhead, fusion is not worth building — that verdict is the point.
 *
 * <p>The tree rows repeat the question where it costs most: k edits under a shared {@code
 * .each(...)} prefix over a 100-element list rebuild the whole container k times.
 *
 * <pre>{@code
 * ./gradlew :benchmarks:jmh -Pjmh.includes=MultiEditBenchmark
 * }</pre>
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class MultiEditBenchmark {

  /** Flat six-field record — the disjoint-lens fusion case. */
  public record Flat(String a, String b, String c, String d, int e, int f) {}

  /** One user leaf for the tree case. */
  public record User(String name, String email, int age) {}

  /** The shared-prefix container the tree edits fan over. */
  public record Org(String title, List<User> users) {}

  private Flat flat;
  private Org org;

  private Telescope<Flat, Flat> flat1;
  private Telescope<Flat, Flat> flat2;
  private Telescope<Flat, Flat> flat4;
  private Telescope<Org, Org> tree1;
  private Telescope<Org, Org> tree3;

  @Setup
  public void setup() {
    flat = new Flat("Alpha", "Beta", "Gamma", "Delta", 41, 7);
    final var users = new ArrayList<User>(100);
    for (var i = 0; i < 100; i++) {
      users.add(new User(" Name" + i + " ", "USER" + i + "@ACME.COM", i));
    }
    org = new Org("org", List.copyOf(users));

    final var fa = Telescope.of(Flat.class).field(Flat::a);
    final var fb = Telescope.of(Flat.class).field(Flat::b);
    final var fc = Telescope.of(Flat.class).field(Flat::c);
    final var fd = Telescope.of(Flat.class).field(Flat::d);
    flat1 = Telescope.all(over(fa, String::toLowerCase));
    flat2 = Telescope.all(over(fa, String::toLowerCase), over(fb, String::trim));
    flat4 = Telescope.all(
      over(fa, String::toLowerCase),
      over(fb, String::trim),
      over(fc, String::toUpperCase),
      over(fd, String::strip)
    );

    final var emails = Telescope.of(Org.class).each(Org::users).field(User::email);
    final var names = Telescope.of(Org.class).each(Org::users).field(User::name);
    final var ages = Telescope.of(Org.class).each(Org::users).field(User::age);
    tree1 = Telescope.all(over(emails, String::toLowerCase));
    tree3 = Telescope.all(over(emails, String::toLowerCase), over(names, String::trim), over(ages, a -> a + 1));
  }

  @Benchmark
  public Flat allFlat1Edit() {
    return flat1.apply(flat);
  }

  @Benchmark
  public Flat allFlat2Edits() {
    return flat2.apply(flat);
  }

  @Benchmark
  public Flat allFlat4Edits() {
    return flat4.apply(flat);
  }

  /** The fusion floor: all four flat edits in one constructor call. */
  @Benchmark
  public Flat handFusedFlat4() {
    return new Flat(
      flat.a().toLowerCase(),
      flat.b().trim(),
      flat.c().toUpperCase(),
      flat.d().strip(),
      flat.e(),
      flat.f()
    );
  }

  @Benchmark
  public Org allTree1Edit() {
    return tree1.apply(org);
  }

  @Benchmark
  public Org allTree3Edits() {
    return tree3.apply(org);
  }

  /** The tree fusion floor: one pass over the container, all three leaf edits per element. */
  @Benchmark
  public Org handFusedTree3() {
    final var users = org.users();
    final var out = new ArrayList<User>(users.size());
    for (final var u : users) {
      out.add(new User(u.name().trim(), u.email().toLowerCase(), u.age() + 1));
    }
    return new Org(org.title(), List.copyOf(out));
  }
}
