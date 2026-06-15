package io.github.eschizoid.telescope.internal.optics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Direct tests of the internal optic lattice — Lens laws, Iso round-trips, Prism behavior, and
 * lattice composition. These ensure the proven optics underneath stay correct as the DSL evolves.
 */
class OpticLawsTest {

  record Address(String city, String zip) {}

  record User(String name, int age, Address address) {}

  record UserDto(String fullName, int age) {}

  sealed interface Event permits Created, Updated, Deleted {}

  record Created(String id) implements Event {}

  record Updated(String id, String diff) implements Event {}

  record Deleted(String id) implements Event {}

  static final User ALICE = new User("Alice", 30, new Address("NYC", "10001"));

  static final Lens<User, String> userName = Focus.lens(User::name, (u, n) -> new User(n, u.age(), u.address()));

  static final Iso<User, UserDto> userIso = Focus.iso(
    u -> new UserDto(u.name(), u.age()),
    d -> new User(d.fullName(), d.age(), null)
  );

  static final Prism<Event, Updated> updatedPrism = Focus.prism(Updated.class);

  @Nested
  @DisplayName("Lens laws")
  class LensLaws {

    @Test
    @DisplayName("get-set: setting back what we read leaves S unchanged")
    void getSet() {
      assertEquals(ALICE, userName.set(ALICE, userName.get(ALICE)));
    }

    @Test
    @DisplayName("set-get: reading after set returns what we set")
    void setGet() {
      assertEquals("Bob", userName.get(userName.set(ALICE, "Bob")));
    }

    @Test
    @DisplayName("set-set: the last set wins")
    void setSet() {
      final var once = userName.set(ALICE, "Bob");
      final var twice = userName.set(once, "Carol");
      assertEquals(userName.set(ALICE, "Carol"), twice);
    }
  }

  @Nested
  @DisplayName("Iso laws")
  class IsoLaws {

    @Test
    @DisplayName("forward round-trip: from(to(a)) equals a (for the fields involved)")
    void forwardRoundTrip() {
      final var noAddress = new User("Alice", 30, null);
      assertEquals(noAddress, userIso.from(userIso.to(noAddress)));
    }

    @Test
    @DisplayName("backward round-trip: to(from(b)) equals b")
    void backwardRoundTrip() {
      final var dto = new UserDto("Alice", 30);
      assertEquals(dto, userIso.to(userIso.from(dto)));
    }

    @Test
    @DisplayName("reverse() swaps to/from")
    void reverseSwapsDirection() {
      final var reversed = userIso.reverse();
      final var dto = new UserDto("Alice", 30);
      assertEquals(userIso.from(dto), reversed.to(dto));
    }

    @Test
    @DisplayName("identity() returns a cached singleton — reference-equal across call sites")
    void identityIsSingleton() {
      final Iso<String, String> a = Iso.identity();
      final Iso<Integer, Integer> b = Iso.identity();
      // Same instance after the unchecked cast — the singleton is type-erased.
      assertTrue(a == (Object) b);
    }

    @Test
    @DisplayName("identity() preserves values: to(x) == x and from(x) == x")
    void identityPassesThrough() {
      final Iso<String, String> id = Iso.identity();
      assertEquals("anything", id.to("anything"));
      assertEquals("anything", id.from("anything"));
      // Reflexive on reverse: id.reverse() also passes through.
      assertEquals("z", id.reverse().to("z"));
    }
  }

  @Nested
  @DisplayName("Prism behavior")
  class PrismBehavior {

    @Test
    @DisplayName("getOption returns the case when matching, empty otherwise")
    void getOption() {
      final Event hit = new Updated("e1", "x");
      final Event miss = new Created("e2");
      assertEquals(Optional.of(new Updated("e1", "x")), updatedPrism.getOption(hit));
      assertEquals(Optional.empty(), updatedPrism.getOption(miss));
    }

    @Test
    @DisplayName("reverseGet round-trip: getOption(reverseGet(a)) equals Optional.of(a)")
    void roundTrip() {
      final var u = new Updated("e1", "diff");
      assertEquals(Optional.of(u), updatedPrism.getOption(updatedPrism.reverseGet(u)));
    }

    @Test
    @DisplayName("modify on a non-matching case is a no-op")
    void modifyNoOpOnMiss() {
      final Event e = new Created("e1");
      assertEquals(e, updatedPrism.modify(e, u -> new Updated(u.id(), u.diff() + "!")));
    }
  }

  @Nested
  @DisplayName("Lattice composition")
  class LatticeComposition {

    @Test
    @DisplayName("Lens.then(Lens) returns a Lens")
    void lensThenLens() {
      final Lens<User, Address> userAddress = Focus.lens(User::address, (u, a) -> new User(u.name(), u.age(), a));
      final Lens<Address, String> addressCity = Focus.lens(Address::city, (a, c) -> new Address(c, a.zip()));
      final var composed = userAddress.then(addressCity);
      assertInstanceOf(Lens.class, composed);
      assertEquals("NYC", composed.get(ALICE));
      assertEquals("BOSTON", composed.set(ALICE, "BOSTON").address().city());
    }

    @Test
    @DisplayName("Lens.then(Prism) returns an Affine")
    void lensThenPrismIsAffine() {
      record Envelope(Event payload) {}
      final Lens<Envelope, Event> payloadLens = Focus.lens(Envelope::payload, (e, p) -> new Envelope(p));
      final var composed = payloadLens.then(updatedPrism);
      assertInstanceOf(Affine.class, composed);
      assertTrue(composed.getOption(new Envelope(new Updated("id", "x"))).isPresent());
      assertEquals(Optional.empty(), composed.getOption(new Envelope(new Created("id"))));
    }

    @Test
    @DisplayName("Iso.then(Iso) returns an Iso")
    void isoThenIso() {
      final var toStr = Focus.iso(Object::toString, Integer::parseInt);
      final var upper = Focus.<String, String>iso(String::toUpperCase, String::toLowerCase);
      final var composed = toStr.then(upper);
      assertInstanceOf(Iso.class, composed);
      assertEquals("42", composed.to(42));
      assertEquals(42, composed.from("42"));
    }

    @Test
    @DisplayName("Iso.then(Lens) returns a Lens (diamond resolution)")
    void isoThenLensIsLens() {
      final Iso<User, UserDto> uIso = Focus.iso(
        u -> new UserDto(u.name(), u.age()),
        d -> new User(d.fullName(), d.age(), null)
      );
      final Lens<UserDto, String> dtoName = Focus.lens(UserDto::fullName, (d, n) -> new UserDto(n, d.age()));
      final var composed = uIso.then(dtoName);
      assertInstanceOf(Lens.class, composed);
      assertEquals("Alice", composed.get(ALICE));
    }
  }
}
