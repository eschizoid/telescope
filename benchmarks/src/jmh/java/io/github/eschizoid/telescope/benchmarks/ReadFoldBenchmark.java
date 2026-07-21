package io.github.eschizoid.telescope.benchmarks;

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
 * The gate benchmark for a push-based read fold: multi-focus read terminals ({@code toList}, {@code
 * count}, {@code exists}) currently ride the {@code Traversal.then} {@code
 * getAll(...).flatMap(...)} Stream pipeline — one single-element Stream per element per lens hop —
 * while the write side was converted to plain loops. The hand rows are the loop floor the proposed
 * {@code Fold#forEach} rewrite would target.
 *
 * <p>How to read it: the telescope-vs-hand ratio per terminal is the available win. {@code
 * existsEarlyHit} additionally probes short-circuiting — if it costs the same as {@code
 * existsMiss}, the fold materializes everything before answering, which is its own finding.
 *
 * <pre>{@code
 * ./gradlew :benchmarks:jmh -Pjmh.includes=ReadFoldBenchmark
 * }</pre>
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class ReadFoldBenchmark {

  public record User(String name, String email) {}

  public record Team(String name, List<User> users) {}

  public record Org(String name, List<Team> teams) {}

  private Org org;
  private Telescope<Org, String> emails;
  private Telescope<Org, String> hitEmails;
  private Telescope<Org, String> missEmails;

  @Setup
  public void setup() {
    final var teams = new ArrayList<Team>(10);
    for (var t = 0; t < 10; t++) {
      final var users = new ArrayList<User>(10);
      for (var u = 0; u < 10; u++) {
        // The very first email carries the early-hit marker; nothing else matches "@first".
        final var marker = (t == 0 && u == 0) ? "hit@first" : "u" + t + "x" + u + "@acme.com";
        users.add(new User("user" + t + "-" + u, marker));
      }
      teams.add(new Team("team" + t, List.copyOf(users)));
    }
    org = new Org("org", List.copyOf(teams));
    emails = Telescope.of(Org.class).each(Org::teams).each(Team::users).field(User::email);
    hitEmails = emails.filter(e -> e.startsWith("hit@"));
    missEmails = emails.filter(e -> e.startsWith("zz@"));
  }

  @Benchmark
  public List<String> toListTelescope() {
    return emails.toList(org);
  }

  @Benchmark
  public long countTelescope() {
    return emails.count(org);
  }

  @Benchmark
  public boolean existsEarlyHit() {
    return hitEmails.exists(org);
  }

  @Benchmark
  public boolean existsMiss() {
    return missEmails.exists(org);
  }

  /** The loop floor for toList. */
  @Benchmark
  public List<String> toListHand() {
    final var out = new ArrayList<String>(100);
    for (final var team : org.teams()) {
      for (final var user : team.users()) {
        out.add(user.email());
      }
    }
    return out;
  }

  /** The loop floor for count. */
  @Benchmark
  public long countHand() {
    var n = 0L;
    for (final var team : org.teams()) {
      n += team.users().size();
    }
    return n;
  }

  /** The loop floor for the early-hit exists. */
  @Benchmark
  public boolean existsHandEarlyHit() {
    for (final var team : org.teams()) {
      for (final var user : team.users()) {
        if (user.email().startsWith("hit@")) return true;
      }
    }
    return false;
  }
}
