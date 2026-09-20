package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.Edit.over;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.effects.Either;
import io.github.eschizoid.telescope.effects.Validated;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

class TelescopeObserveTest {

  record User(String email) {}

  record Team(List<User> users) {}

  record Department(List<Team> teams) {}

  record Company(List<Department> departments) {}

  private static final Company COMPANY = new Company(
    List.of(new Department(List.of(new Team(List.of(new User("A@X"), new User("B@X"))))))
  );

  private static Telescope<Company, String> emails(final List<String> seen) {
    return Telescope.of(Company.class)
      .each(Company::departments)
      .observe(d -> seen.add("department:" + d.teams().get(0).users().get(0).email()))
      .each(Department::teams)
      .observe(t -> seen.add("team:" + t.users().get(0).email()))
      .each(Team::users)
      .observe(u -> seen.add("user:" + u.email()))
      .field(User::email);
  }

  @Test
  void readsObserveReachedValuesAndShortCircuit() {
    final var seen = new ArrayList<String>();
    final var path = emails(seen);
    assertTrue(seen.isEmpty());
    assertEquals("A@X", path.read(COMPANY));
    assertEquals(List.of("department:A@X", "team:A@X", "user:A@X"), seen);

    seen.clear();
    assertEquals(List.of("A@X", "B@X"), path.toList(COMPANY));
    assertEquals(List.of("department:A@X", "team:A@X", "user:A@X", "user:B@X"), seen);

    seen.clear();
    assertEquals(2, path.count(COMPANY));
    assertEquals(4, seen.size());
    seen.clear();
    assertTrue(path.exists(COMPANY));
    assertEquals(3, seen.size());
    seen.clear();
    path.trace(COMPANY);
    path.explain();
    assertTrue(seen.isEmpty());
  }

  @Test
  void writesObserveRebuiltValuesFromInsideOut() {
    final var seen = new ArrayList<String>();
    final var path = emails(seen);
    final var lowered = path.update(COMPANY, String::toLowerCase);
    assertEquals("a@x", lowered.departments().get(0).teams().get(0).users().get(0).email());
    assertEquals(List.of("user:a@x", "user:b@x", "team:a@x", "department:a@x"), seen);
    assertEquals("A@X", COMPANY.departments().get(0).teams().get(0).users().get(0).email());

    seen.clear();
    path.set(COMPANY, "fixed");
    assertEquals(List.of("user:fixed", "user:fixed", "team:fixed", "department:fixed"), seen);

    seen.clear();
    path.updateIndexed(COMPANY, (i, email) -> i + email);
    assertEquals(List.of("user:0A@X", "user:1B@X", "team:0A@X", "department:0A@X"), seen);
  }

  @Test
  void failedEffectfulUpdatesDoNotObserve() {
    final var seen = new ArrayList<String>();
    final var path = emails(seen);
    assertTrue(path.updateEither(COMPANY, email -> Either.left("bad")) instanceof Either.Left);
    assertTrue(seen.isEmpty());
    assertEquals(Optional.empty(), path.updateOptional(COMPANY, email -> Optional.empty()));
    assertTrue(seen.isEmpty());
    assertTrue(path.updateValidated(COMPANY, email -> Validated.invalid("bad")) instanceof Validated.Invalid);
    assertTrue(seen.isEmpty());
    assertThrows(CompletionException.class, () ->
      path
        .updateAsync(COMPANY, email -> CompletableFuture.<String>failedFuture(new IllegalStateException("bad")))
        .join()
    );
    assertTrue(seen.isEmpty());
  }

  @Test
  void successfulEffectfulUpdatesObserveOnceAndInOrder() {
    final var seen = new ArrayList<String>();
    final var path = emails(seen);
    path.updateEither(COMPANY, email -> Either.right(email.toLowerCase()));
    final var expected = List.of("user:a@x", "user:b@x", "team:a@x", "department:a@x");
    assertEquals(expected, seen);

    seen.clear();
    path.updateOptional(COMPANY, email -> Optional.of(email.toLowerCase()));
    assertEquals(expected, seen);
    seen.clear();
    path.updateValidated(COMPANY, email -> Validated.valid(email.toLowerCase()));
    assertEquals(expected, seen);
    seen.clear();
    path.updateAsync(COMPANY, email -> CompletableFuture.completedFuture(email.toLowerCase())).join();
    assertEquals(expected, seen);
  }

  @Test
  void emptyPathsAndMultiEditKeepCallbackSemantics() {
    final var seen = new ArrayList<String>();
    final var path = emails(seen);
    final var empty = new Company(List.of());
    assertEquals(List.of(), path.toList(empty));
    assertFalse(path.exists(empty));
    assertEquals(empty, path.update(empty, String::toLowerCase));
    assertTrue(seen.isEmpty());

    final var edit = Telescope.all(over(path, String::toLowerCase), over(path, String::toUpperCase));
    edit.apply(COMPANY);
    assertEquals(8, seen.size());
    assertEquals("department:a@x", seen.get(3));
    assertEquals("department:A@X", seen.get(7));
  }

  @Test
  void nullFocusAndComposedTailAreObserved() {
    final var seen = new ArrayList<User>();
    final var users = Telescope.of(Team.class).each(Team::users).observe(seen::add);
    final var team = new Team(Arrays.asList((User) null));
    assertEquals(Arrays.asList((User) null), users.toList(team));
    assertEquals(Arrays.asList((User) null), seen);
    seen.clear();
    users.update(team, user -> user);
    assertEquals(Arrays.asList((User) null), seen);

    seen.clear();
    final var composed = Telescope.of(Team.class)
      .each(Team::users)
      .observe(seen::add)
      .then(Telescope.of(User.class).field(User::email));
    assertEquals(List.of("A@X", "B@X"), composed.toList(COMPANY.departments().get(0).teams().get(0)));
    assertEquals(2, seen.size());
  }

  @Test
  void filterKeepsObserverAtItsPositionDuringEffectfulUpdates() {
    final var seen = new ArrayList<String>();
    final var team = COMPANY.departments().get(0).teams().get(0);
    final var path = Telescope.of(Team.class)
      .each(Team::users)
      .observe(user -> seen.add(user.email()))
      .filter(user -> user.email().startsWith("A"))
      .field(User::email);

    final var updated = path.updateOptional(team, email -> Optional.of(email.toLowerCase())).orElseThrow();
    assertEquals(List.of("a@x", "B@X"), updated.users().stream().map(User::email).toList());
    assertEquals(List.of("a@x", "B@X"), seen);
  }
}
