package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.Mapping.auto;
import static io.github.eschizoid.telescope.Mapping.to;
import static io.github.eschizoid.telescope.Mapping.via;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Telescope#map(Mapping[])} and {@link Telescope#mapper(Mapping[])} — the
 * declarative varargs sibling of {@link MapBuilder}. Each {@code to(...)} / {@code via(...)} /
 * {@code auto()} row is one correspondence; the factory recovers source/target classes from the
 * first row that carries them.
 *
 * <p>Mirrors the {@link MappingBuilderTest} coverage but exercises the new shape; the two should
 * produce equivalent {@code Telescope} / {@code Mapper} values for the same correspondence set.
 */
class MappingVarargsTest {

  record AddrEntity(String city, String zip) {}

  record AddrDto(String city, String zip) {}

  record UserEntity(String id, String email, String name, AddrEntity address) {}

  record UserDto(String id, String email, String fullName, AddrDto address) {}

  // Pure-auto target type for the empty-mapping degenerate case.
  record SameA(String x, int y) {}

  record SameB(String x, int y) {}

  private static final Mapper<AddrEntity, AddrDto> ADDR = Telescope.mapper(auto(AddrEntity.class, AddrDto.class));

  @Nested
  @DisplayName("Class inference — recovered from to(...) / via(...) accessors")
  class InferenceFromAccessors {

    @Test
    @DisplayName("to(src, tgt) reveals source and target classes via SerializedLambda")
    void inferredFromTo() {
      final Telescope<UserEntity, UserDto> mapper = Telescope.map(
        to(UserEntity::id, UserDto::id),
        to(UserEntity::email, UserDto::email),
        to(UserEntity::name, UserDto::fullName),
        via(UserEntity::address, UserDto::address, ADDR)
      );
      final var src = new UserEntity("u1", "U@X", "Alice", new AddrEntity("NYC", "10001"));
      final var dto = mapper.read(src);
      assertEquals("Alice", dto.fullName());
      assertEquals("NYC", dto.address().city());
    }

    @Test
    @DisplayName("auto() rides on a sibling to(...) row's inference")
    void autoRidesOnSibling() {
      final Telescope<UserEntity, UserDto> mapper = Telescope.map(
        to(UserEntity::name, UserDto::fullName),
        via(UserEntity::address, UserDto::address, ADDR),
        auto()
      );
      final var src = new UserEntity("u1", "U@X", "Alice", new AddrEntity("NYC", "10001"));
      final var dto = mapper.read(src);
      assertEquals("u1", dto.id());
      assertEquals("U@X", dto.email());
      assertEquals("Alice", dto.fullName());
      assertEquals("NYC", dto.address().city());
    }
  }

  @Nested
  @DisplayName("Class inference — explicit via auto(A.class, B.class)")
  class ExplicitInference {

    @Test
    @DisplayName("auto(A.class, B.class) supplies the classes when no other row carries them")
    void allAutoWithExplicit() {
      final Telescope<SameA, SameB> mapper = Telescope.map(auto(SameA.class, SameB.class));
      final var out = mapper.read(new SameA("hello", 42));
      assertEquals("hello", out.x());
      assertEquals(42, out.y());
    }

    @Test
    @DisplayName("the explicit auto can be combined with row-form auto on either side of it")
    void mixedExplicitAndImplicitAuto() {
      final Telescope<SameA, SameB> mapper = Telescope.map(auto(SameA.class, SameB.class), auto());
      final var out = mapper.read(new SameA("hello", 42));
      assertEquals("hello", out.x());
      assertEquals(42, out.y());
    }
  }

  @Nested
  @DisplayName("Class inference — fails noisily when nothing carries the classes")
  class InferenceFailure {

    @Test
    @DisplayName("Telescope.map(auto()) with no explicit row throws IAE")
    void pureBareAutoFails() {
      final Mapping<SameA, SameB> bare = auto();
      final var ex = assertThrows(IllegalArgumentException.class, () -> Telescope.map(bare));
      assertTrue(ex.getMessage().contains("auto(A.class, B.class)"), ex.getMessage());
    }

    @Test
    @DisplayName("Telescope.map() with no rows at all says \"No rows were passed\"")
    void zeroRowsFails() {
      final var ex = assertThrows(IllegalArgumentException.class, () -> Telescope.<SameA, SameB>map());
      assertTrue(ex.getMessage().contains("No rows were passed"), ex.getMessage());
    }

    @Test
    @DisplayName("a lambda passed to to(...) is rejected at map(...) time (not silently misclassified)")
    void lambdaInToIsRejected() {
      // a -> a.x() is a lambda, not a method reference. Mapping.sourceClass() must throw via
      // Telescope.implClassOf rather than silently returning the lambda's enclosing class.
      final Telescope.Accessor<SameA, String> lambda = a -> a.x();
      final Mapping<SameA, SameB> bad = to(lambda, SameB::x);
      assertThrows(IllegalArgumentException.class, () -> Telescope.map(bad, auto()));
    }
  }

  @Nested
  @DisplayName("Equivalence — varargs and fluent builder produce the same Telescope")
  class EquivalenceWithBuilder {

    @Test
    @DisplayName(
      "Telescope.map(to(...), via(...)) equals Telescope.map(A.class).to(B.class).field(...).to(...).via(...).build()"
    )
    void equivalent() {
      final var viaVarargs = Telescope.map(
        to(UserEntity::id, UserDto::id),
        to(UserEntity::email, UserDto::email),
        to(UserEntity::name, UserDto::fullName),
        via(UserEntity::address, UserDto::address, ADDR)
      );
      final var viaBuilder = Telescope.map(UserEntity.class)
        .to(UserDto.class)
        .field(UserEntity::id)
        .to(UserDto::id)
        .field(UserEntity::email)
        .to(UserDto::email)
        .field(UserEntity::name)
        .to(UserDto::fullName)
        .field(UserEntity::address)
        .via(UserDto::address, ADDR)
        .build();
      final var src = new UserEntity("u1", "U@X", "Alice", new AddrEntity("NYC", "10001"));
      assertEquals(viaBuilder.read(src), viaVarargs.read(src));
    }
  }

  @Nested
  @DisplayName("Telescope.mapper(...) — same factory, Mapper<A,B> return for patch + nesting")
  class MapperEntry {

    @Test
    @DisplayName("Telescope.mapper(...) is the Mapper sibling and supports patch")
    void mapperWithPatch() {
      final Mapper<UserEntity, UserDto> mapper = Telescope.mapper(
        to(UserEntity::id, UserDto::id),
        to(UserEntity::email, UserDto::email),
        to(UserEntity::name, UserDto::fullName),
        via(UserEntity::address, UserDto::address, ADDR)
      );
      final var src = new UserEntity("u1", "U@X", "Alice", new AddrEntity("NYC", "10001"));
      final var patchDto = new UserDto(null, "new@x", null, null);
      final var patched = mapper.patch(src, patchDto);
      assertEquals("new@x", patched.email());
      assertEquals("u1", patched.id());
      assertEquals("Alice", patched.name());
    }
  }

  @Nested
  @DisplayName("Mapping reuse — a single Mapping<A, B> value is reusable across factory calls")
  class Reuse {

    @Test
    @DisplayName("the same explicit row can be passed to both map and mapper")
    void sameRowsTwoFactories() {
      final Mapping<UserEntity, UserDto> idRow = to(UserEntity::id, UserDto::id);
      final Mapping<UserEntity, UserDto> emailRow = to(UserEntity::email, UserDto::email);
      final Mapping<UserEntity, UserDto> nameRow = to(UserEntity::name, UserDto::fullName);
      final Mapping<UserEntity, UserDto> addrRow = via(UserEntity::address, UserDto::address, ADDR);
      final var t = Telescope.map(idRow, emailRow, nameRow, addrRow);
      final var m = Telescope.mapper(idRow, emailRow, nameRow, addrRow);
      final var src = new UserEntity("u1", "U@X", "Alice", new AddrEntity("NYC", "10001"));
      assertEquals(t.read(src), m.read(src));
    }
  }

  @Nested
  @DisplayName("Bridge — the varargs shape composes through Telescope.then like any other")
  class Composition {

    record EntityPage(java.util.List<UserEntity> items) {}

    record DtoPage(java.util.List<UserDto> items) {}

    @Test
    @DisplayName("Telescope.map(...) result threads through .each + .then in a longer path")
    void threadsThroughLongerPath() {
      final var userMapper = Telescope.map(
        to(UserEntity::id, UserDto::id),
        to(UserEntity::email, UserDto::email),
        to(UserEntity::name, UserDto::fullName),
        via(UserEntity::address, UserDto::address, ADDR)
      );
      final var p = new EntityPage(
        java.util.List.of(new UserEntity("u1", "FOO@X", "Alice", new AddrEntity("NYC", "10001")))
      );
      final var out = Telescope.of(EntityPage.class)
        .each(EntityPage::items)
        .then(userMapper)
        .field(UserDto::email)
        .update(p, String::toLowerCase);
      assertEquals("foo@x", out.items().getFirst().email());
    }
  }

  @Nested
  @DisplayName("Sanity — identity round-trip on a same-shape mapping")
  class Sanity {

    @Test
    @DisplayName("auto(A.class, B.class) mapping is identity on round-trip")
    void identityRoundtrip() {
      final var mapper = Telescope.mapper(auto(SameA.class, SameB.class));
      final var src = new SameA("x", 7);
      final var dto = mapper.read(src);
      assertEquals("x", dto.x());
      assertEquals(7, dto.y());
      // Reverse direction closes the bijection — the iso must round-trip identically.
      assertEquals(src, mapper.backward(dto));
    }
  }
}
