package io.github.eschizoid.telescope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for the multi-edit chain: {@link Telescope#with(java.util.function.Function)} accumulates
 * an edit at the current focus and returns a fresh identity {@code Telescope<S, S>}; {@link
 * Telescope#apply(Object)} runs every accumulated edit in insertion order against a source.
 *
 * <p>Each step is compile-checked through the existing typed navigation API ({@code .each}, {@code
 * .field}, etc.) — no new public types, no varargs path-builder, no runtime type dispatch.
 */
class WithChainTest {

  record User(String name, String email) {}

  record Team(String name, List<User> users) {}

  record Department(String name, List<Team> teams) {}

  record Company(String name, List<Department> departments) {}

  // Pre-built paths reused across the whole test suite. Each typed Telescope<Company, String>;
  // every step compile-checked.
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
  @DisplayName("Single edit — equivalent to one update(source, fn)")
  class SingleEdit {

    @Test
    @DisplayName("one update(path, fn).apply(source) equals one path.update(source, fn)")
    void mirrorsDirectUpdate() {
      final var company = sample();
      final var viaChain = Telescope.of(Company.class).update(EMAILS, String::toLowerCase).apply(company);
      final var viaDirect = EMAILS.update(company, String::toLowerCase);
      assertEquals(viaDirect, viaChain);
    }
  }

  @Nested
  @DisplayName("Multi-edit — chain accumulates edits in order")
  class MultiEdit {

    @Test
    @DisplayName("two heterogeneous edits both apply, in insertion order")
    void twoEditsHeterogeneous() {
      final var company = sample();
      final var out = Telescope.of(Company.class)
        .update(EMAILS, String::toLowerCase)
        .update(DEPT_NAMES, String::trim)
        .apply(company);
      assertEquals("Engineering", out.departments().get(0).name());
      assertEquals("Sales", out.departments().get(1).name());
      assertEquals("alice@acme.com", out.departments().get(0).teams().get(0).users().get(0).email());
    }

    @Test
    @DisplayName("four-edit chain applies all edits in one declarative pass")
    void fourEdits() {
      final var company = sample();
      final var out = Telescope.of(Company.class)
        .update(EMAILS, String::toLowerCase)
        .update(DEPT_NAMES, String::trim)
        .update(TEAM_NAMES, String::trim)
        .update(USER_NAMES, String::toUpperCase)
        .apply(company);
      assertEquals("Engineering", out.departments().get(0).name());
      assertEquals("Platform", out.departments().get(0).teams().get(0).name());
      assertEquals("ALICE", out.departments().get(0).teams().get(0).users().get(0).name());
      assertEquals("alice@acme.com", out.departments().get(0).teams().get(0).users().get(0).email());
    }

    @Test
    @DisplayName("later edits see earlier edits' results, not the original source")
    void laterSeesEarlier() {
      final var company = sample();
      final var out = Telescope.of(Company.class)
        .update(EMAILS, String::toLowerCase)
        .update(EMAILS, e -> e + "!")
        .apply(company);
      assertEquals("alice@acme.com!", out.departments().get(0).teams().get(0).users().get(0).email());
    }
  }

  @Nested
  @DisplayName("Empty chain — apply returns the source unchanged")
  class EmptyChain {

    @Test
    @DisplayName("apply on a fresh Telescope.of(...) returns the same source instance")
    void freshTelescopeIsIdentity() {
      final var company = sample();
      assertSame(company, Telescope.of(Company.class).apply(company));
    }

    @Test
    @DisplayName("apply on a navigated-but-not-edited telescope still returns source unchanged")
    void navigatedWithoutWithIsIdentity() {
      final var company = sample();
      assertSame(company, Telescope.of(Company.class).each(Company::departments).apply(company));
    }
  }

  @Nested
  @DisplayName("Mix-and-match — pre-built update(path, fn) and inline with(fn) in the same chain")
  class MixedForms {

    @Test
    @DisplayName("pre-built update(path, fn) interleaved with inline with(fn) works")
    void mixPrebuiltAndInline() {
      final var company = sample();
      final var out = Telescope.of(Company.class)
        .update(EMAILS, String::toLowerCase)
        .each(Company::departments)
        .field(Department::name)
        .with(String::trim)
        .apply(company);
      assertEquals("Engineering", out.departments().get(0).name());
      assertEquals("alice@acme.com", out.departments().get(0).teams().get(0).users().get(0).email());
    }

    @Test
    @DisplayName("Java picks the right overload by first-arg type — Telescope vs source instance")
    void overloadResolution() {
      final var company = sample();
      final Company viaTerminal = EMAILS.update(company, String::toLowerCase);
      final Company viaChain = Telescope.of(Company.class).update(EMAILS, String::toLowerCase).apply(company);
      assertEquals(viaTerminal, viaChain);
    }
  }

  @Nested
  @DisplayName("Reuse — a stored multi-edit telescope applies cleanly to many sources")
  class Reuse {

    @Test
    @DisplayName("the same multi-edit chain applies independently to many sources")
    void appliedToManySources() {
      final Telescope<Company, Company> normalize = Telescope.of(Company.class)
        .update(EMAILS, String::toLowerCase)
        .update(DEPT_NAMES, String::trim);

      final var company1 = sample();
      final var company2 = new Company(
        "Bingo",
        List.of(new Department(" Ops ", List.of(new Team(" SRE ", List.of(new User("zane", "Z@BINGO.COM"))))))
      );

      final var out1 = normalize.apply(company1);
      final var out2 = normalize.apply(company2);

      assertEquals("Engineering", out1.departments().get(0).name());
      assertEquals("Ops", out2.departments().get(0).name());
      assertEquals("z@bingo.com", out2.departments().get(0).teams().get(0).users().get(0).email());
    }
  }
}
