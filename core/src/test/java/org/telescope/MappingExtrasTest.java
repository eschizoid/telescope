package org.telescope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Tests for the v0.6 mapping additions: .auto(), transforms, .via() nesting, and patch(). */
class MappingExtrasTest {

  @Nested
  @DisplayName(".auto() — map same-name fields")
  class Auto {

    record OrderEntity(String id, long amount, String currency) {}

    record OrderDto(String id, long amount, String currency) {}

    @Test
    @DisplayName("auto() maps every same-name field with no explicit declarations")
    void allAuto() {
      final var mapper = Telescope.map(OrderEntity.class).to(OrderDto.class).auto().build();
      assertEquals(new OrderDto("o1", 500L, "USD"), mapper.read(new OrderEntity("o1", 500L, "USD")));
    }

    record A(String id, String name) {}

    record B(String id, String fullName) {}

    @Test
    @DisplayName("explicit .field().to() overrides/fills what auto() can't (renames)")
    void autoPlusRename() {
      // id auto-maps; name→fullName declared explicitly
      final var mapper = Telescope.map(A.class).to(B.class).auto().field(A::name).to(B::fullName).build();
      assertEquals(new B("a1", "Alice"), mapper.read(new A("a1", "Alice")));
    }
  }

  @Nested
  @DisplayName("transforms — type-converting field maps")
  class Transforms {

    record Event(String id, Instant at) {}

    record EventDto(String id, String at) {}

    static final Telescope<Event, EventDto> MAPPER = Telescope.map(Event.class)
      .to(EventDto.class)
      .field(Event::id)
      .to(EventDto::id)
      .field(Event::at)
      .to(EventDto::at, Instant::toString, Instant::parse)
      .build();

    @Test
    @DisplayName("forward applies the transform")
    void forward() {
      final var instant = Instant.parse("2026-01-01T00:00:00Z");
      assertEquals(new EventDto("e1", "2026-01-01T00:00:00Z"), MAPPER.read(new Event("e1", instant)));
    }

    @Test
    @DisplayName("round-trip through the transform is identity")
    void roundTrip() {
      final var event = new Event("e1", Instant.parse("2026-01-01T00:00:00Z"));
      assertEquals(event, MAPPER.set(event, MAPPER.read(event)));
    }
  }

  @Nested
  @DisplayName(".via() — nested mappers")
  class Via {

    record AddrEntity(String city, String zip) {}

    record AddrDto(String city, String zip) {}

    record UserEntity(String name, AddrEntity address) {}

    record UserDto(String name, AddrDto address) {}

    @Test
    @DisplayName("a nested mapper maps a sub-record both ways")
    void nested() {
      final var addressMapper = Telescope.map(AddrEntity.class).to(AddrDto.class).auto().buildMapper();

      final var userMapper = Telescope.map(UserEntity.class)
        .to(UserDto.class)
        .field(UserEntity::name)
        .to(UserDto::name)
        .field(UserEntity::address)
        .via(UserDto::address, addressMapper)
        .build();

      final var entity = new UserEntity("alice", new AddrEntity("nyc", "10001"));
      final var dto = userMapper.read(entity);
      assertEquals(new UserDto("alice", new AddrDto("nyc", "10001")), dto);
      // round-trip
      assertEquals(entity, userMapper.set(entity, dto));
    }
  }

  @Nested
  @DisplayName("patch() — sparse update")
  class Patch {

    record User(String id, String email, String name) {}

    record UserPatch(String id, String email, String name) {}

    static final Telescope.Mapper<User, UserPatch> MAPPER = Telescope.map(User.class)
      .to(UserPatch.class)
      .auto()
      .buildMapper();

    @Test
    @DisplayName("only non-null patch fields are applied")
    void sparse() {
      final var user = new User("u1", "old@x.com", "Alice");
      final var patch = new UserPatch(null, "new@x.com", null); // only email present
      final var updated = MAPPER.patch(user, patch);
      assertEquals(new User("u1", "new@x.com", "Alice"), updated);
    }

    @Test
    @DisplayName("an all-null patch leaves the base unchanged")
    void emptyPatch() {
      final var user = new User("u1", "old@x.com", "Alice");
      assertEquals(user, MAPPER.patch(user, new UserPatch(null, null, null)));
    }

    @Test
    @DisplayName("a full patch overwrites everything")
    void fullPatch() {
      final var user = new User("u1", "old@x.com", "Alice");
      final var updated = MAPPER.patch(user, new UserPatch("u2", "new@x.com", "Bob"));
      assertEquals(new User("u2", "new@x.com", "Bob"), updated);
    }
  }

  @Nested
  @DisplayName("Mapper composition")
  class MapperComposition {

    record E(String id, String email) {}

    record D(String id, String email) {}

    record Page(java.util.List<E> items) {}

    @Test
    @DisplayName("Mapper.asTelescope() threads through a longer path")
    void asTelescope() {
      final var mapper = Telescope.map(E.class).to(D.class).auto().buildMapper();

      final var page = new Page(java.util.List.of(new E("u1", "A@X"), new E("u2", "B@Y")));
      final var lowered = Telescope.of(Page.class)
        .each(Page::items)
        .then(mapper.asTelescope())
        .field(D::email)
        .update(page, String::toLowerCase);

      assertInstanceOf(Page.class, lowered);
      assertEquals("a@x", lowered.items().get(0).email());
      assertEquals("b@y", lowered.items().get(1).email());
    }
  }
}
