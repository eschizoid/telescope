package io.github.eschizoid.telescope.examples;

import io.github.eschizoid.telescope.Telescope;
import java.util.List;
import java.util.Optional;

/**
 * Exercises {@code .as(Class)} narrowing on a sealed hierarchy (Prism semantics — non-matching
 * subjects pass through unchanged on update, and find() returns Optional.empty) and {@code
 * .filter(Predicate)} restriction on a many-focus path.
 */
final class SealedAndFilterDemo {

  private SealedAndFilterDemo() {}

  static void main() {
    run();
  }

  sealed interface Event permits Created, Updated, Deleted {}

  record Created(String id) implements Event {}

  record Updated(String id, String diff, int revision) implements Event {}

  record Deleted(String id) implements Event {}

  record User(String name, int age, String email) {}

  record Team(String name, List<User> users) {}

  static void run() {
    asNarrowsSealedCase();
    asMissPassesThroughOnUpdate();
    filterRestrictsManyFocus();
    eachAsAcrossListOfSealed();
  }

  // .as(Class) narrows to a sealed-type case. find() returns Optional.of(value) on a hit.
  private static void asNarrowsSealedCase() {
    final var diffPath = Telescope.of(Event.class).as(Updated.class).field(Updated::diff);
    final Event hit = new Updated("e1", "+++", 0);
    System.out.println("[as] hit  find: " + diffPath.find(hit));

    final Event miss = new Created("e2");
    final Optional<String> missed = diffPath.find(miss);
    System.out.println("[as] miss find: " + missed);
  }

  // .as(Class) on update — when the cast doesn't match, the update is a no-op (Prism semantics).
  // This is the "what happens when the cast doesn't match" scenario from the task brief.
  private static void asMissPassesThroughOnUpdate() {
    final var diffPath = Telescope.of(Event.class).as(Updated.class).field(Updated::diff);
    final Event miss = new Created("e2");
    final Event result = diffPath.update(miss, s -> "should not happen");
    System.out.println("[as] update on a miss : " + result + " (unchanged — Prism no-op)");
  }

  // .filter(Predicate) restricts a many-focus path: matching elements are updated, others pass
  // through unchanged on update; toList only emits the matching ones.
  private static void filterRestrictsManyFocus() {
    final var adults = Telescope.of(Team.class)
      .each(Team::users)
      .filter(u -> u.age() >= 18)
      .field(User::name);

    final var t = new Team(
      "a",
      List.of(new User("alice", 30, "a@x"), new User("kid", 12, "k@x"), new User("bob", 25, "b@x"))
    );

    final var loud = adults.update(t, String::toUpperCase);
    System.out.println("[filter] adult names    : " + adults.toList(t));
    System.out.println("[filter] update result  : " + loud);
  }

  // each() + as(Subtype) over a List<Event>: only Updated events get their revision bumped, the
  // Created and Deleted entries pass through untouched.
  private static void eachAsAcrossListOfSealed() {
    record Stream(List<Event> events) {}

    final var input = new Stream(
      List.of(new Created("e1"), new Updated("e2", "diff-A", 0), new Deleted("e3"), new Updated("e4", "diff-B", 7))
    );

    final var rev = Telescope.of(Stream.class).each(Stream::events).as(Updated.class).field(Updated::revision);
    final var result = rev.update(input, r -> r + 1);
    System.out.println("[each/as] bumped Updated revisions: " + result);
  }
}
