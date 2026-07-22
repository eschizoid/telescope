package io.github.eschizoid.telescope;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Single-file demo of the whole library. If you can read this you can use Telescope. */
class TelescopeTest {

  record Address(String city, String zip) {}

  record User(String name, int age, Address address) {}

  record Team(String name, List<User> users) {}

  record Company(String name, List<Team> teams) {}

  sealed interface Event permits Created, Updated, Deleted {}

  record Created(String id) implements Event {}

  record Updated(String id, String diff, int revision) implements Event {}

  record Deleted(String id) implements Event {}

  @Nested
  @DisplayName("Field access")
  class FieldAccess {

    @Test
    @DisplayName("read and update a single record field via method reference")
    void singleField() {
      final var name = Telescope.of(User.class).field(User::name);
      final var alice = new User("alice", 30, new Address("nyc", "10001"));

      assertEquals("alice", name.read(alice));

      final var loud = name.update(alice, String::toUpperCase);
      assertEquals("ALICE", loud.name());
      assertEquals(30, loud.age());
      assertEquals(alice.address(), loud.address());
    }

    @Test
    @DisplayName("set a single field to a new value")
    void singleFieldSet() {
      final var name = Telescope.of(User.class).field(User::name);
      final var alice = new User("alice", 30, null);
      assertEquals("Bob", name.set(alice, "Bob").name());
    }

    @Test
    @DisplayName("fieldByName — runtime-only path when method reference isn't convenient")
    void singleFieldByName() {
      final var name = Telescope.of(User.class).fieldByName("name", String.class);
      final var alice = new User("alice", 30, null);
      assertEquals("ALICE", name.update(alice, String::toUpperCase).name());
    }

    @Test
    @DisplayName("nested fields: address.city via two .field() calls")
    void nestedFields() {
      final var city = Telescope.of(User.class).field(User::address).field(Address::city);
      final var alice = new User("alice", 30, new Address("nyc", "10001"));

      assertEquals("nyc", city.read(alice));
      final var moved = city.set(alice, "BOSTON");
      assertEquals("BOSTON", moved.address().city());
      assertEquals("10001", moved.address().zip());
    }
  }

  @Nested
  @DisplayName("Collections")
  class Collections {

    @Test
    @DisplayName("each(getter) over a record's List<User> updates every element")
    void eachOverList() {
      final var userName = Telescope.of(Team.class).each(Team::users).field(User::name);

      final var t = new Team("a", List.of(new User("alice", 30, null), new User("bob", 25, null)));

      final var loud = userName.update(t, String::toUpperCase);
      assertEquals("ALICE", loud.users().get(0).name());
      assertEquals("BOB", loud.users().get(1).name());

      assertEquals(List.of("alice", "bob"), userName.toList(t));
      assertEquals(2L, userName.count(t));
      assertTrue(userName.exists(t));
      assertFalse(userName.exists(new Team("empty", List.of())));
    }

    @Test
    @DisplayName("a null focus is still a focus — exists/count/toList tolerate it, they don't NPE")
    void nullFocusToleratedByReadTerminals() {
      // A List element whose focused field is null yields a one-element [null] traversal. The read
      // terminals must count it as one present focus. exists() previously routed through
      // Stream.findAny(), which NPEs on a null element (Optional.of); the visitor path tolerates
      // it.
      // (toList/count were already null-safe via Stream.toList()/.count() — this pins all three.)
      final var userName = Telescope.of(Team.class).each(Team::users).field(User::name);
      final var t = new Team("a", List.of(new User(null, 30, null)));

      assertTrue(userName.exists(t));
      assertEquals(1L, userName.count(t));

      final var focuses = userName.toList(t);
      assertEquals(1, focuses.size());
      assertNull(focuses.get(0));
    }

    @Test
    @DisplayName("eachValue(getter) over a record's Map<K, V> updates every value")
    void eachOverMapValues() {
      record Index(Map<String, Integer> byKey) {}

      final var src = new LinkedHashMap<String, Integer>();
      src.put("a", 1);
      src.put("b", 2);
      final var index = new Index(src);

      final var values = Telescope.of(Index.class).eachValue(Index::byKey);
      final var doubled = values.update(index, v -> v * 10);
      assertEquals(10, doubled.byKey().get("a"));
      assertEquals(20, doubled.byKey().get("b"));
    }

    @Test
    @DisplayName("whenPresent(getter) skips empty, modifies present")
    void whenPresentBehavior() {
      record Profile(String id, Optional<String> nickname) {}

      final var nick = Telescope.of(Profile.class).whenPresent(Profile::nickname);

      final var withNick = new Profile("p1", Optional.of("alice"));
      final var noNick = new Profile("p2", Optional.empty());

      assertEquals(Optional.of("ALICE"), nick.update(withNick, String::toUpperCase).nickname());
      assertEquals(Optional.empty(), nick.update(noNick, String::toUpperCase).nickname());
    }

    // Array containers (int[], String[], etc.) are no longer supported by the typed each() — the
    // runtime-dispatched no-arg form is gone, and java.lang.reflect.Array reflection has been
    // dropped. Users with array fields wrap them as List/Set. See PLAN.md item #6 for the
    // migration note.
  }

  @Nested
  @DisplayName("Sealed-type cases")
  class SealedCases {

    @Test
    @DisplayName("as() narrows to a sealed-type case; non-matching values pass through unchanged")
    void asSealedCase() {
      final var diff = Telescope.of(Event.class).as(Updated.class).field(Updated::diff);

      final Event hit = new Updated("e1", "x", 0);
      final Event miss = new Created("e2");

      assertEquals(Optional.of("x"), diff.find(hit));
      assertEquals(Optional.empty(), diff.find(miss));

      final Event modified = diff.update(hit, s -> s + "!");
      assertEquals(new Updated("e1", "x!", 0), modified);

      final Event unchanged = diff.update(miss, s -> "should not happen");
      assertInstanceOf(Created.class, unchanged);
      assertEquals(miss, unchanged);
    }

    @Test
    @DisplayName("each() + as() on a list of sealed types — bump revision on every Updated")
    void eachAndAs() {
      record Stream(List<Event> events) {}

      final var input = new Stream(
        List.of(new Created("e1"), new Updated("e2", "diff-A", 0), new Deleted("e3"), new Updated("e4", "diff-B", 7))
      );

      final var rev = Telescope.of(Stream.class).each(Stream::events).as(Updated.class).field(Updated::revision);

      final var result = rev.update(input, r -> r + 1);

      assertEquals(new Created("e1"), result.events().get(0));
      assertEquals(new Updated("e2", "diff-A", 1), result.events().get(1));
      assertEquals(new Deleted("e3"), result.events().get(2));
      assertEquals(new Updated("e4", "diff-B", 8), result.events().get(3));
    }
  }

  @Nested
  @DisplayName("Deep nesting")
  class DeepNesting {

    @Test
    @DisplayName("five-level path: Company > teams > users > address > city")
    void deepNesting() {
      final var cities = Telescope.of(Company.class)
        .each(Company::teams)
        .each(Team::users)
        .field(User::address)
        .field(Address::city);
      final var ages = Telescope.of(Company.class).each(Company::teams).each(Team::users).field(User::age);
      final var zips = Telescope.of(Company.class)
        .each(Company::teams)
        .each(Team::users)
        .field(User::address)
        .field(Address::zip);

      final var company = new Company(
        "ACME",
        List.of(
          new Team(
            "core",
            List.of(
              new User("alice", 30, new Address("nyc", "10001")),
              new User("bob", 25, new Address("sfo", "94110"))
            )
          ),
          new Team("data", List.of(new User("carol", 40, new Address("bos", "02110"))))
        )
      );

      final var loud = cities.update(company, String::toUpperCase);

      assertEquals(List.of("NYC", "SFO", "BOS"), cities.toList(loud));
      assertEquals("ACME", loud.name());
      assertEquals(List.of(30, 25, 40), ages.toList(loud));
      assertEquals(List.of("10001", "94110", "02110"), zips.toList(loud));

      assertEquals(List.of("nyc", "sfo", "bos"), cities.toList(company));
      assertEquals(3L, cities.count(company));
    }
  }

  @Nested
  @DisplayName("Filter")
  class Filter {

    @Test
    @DisplayName("filter restricts a many-focus path to matching elements")
    void filterRestricts() {
      final var adults = Telescope.of(Team.class)
        .each(Team::users)
        .filter(u -> u.age() >= 18)
        .field(User::name);

      final var t = new Team(
        "a",
        List.of(new User("alice", 30, null), new User("kid", 12, null), new User("bob", 25, null))
      );

      final var loud = adults.update(t, String::toUpperCase);
      assertEquals("ALICE", loud.users().get(0).name());
      assertEquals("kid", loud.users().get(1).name());
      assertEquals("BOB", loud.users().get(2).name());

      assertEquals(List.of("alice", "bob"), adults.toList(t));
    }
  }

  @Nested
  @DisplayName("Type-to-type conversion (from/to/using)")
  class TypeConversion {

    record UserEntity(String id, String email, String name) {}

    record UserDto(String id, String email, String name) {}

    record EntityPage(List<UserEntity> items, int total) {}

    @Test
    @DisplayName("basic round-trip: read forward, modify, return reconstructed type")
    void roundTrip() {
      final var conv = Telescope.from(UserEntity.class)
        .to(UserDto.class)
        .using(e -> new UserDto(e.id(), e.email(), e.name()), d -> new UserEntity(d.id(), d.email(), d.name()));

      final var entity = new UserEntity("u1", "Alice@Example.COM", "Alice");

      // forward read
      assertEquals(new UserDto("u1", "Alice@Example.COM", "Alice"), conv.read(entity));

      // round-trip modify: convert to DTO, apply fn, convert back to entity
      final var lowered = conv.update(entity, dto -> new UserDto(dto.id(), dto.email().toLowerCase(), dto.name()));
      assertEquals(new UserEntity("u1", "alice@example.com", "Alice"), lowered);
    }

    @Test
    @DisplayName("composes into a longer path: page → each item → as DTO → field")
    void composesIntoLongerPath() {
      final var userIso = Telescope.from(UserEntity.class)
        .to(UserDto.class)
        .using(e -> new UserDto(e.id(), e.email(), e.name()), d -> new UserEntity(d.id(), d.email(), d.name()));

      // walk into items, view each as DTO, focus the email — all in one chain
      final var emailsViaDto = Telescope.of(EntityPage.class)
        .each(EntityPage::items)
        .then(userIso)
        .field(UserDto::email);

      final var page = new EntityPage(
        List.of(new UserEntity("u1", "Alice@Example.COM", "Alice"), new UserEntity("u2", "BOB@example.com", "Bob")),
        2
      );

      // reads as List<String> — emails extracted via the iso
      assertEquals(List.of("Alice@Example.COM", "BOB@example.com"), emailsViaDto.toList(page));

      // updates: round-trip through DTO, modify, come back to entities
      final var normalized = emailsViaDto.update(page, String::toLowerCase);
      assertEquals("alice@example.com", normalized.items().get(0).email());
      assertEquals("bob@example.com", normalized.items().get(1).email());
      // structure preserved
      assertEquals(2, normalized.total());
      assertEquals("Alice", normalized.items().get(0).name());
    }

    @Test
    @DisplayName("Telescope.bridge(BridgeFn) — concrete-type dispatch path mirrors using(...) semantics")
    void bridgeFnFactory() {
      final var bridge = Telescope.bridge(
        new io.github.eschizoid.telescope.conversion.BridgeFn<UserEntity, UserDto>() {
          @Override
          public UserDto forward(final UserEntity e) {
            return new UserDto(e.id(), e.email(), e.name());
          }

          @Override
          public UserEntity backward(final UserDto d) {
            return new UserEntity(d.id(), d.email(), d.name());
          }
        }
      );

      final var entity = new UserEntity("u1", "Alice@Example.COM", "Alice");

      // forward read
      assertEquals(new UserDto("u1", "Alice@Example.COM", "Alice"), bridge.read(entity));

      // round-trip modify: convert via Fn, mutate, rebuild entity
      final var lowered = bridge.update(entity, dto -> new UserDto(dto.id(), dto.email().toLowerCase(), dto.name()));
      assertEquals(new UserEntity("u1", "alice@example.com", "Alice"), lowered);

      // composes into a longer path same as from/to/using does
      final var page = new EntityPage(
        List.of(new UserEntity("u1", "Alice@Example.COM", "Alice"), new UserEntity("u2", "BOB@example.com", "Bob")),
        2
      );
      final var emailsViaDto = Telescope.of(EntityPage.class)
        .each(EntityPage::items)
        .then(bridge)
        .field(UserDto::email);
      final var normalized = emailsViaDto.update(page, String::toLowerCase);
      assertEquals("alice@example.com", normalized.items().get(0).email());
      assertEquals("bob@example.com", normalized.items().get(1).email());
    }

    @Test
    @DisplayName("reverse direction available by swapping arguments to using(...)")
    void reverseViaSwappedArgs() {
      final Function<UserEntity, UserDto> toDto = e -> new UserDto(e.id(), e.email(), e.name());
      final Function<UserDto, UserEntity> toEntity = d -> new UserEntity(d.id(), d.email(), d.name());

      final var entityToDto = Telescope.from(UserEntity.class).to(UserDto.class).using(toDto, toEntity);
      final var dtoToEntity = Telescope.from(UserDto.class).to(UserEntity.class).using(toEntity, toDto);

      final var entity = new UserEntity("u1", "alice@example.com", "Alice");
      final var dto = entityToDto.read(entity);

      // round-trip through both directions
      assertEquals(entity, dtoToEntity.read(dto));
    }
  }

  @Nested
  @DisplayName("Per-accessor FieldOptics dispatch — survives paradigm hops and sealed narrowing")
  class ParadigmHopDispatch {

    // Record-side root with a bean-shaped sub-leaf reached via a Mapper-as-Telescope hop.
    record BoxRoot(BoxRec inner) {}

    record BoxRec(String email) {}

    // Plain POJO target — getter/setter shape that BeanFieldOptics can navigate.
    static final class BoxBean {

      private String email;

      public BoxBean() {}

      public String getEmail() {
        return email;
      }

      public void setEmail(final String email) {
        this.email = email;
      }
    }

    @Test
    @DisplayName(
      ".field() after .then(mapper.asTelescope()) routes to BeanFieldOptics, not the entry-point's RecordFieldOptics"
    )
    void fieldAfterParadigmHop() {
      // Before the per-accessor dispatch fix this threw "Not a record: BoxBean" because
      // Telescope.of(record) locked in RecordFieldOptics and the trailing .field(BoxBean::getEmail)
      // still routed through Records.fieldLens(name).
      final var recToBean = Telescope.mapper(BoxRec.class, BoxBean.class).asTelescope();
      final var chain = Telescope.of(BoxRoot.class).field(BoxRoot::inner).then(recToBean).field(BoxBean::getEmail);
      final var root = new BoxRoot(new BoxRec("Alice@x"));
      assertEquals("Alice@x", chain.read(root));
      final var updated = chain.update(root, String::toLowerCase);
      assertEquals("alice@x", updated.inner().email());
    }
  }

  @Nested
  @DisplayName("Errors")
  class Errors {

    @Test
    @DisplayName("missing field name surfaces a clear error including the known component names")
    void unknownFieldErrors() {
      final var bad = Telescope.of(User.class).fieldByName("doesNotExist");
      final var alice = new User("alice", 30, null);
      final var ex = assertThrows(IllegalArgumentException.class, () -> bad.read(alice));
      assertTrue(ex.getMessage().contains("doesNotExist"));
      // The error must list available alternatives so a config-driven fieldByName(...) typo
      // surfaces a usable hint without forcing the user to read the record source.
      assertTrue(ex.getMessage().contains("known fields"), ex.getMessage());
      assertTrue(ex.getMessage().contains("name"), ex.getMessage());
      assertTrue(ex.getMessage().contains("age"), ex.getMessage());
    }

    @Test
    @DisplayName("calling read on a missing partial path throws NoSuchElementException")
    void readOnMissingThrows() {
      final var diff = Telescope.of(Event.class).as(Updated.class).field(Updated::diff);
      final Event miss = new Created("e1");
      assertThrows(NoSuchElementException.class, () -> diff.read(miss));
    }

    @Test
    @DisplayName("regular lambdas are rejected — method references required")
    void rejectsRegularLambdas() {
      // Must stay a lambda so the resolver hits the lambda$ branch. Do NOT let an IDE convert this
      // to a method reference, or the test will silently stop exercising the rejection path.
      //noinspection Convert2MethodRef
      final Telescope.Accessor<User, String> notARef = u -> u.name();
      final var ex = assertThrows(IllegalArgumentException.class, () -> Telescope.of(User.class).field(notARef));
      assertTrue(
        ex.getMessage().toLowerCase().contains("method reference") || ex.getMessage().toLowerCase().contains("lambda")
      );
    }
  }
}
