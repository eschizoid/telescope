package io.github.eschizoid.telescope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Tests for the v0.5 {@code Telescope.map(A).to(B)...build()} field-mapping builder. */
class MappingBuilderTest {

  record UserEntity(String id, String email, String name) {}

  record UserDto(String id, String email, String fullName) {}

  record EntityPage(List<UserEntity> items, int total) {}

  static final Telescope<UserEntity, UserDto> USER_MAPPER = Telescope.map(UserEntity.class)
    .to(UserDto.class)
    .field(UserEntity::id)
    .to(UserDto::id)
    .field(UserEntity::email)
    .to(UserDto::email)
    .field(UserEntity::name)
    .to(UserDto::fullName) // rename across the boundary
    .build();

  @Nested
  @DisplayName("Forward / backward")
  class RoundTrip {

    @Test
    @DisplayName("read converts entity → dto, honoring the field rename")
    void forward() {
      final var entity = new UserEntity("u1", "a@x.com", "Alice");
      final var dto = USER_MAPPER.read(entity);
      assertEquals(new UserDto("u1", "a@x.com", "Alice"), dto);
    }

    @Test
    @DisplayName("round-trip entity → dto → entity is identity")
    void roundTrip() {
      final var entity = new UserEntity("u1", "a@x.com", "Alice");
      final var dto = USER_MAPPER.read(entity);
      // update with identity then read back the entity form
      final var back = USER_MAPPER.set(entity, dto);
      assertEquals(entity, back);
    }

    @Test
    @DisplayName("update round-trips through the DTO and returns an entity")
    void updateThroughDto() {
      final var entity = new UserEntity("u1", "A@X.COM", "Alice");
      final var lowered = USER_MAPPER.update(entity, dto ->
        new UserDto(dto.id(), dto.email().toLowerCase(), dto.fullName())
      );
      assertEquals(new UserEntity("u1", "a@x.com", "Alice"), lowered);
    }
  }

  @Nested
  @DisplayName("Composition")
  class Composition {

    @Test
    @DisplayName("threads through a longer path: page → each item → as DTO → field → update")
    void composesIntoPath() {
      final var page = new EntityPage(
        List.of(new UserEntity("u1", "A@X.COM", "Alice"), new UserEntity("u2", "B@Y.COM", "Bob")),
        2
      );

      final var normalized = Telescope.of(EntityPage.class)
        .each(EntityPage::items)
        .then(USER_MAPPER)
        .field(UserDto::email)
        .update(page, String::toLowerCase);

      assertEquals("a@x.com", normalized.items().get(0).email());
      assertEquals("b@y.com", normalized.items().get(1).email());
      assertEquals(2, normalized.total());
      assertEquals("Alice", normalized.items().get(0).name()); // untouched, survived the round-trip
    }
  }

  @Nested
  @DisplayName("Bijection enforcement")
  class Bijection {

    record A(String x, String y) {}

    record B(String p, String q) {}

    @Test
    @DisplayName("build() throws when a target field is unmapped")
    void unmappedTarget() {
      final var ex = assertThrows(
        IllegalStateException.class,
        () -> Telescope.map(A.class).to(B.class).field(A::x).to(B::p).build() // q unmapped
      );
      assertEquals(true, ex.getMessage().contains("'q'"));
    }

    @Test
    @DisplayName("build() throws when a source field is unmapped")
    void unmappedSource() {
      final var ex = assertThrows(
        IllegalStateException.class,
        // both B fields mapped from x, but A.y is never a source
        () -> Telescope.map(A.class).to(B.class).field(A::x).to(B::p).field(A::x).to(B::q).build()
      );
      assertEquals(true, ex.getMessage().contains("'y'"));
    }

    @Test
    @DisplayName("complete bijection builds successfully")
    void complete() {
      final var mapper = Telescope.map(A.class).to(B.class).field(A::x).to(B::p).field(A::y).to(B::q).build();
      assertEquals(new B("1", "2"), mapper.read(new A("1", "2")));
    }
  }
}
