package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.Edit.over;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Telescope#all(Edit[])} — the recommended multi-edit shape. Each {@code
 * over(PATH, fn)} is one edit; {@code all(...)} folds them into a reusable {@code Telescope<S, S>}
 * normalizer whose {@code apply(s)} runs every edit in argument order.
 *
 * <p>Mirrors {@link WithChainTest}'s coverage of the chain accumulator so both shapes are pinned to
 * the same semantics.
 */
class AllOverTest {

  record User(String name, String email) {}

  record Team(String name, List<User> users) {}

  record Department(String name, List<Team> teams) {}

  record Company(String name, List<Department> departments) {}

  private static final Telescope<Company, String> EMAILS = Telescope.of(Company.class)
    .each(Company::departments)
    .each(Department::teams)
    .each(Team::users)
    .field(User::email);

  private static final Telescope<Company, String> DEPT_NAMES = Telescope.of(Company.class)
    .each(Company::departments)
    .field(Department::name);

  private static final Telescope<Company, String> TEAM_NAMES = Telescope.of(Company.class)
    .each(Company::departments)
    .each(Department::teams)
    .field(Team::name);

  private static final Telescope<Company, String> USER_NAMES = Telescope.of(Company.class)
    .each(Company::departments)
    .each(Department::teams)
    .each(Team::users)
    .field(User::name);

  private static Company sample() {
    return new Company(
      "Acme",
      List.of(
        new Department(
          " Engineering ",
          List.of(new Team(" Platform ", List.of(new User("alice", "ALICE@ACME.COM"), new User("bob", "BOB@ACME.COM"))))
        ),
        new Department(" Sales ", List.of(new Team("   APAC   ", List.of(new User("carol", "CAROL@ACME.COM")))))
      )
    );
  }

  @Nested
  @DisplayName("Single edit — equivalent to one path.update(source, fn)")
  class SingleEdit {

    @Test
    @DisplayName("Telescope.all(over(PATH, fn)).apply(s) equals PATH.update(s, fn)")
    void mirrorsDirectUpdate() {
      final var company = sample();
      final var viaAll = Telescope.all(over(EMAILS, String::toLowerCase)).apply(company);
      final var viaDirect = EMAILS.update(company, String::toLowerCase);
      assertEquals(viaDirect, viaAll);
    }
  }

  @Nested
  @DisplayName("Multi-edit — every edit applies, in argument order")
  class MultiEdit {

    @Test
    @DisplayName("two heterogeneous edits both apply")
    void twoEdits() {
      final var company = sample();
      final var out = Telescope.all(over(EMAILS, String::toLowerCase), over(DEPT_NAMES, String::trim)).apply(company);
      assertEquals("Engineering", out.departments().get(0).name());
      assertEquals("Sales", out.departments().get(1).name());
      assertEquals("alice@acme.com", out.departments().get(0).teams().get(0).users().get(0).email());
    }

    @Test
    @DisplayName("four-edit pack applies all leaves in one declarative expression")
    void fourEdits() {
      final var company = sample();
      final var out = Telescope.all(
        over(EMAILS, String::toLowerCase),
        over(DEPT_NAMES, String::trim),
        over(TEAM_NAMES, String::trim),
        over(USER_NAMES, String::toUpperCase)
      ).apply(company);
      assertEquals("Engineering", out.departments().getFirst().name());
      assertEquals("Platform", out.departments().getFirst().teams().get(0).name());
      assertEquals("ALICE", out.departments().getFirst().teams().getFirst().users().getFirst().name());
      assertEquals("alice@acme.com", out.departments().get(0).teams().get(0).users().get(0).email());
    }

    @Test
    @DisplayName("later edits see earlier edits' results, not the original source")
    void laterSeesEarlier() {
      final var company = sample();
      final var out = Telescope.all(over(EMAILS, String::toLowerCase), over(EMAILS, e -> e + "!")).apply(company);
      assertEquals("alice@acme.com!", out.departments().get(0).teams().get(0).users().get(0).email());
    }
  }

  @Nested
  @DisplayName("Empty pack — apply returns the source unchanged")
  class EmptyPack {

    @Test
    @DisplayName("Telescope.all() with no edits is identity on apply")
    void emptyIsIdentity() {
      final var company = sample();
      assertSame(company, Telescope.<Company>all().apply(company));
    }
  }

  @Nested
  @DisplayName("Reusable — the same normalizer applies to many sources")
  class Reuse {

    @Test
    @DisplayName("one Telescope.all(...) value applies independently to many sources")
    void appliedToManySources() {
      final Telescope<Company, Company> normalize = Telescope.all(
        over(EMAILS, String::toLowerCase),
        over(DEPT_NAMES, String::trim)
      );
      final var company1 = sample();
      final var company2 = new Company(
        "Bingo",
        List.of(new Department(" Ops ", List.of(new Team(" SRE ", List.of(new User("zane", "Z@BINGO.COM"))))))
      );
      final var out1 = normalize.apply(company1);
      final var out2 = normalize.apply(company2);
      assertEquals("Engineering", out1.departments().getFirst().name());
      assertEquals("Ops", out2.departments().getFirst().name());
      assertEquals("z@bingo.com", out2.departments().getFirst().teams().getFirst().users().getFirst().email());
    }
  }

  @Nested
  @DisplayName("Equivalence — Telescope.all(over(...)) matches the chain accumulator")
  class EquivalenceWithChain {

    @Test
    @DisplayName("Telescope.all(over(a), over(b)) is end-equivalent to .update(a).update(b)")
    void equivalentToChain() {
      final var company = sample();
      final var viaAll = Telescope.all(over(EMAILS, String::toLowerCase), over(DEPT_NAMES, String::trim)).apply(
        company
      );
      final var viaChain = Telescope.of(Company.class)
        .update(EMAILS, String::toLowerCase)
        .update(DEPT_NAMES, String::trim)
        .apply(company);
      assertEquals(viaChain, viaAll);
    }
  }

  @Nested
  @DisplayName("overIfPresent — sparse-PATCH ergonomics")
  class OverIfPresent {

    @Test
    @DisplayName("non-null direct value replaces the focused leaf")
    void directNonNullReplaces() {
      final var company = sample();
      final var out = Telescope.all(Edit.overIfPresent(DEPT_NAMES, "Engineering")).apply(company);
      assertEquals("Engineering", out.departments().getFirst().name());
    }

    @Test
    @DisplayName("null direct value short-circuits to identity — source returned unchanged")
    void directNullIsIdentity() {
      final var company = sample();
      final var out = Telescope.all(Edit.<Company, String>overIfPresent(DEPT_NAMES, null)).apply(company);
      assertSame(company, out);
    }

    @Test
    @DisplayName("non-null with mapper applies the mapper to the carried value before replacing")
    void mapperFormApplies() {
      final var company = sample();
      final var out = Telescope.all(Edit.overIfPresent(DEPT_NAMES, "  trimmed  ", String::trim)).apply(company);
      assertEquals("trimmed", out.departments().getFirst().name());
    }

    @Test
    @DisplayName("null with mapper does not invoke the mapper and returns identity")
    void mapperNullSkips() {
      final var company = sample();
      final var out = Telescope.all(
        Edit.<Company, String, String>overIfPresent(DEPT_NAMES, null, v -> {
          throw new IllegalStateException("mapper must not run for null value");
        })
      ).apply(company);
      assertSame(company, out);
    }

    @Test
    @DisplayName("mapIfPresent — value steers per-leaf transformation")
    void mapIfPresentCombinesValueWithLeaf() {
      final var company = sample();
      final var out = Telescope.all(
        Edit.mapIfPresent(EMAILS, "@DOMAIN", (suffix, email) -> email + suffix.toLowerCase())
      ).apply(company);
      assertEquals("ALICE@ACME.COM@domain", out.departments().getFirst().teams().getFirst().users().getFirst().email());
    }

    @Test
    @DisplayName("mapIfPresent — null value short-circuits without invoking the transform")
    void mapIfPresentNullSkips() {
      final var company = sample();
      final var out = Telescope.all(
        Edit.<Company, String, String>mapIfPresent(EMAILS, null, (suffix, email) -> {
          throw new IllegalStateException("transform must not run for null value");
        })
      ).apply(company);
      assertSame(company, out);
    }

    @Test
    @DisplayName("Mixed PATCH composition — some null, some not — only the non-null slots land")
    void sparsePatchCompositionLandsOnlyPresentSlots() {
      final var company = sample();
      final var out = Telescope.all(
        Edit.<Company, String>overIfPresent(DEPT_NAMES, null),
        Edit.overIfPresent(TEAM_NAMES, "Renamed"),
        Edit.<Company, String, String>overIfPresent(USER_NAMES, null, String::toUpperCase),
        Edit.mapIfPresent(EMAILS, "!", (bang, email) -> email + bang)
      ).apply(company);
      assertEquals(company.departments().getFirst().name(), out.departments().getFirst().name());
      assertEquals("Renamed", out.departments().getFirst().teams().getFirst().name());
      assertEquals(
        company.departments().getFirst().teams().getFirst().users().getFirst().name(),
        out.departments().getFirst().teams().getFirst().users().getFirst().name()
      );
      assertEquals("ALICE@ACME.COM!", out.departments().getFirst().teams().getFirst().users().getFirst().email());
    }
  }
}
