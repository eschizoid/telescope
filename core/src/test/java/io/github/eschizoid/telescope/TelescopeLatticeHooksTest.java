package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.Edit.over;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link Telescope#after(java.util.function.Function)} and {@link
 * Telescope#before(java.util.function.Function)} — lattice-native hooks composed onto a path via
 * the internal {@link io.github.eschizoid.telescope.internal.optics.Iso} substrate. These hooks
 * thread through {@link Telescope#then}, {@link Edit#over Edit.over}, and the codegen-generated
 * navigators — MapStruct's annotation-bound hooks cannot reach this because they bind to mapper
 * methods, not paths.
 */
class TelescopeLatticeHooksTest {

  record User(String email) {}

  record Team(String name, List<User> users) {}

  @Nested
  @DisplayName("after — runs on the read side")
  class AfterRead {

    @Test
    @DisplayName("read passes through the hook")
    void readApplied() {
      final var path = Telescope.of(User.class).field(User::email).after(String::trim);
      assertEquals("alice@x.com", path.read(new User("  alice@x.com  ")));
    }

    @Test
    @DisplayName("write side passes through unchanged — hook is one-sided")
    void writeUnchanged() {
      final var path = Telescope.of(User.class).field(User::email).after(String::trim);
      assertEquals("  ALICE@X.COM  ", path.set(new User(""), "  ALICE@X.COM  ").email());
    }

    @Test
    @DisplayName("hook composes through .then(...) — traversal threads it per element")
    void composesThroughThen() {
      final var trimmedEmails = Telescope.of(Team.class).each(Team::users).field(User::email).after(String::trim);

      final var team = new Team("eng", List.of(new User("  a@x  "), new User("b@x"), new User("  c@x  ")));
      assertEquals(List.of("a@x", "b@x", "c@x"), trimmedEmails.toList(team));
    }
  }

  @Nested
  @DisplayName("before — runs on the write side")
  class BeforeWrite {

    @Test
    @DisplayName("set passes through the hook")
    void setApplied() {
      final var path = Telescope.of(User.class).field(User::email).before(String::toLowerCase);
      assertEquals("alice@x.com", path.set(new User(""), "ALICE@X.COM").email());
    }

    @Test
    @DisplayName("update's incoming function output is normalised by the hook")
    void updateApplied() {
      final var path = Telescope.of(User.class).field(User::email).before(String::toLowerCase);
      assertEquals("alice@x.com", path.update(new User("a@x.com"), e -> "ALICE@X.COM").email());
    }

    @Test
    @DisplayName("read side passes through unchanged — hook is one-sided")
    void readUnchanged() {
      final var path = Telescope.of(User.class).field(User::email).before(String::toLowerCase);
      assertEquals("UPPERCASE@X.COM", path.read(new User("UPPERCASE@X.COM")));
    }

    @Test
    @DisplayName("hook composes with Edit.over(...) — the multi-edit shape sees it")
    void composesWithEditOver() {
      final var emailsNormalised = Telescope.of(Team.class)
        .each(Team::users)
        .field(User::email)
        .before(String::toLowerCase);

      final var normalize = Telescope.all(over(emailsNormalised, e -> "MIXED@X.COM"));
      final var team = new Team("eng", List.of(new User("a@x"), new User("b@x")));

      assertEquals(
        List.of("mixed@x.com", "mixed@x.com"),
        normalize.apply(team).users().stream().map(User::email).toList()
      );
    }
  }

  @Nested
  @DisplayName("before + after together — symmetric pair")
  class BeforeAfterCombo {

    @Test
    @DisplayName("read uses after-hook only; write uses before-hook only")
    void independentSides() {
      final var path = Telescope.of(User.class).field(User::email).before(String::toLowerCase).after(String::trim);

      assertEquals("alice@x.com", path.set(new User(""), "ALICE@X.COM").email());
      assertEquals("trimmed", path.read(new User("  trimmed  ")));
    }
  }
}
