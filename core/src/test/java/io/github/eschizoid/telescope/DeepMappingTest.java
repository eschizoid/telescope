package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.mapping.Mapping.to;
import static io.github.eschizoid.telescope.mapping.Mapping.via;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.conversion.Mapper;
import io.github.eschizoid.telescope.mapping.Mapping;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for the deep recursive {@link Telescope#map(Class, Class, Mapping[])} / {@link
 * Telescope#mapper(Class, Class, Mapping[])} factories — the "explore the academia boundaries"
 * shape. Each test exercises a different facet of the recursion: same-name copy, container
 * traversal at multiple depths, renames keyed by type pair applying at any depth, typed transforms,
 * nested mappers via {@code via}, and self-referencing structures (cycle handling).
 */
class DeepMappingTest {

  // --- Pure same-name structures (no renames anywhere) ---
  record SameAddrEntity(String city, String zip) {}

  record SameAddrDto(String city, String zip) {}

  record SameUserEntity(String email, SameAddrEntity address) {}

  record SameUserDto(String email, SameAddrDto address) {}

  // --- 5-level deeply-nested structure with one rename + containers + Optional + Map ---
  record AddressEntity(String city, String zip) {}

  record AddressDto(String city, String zip) {}

  record UserEntity(String name, String email, AddressEntity address) {}

  record UserDto(String fullName, String email, AddressDto address) {} // name → fullName

  record TeamEntity(String name, List<UserEntity> users) {}

  record TeamDto(String name, List<UserDto> users) {}

  record DepartmentEntity(String name, List<TeamEntity> teams, Optional<UserEntity> head) {}

  record DepartmentDto(String name, List<TeamDto> teams, Optional<UserDto> head) {}

  record CompanyEntity(
    String name,
    int founded,
    List<DepartmentEntity> departments,
    Map<String, AddressEntity> officesByRegion,
    Optional<UserEntity> ceo
  ) {}

  record CompanyDto(
    String name,
    int since, // founded → since
    List<DepartmentDto> departments,
    Map<String, AddressDto> officesByRegion,
    Optional<UserDto> ceo
  ) {}

  // --- Self-referencing structure for cycle handling ---
  record NodeEntity(String label, Optional<NodeEntity> child) {}

  record NodeDto(String label, Optional<NodeDto> child) {}

  // --- Typed transform: int year ↔ String ---
  record EventEntity(String name, int year) {}

  record EventDto(String name, String year) {}

  @Nested
  @DisplayName("Pure same-name deep copy — no overrides needed")
  class PureAuto {

    @Test
    @DisplayName("flat record pair with no nesting copies identically (1-liner shape)")
    void flatPair() {
      final var mapper = Telescope.map(SameAddrEntity.class, SameAddrDto.class);
      final var src = new SameAddrEntity("NYC", "10001");
      final var dto = mapper.read(src);
      assertEquals("NYC", dto.city());
      assertEquals("10001", dto.zip());
    }

    @Test
    @DisplayName("nested record (User with Address) recurses one level")
    void oneLevelNested() {
      final var mapper = Telescope.map(SameUserEntity.class, SameUserDto.class);
      final var src = new SameUserEntity("u@x", new SameAddrEntity("NYC", "10001"));
      final var dto = mapper.read(src);
      assertEquals("u@x", dto.email());
      assertEquals("NYC", dto.address().city());
    }
  }

  @Nested
  @DisplayName("Type-pair overrides — renames apply at any depth where the pair recurses")
  class TypePairOverrides {

    @Test
    @DisplayName("5-level nesting with two renames — every field threads through correctly")
    void fiveLevelEndToEnd() {
      final Telescope<CompanyEntity, CompanyDto> companyMapper = Telescope.map(
        CompanyEntity.class,
        CompanyDto.class,
        to(CompanyEntity::founded, CompanyDto::since),
        to(UserEntity::name, UserDto::fullName)
      );

      final var entity = new CompanyEntity(
        "Acme",
        2020,
        List.of(
          new DepartmentEntity(
            "Engineering",
            List.of(
              new TeamEntity(
                "Platform",
                List.of(
                  new UserEntity("alice", "alice@acme.com", new AddressEntity("NYC", "10001")),
                  new UserEntity("bob", "bob@acme.com", new AddressEntity("SF", "94016"))
                )
              )
            ),
            Optional.of(new UserEntity("eve", "eve@acme.com", new AddressEntity("LA", "90001")))
          )
        ),
        Map.of("US", new AddressEntity("NYC", "10001"), "EU", new AddressEntity("LON", "EC1A")),
        Optional.of(new UserEntity("zane", "z@acme.com", new AddressEntity("AUS", "78701")))
      );

      final CompanyDto dto = companyMapper.read(entity);

      // Top-level rename: founded → since
      assertEquals(2020, dto.since());
      assertEquals("Acme", dto.name());

      // 4-deep traversal through List → List → List with User::name → UserDto::fullName rename
      assertEquals("Engineering", dto.departments().get(0).name());
      assertEquals("Platform", dto.departments().get(0).teams().get(0).name());
      assertEquals("alice", dto.departments().get(0).teams().get(0).users().get(0).fullName());
      assertEquals("bob", dto.departments().get(0).teams().get(0).users().get(1).fullName());

      // Nested Address (sub-record under User)
      assertEquals("SF", dto.departments().get(0).teams().get(0).users().get(1).address().city());

      // Optional<User> at department level (User rename applies here too)
      assertEquals("eve", dto.departments().get(0).head().orElseThrow().fullName());

      // Map<String, Address> at company level — recurses on values, keys preserved
      assertEquals("LON", dto.officesByRegion().get("EU").city());
      assertEquals("NYC", dto.officesByRegion().get("US").city());

      // Optional<User> at company level (same User rename applies)
      assertEquals("zane", dto.ceo().orElseThrow().fullName());
    }

    @Test
    @DisplayName(
      "rename applied wherever the type pair recurses — User::name → UserDto::fullName " +
        "fires twice (in users[] and in head Optional)"
    )
    void renameAppliesAtEveryDepth() {
      final var mapper = Telescope.map(
        DepartmentEntity.class,
        DepartmentDto.class,
        to(UserEntity::name, UserDto::fullName)
      );
      final var entity = new DepartmentEntity(
        "X",
        List.of(new TeamEntity("T", List.of(new UserEntity("alice", "a@x", new AddressEntity("c", "z"))))),
        Optional.of(new UserEntity("eve", "e@x", new AddressEntity("c", "z")))
      );
      final var dto = mapper.read(entity);
      assertEquals("alice", dto.teams().get(0).users().get(0).fullName());
      assertEquals("eve", dto.head().orElseThrow().fullName());
    }
  }

  @Nested
  @DisplayName("Round-trip — forward + backward returns the original")
  class RoundTrip {

    @Test
    @DisplayName("backward(forward(entity)) equals entity for a 5-level mapping")
    void deepRoundTrip() {
      final Mapper<CompanyEntity, CompanyDto> companyMapper = Telescope.mapper(
        CompanyEntity.class,
        CompanyDto.class,
        to(CompanyEntity::founded, CompanyDto::since),
        to(UserEntity::name, UserDto::fullName)
      );
      final var entity = new CompanyEntity(
        "Acme",
        2020,
        List.of(
          new DepartmentEntity(
            "Eng",
            List.of(new TeamEntity("P", List.of(new UserEntity("a", "a@x", new AddressEntity("NYC", "10001"))))),
            Optional.empty()
          )
        ),
        Map.of("US", new AddressEntity("NYC", "10001")),
        Optional.empty()
      );
      final var dto = companyMapper.read(entity);
      assertEquals(entity, companyMapper.backward(dto));
    }
  }

  @Nested
  @DisplayName("Typed transform — int ↔ String at the leaf via to(..., fwd, bwd)")
  class TypedTransforms {

    @Test
    @DisplayName("typed transform applied at the right pair")
    void intYearToStringYear() {
      final var mapper = Telescope.mapper(
        EventEntity.class,
        EventDto.class,
        to(EventEntity::year, EventDto::year, Object::toString, Integer::parseInt)
      );
      final var entity = new EventEntity("launch", 2024);
      final var dto = mapper.read(entity);
      assertEquals("2024", dto.year());
      assertEquals(entity, mapper.backward(dto));
    }
  }

  @Nested
  @DisplayName("Nested containers — List<Optional<X>>, Optional<List<X>>, Map<K, List<X>> auto-lift")
  class NestedContainers {

    record InnerE(String name) {}

    record InnerD(String name) {}

    record OuterE(List<Optional<InnerE>> items) {}

    record OuterD(List<Optional<InnerD>> items) {}

    @Test
    @DisplayName("List<Optional<Record>> auto-lifts both layers; empty Optional round-trips")
    void listOfOptional() {
      final var mapper = Telescope.mapper(OuterE.class, OuterD.class);
      final var src = new OuterE(List.of(Optional.of(new InnerE("a")), Optional.empty(), Optional.of(new InnerE("b"))));
      final var dto = mapper.read(src);
      assertEquals("a", dto.items().get(0).orElseThrow().name());
      assertEquals(Optional.empty(), dto.items().get(1));
      assertEquals("b", dto.items().get(2).orElseThrow().name());
      assertEquals(src, mapper.backward(dto));
    }

    record MapE(Map<String, List<InnerE>> byKey) {}

    record MapD(Map<String, List<InnerD>> byKey) {}

    @Test
    @DisplayName("Map<K, List<Record>> auto-lifts values + list element")
    void mapOfList() {
      final var mapper = Telescope.mapper(MapE.class, MapD.class);
      final var src = new MapE(Map.of("a", List.of(new InnerE("x"), new InnerE("y"))));
      final var dto = mapper.read(src);
      assertEquals("x", dto.byKey().get("a").get(0).name());
      assertEquals("y", dto.byKey().get("a").get(1).name());
      assertEquals(src, mapper.backward(dto));
    }
  }

  @Nested
  @DisplayName("Mapper.patch — sparse overlay using the per-component backward Iso")
  class Patch {

    record FlatEntity(String id, String email, String name) {}

    record FlatDto(String id, String email, String name) {}

    @Test
    @DisplayName("non-null partial fields overlay; null fields leave the source value alone")
    void sparsePatch() {
      final var mapper = Telescope.mapper(FlatEntity.class, FlatDto.class);
      final var base = new FlatEntity("u1", "old@x", "alice");
      final var partial = new FlatDto(null, "new@x", null);
      final var out = mapper.patch(base, partial);
      assertEquals("u1", out.id()); // not in partial → unchanged
      assertEquals("new@x", out.email()); // overlaid
      assertEquals("alice", out.name()); // not in partial → unchanged
    }

    @Test
    @DisplayName("rename + typed transform are honored by patch via each field's backward Iso")
    void patchWithRenameAndTransform() {
      record E(String id, int year) {}
      record D(String id, String year) {}
      final var mapper = Telescope.mapper(E.class, D.class, to(E::year, D::year, Object::toString, Integer::parseInt));
      final var base = new E("u1", 2020);
      final var out = mapper.patch(base, new D(null, "2024"));
      assertEquals("u1", out.id());
      assertEquals(2024, out.year());
    }
  }

  @Nested
  @DisplayName("via — drop a pre-built nested mapper instead of letting recursion build one")
  class PreBuiltViaMapper {

    @Test
    @DisplayName("via(...) takes precedence over auto-recursion for its target field")
    void viaWinsOverAuto() {
      // An address mapper that "shouts" — replaces the city. Lets us see whether deep recursion
      // built it or our via override won.
      final Mapper<AddressEntity, AddressDto> loudAddress = Telescope.mapper(
        AddressEntity.class,
        AddressDto.class,
        to(AddressEntity::city, AddressDto::city, String::toUpperCase, String::toLowerCase),
        to(AddressEntity::zip, AddressDto::zip)
      );
      final var mapper = Telescope.mapper(
        UserEntity.class,
        UserDto.class,
        to(UserEntity::name, UserDto::fullName),
        via(UserEntity::address, UserDto::address, loudAddress)
      );
      final var entity = new UserEntity("alice", "a@x", new AddressEntity("nyc", "10001"));
      final var dto = mapper.read(entity);
      assertEquals("NYC", dto.address().city()); // loudAddress shouted; auto would have left "nyc"
    }
  }

  @Nested
  @DisplayName("Cycle handling — self-referencing structures terminate cleanly")
  class Cycles {

    @Test
    @DisplayName("Node containing Optional<Node> resolves and round-trips")
    void selfReferentialNode() {
      final var mapper = Telescope.mapper(NodeEntity.class, NodeDto.class);
      final var leaf = new NodeEntity("leaf", Optional.empty());
      final var mid = new NodeEntity("mid", Optional.of(leaf));
      final var root = new NodeEntity("root", Optional.of(mid));
      final var dto = mapper.read(root);
      assertEquals("root", dto.label());
      assertEquals("mid", dto.child().orElseThrow().label());
      assertEquals("leaf", dto.child().orElseThrow().child().orElseThrow().label());
      assertEquals(root, mapper.backward(dto));
    }
  }

  @Nested
  @DisplayName("Errors — surface a precise message at the unmappable point")
  class Errors {

    record OddSrc(String name, int extraOnSource) {}

    record OddTgt(String name, int extraOnTarget) {}

    @Test
    @DisplayName("unmatched names on either side throw IllegalStateException at construction")
    void unmatchedNamesThrow() {
      final var ex = assertThrows(IllegalStateException.class, () -> Telescope.map(OddSrc.class, OddTgt.class));
      assertTrue(
        ex.getMessage().contains("extraOnTarget") || ex.getMessage().contains("extraOnSource"),
        ex.getMessage()
      );
    }

    record MixedSrc(String name, AddressEntity address) {}

    record MixedTgt(String name, String address) {}

    @Test
    @DisplayName("incompatible component shapes throw — record vs scalar")
    void incompatibleShapesThrow() {
      final var ex = assertThrows(IllegalStateException.class, () -> Telescope.map(MixedSrc.class, MixedTgt.class));
      assertTrue(ex.getMessage().contains("address"), ex.getMessage());
    }

    @Test
    @DisplayName("duplicate override rows for the same target field fail fast at construction")
    void duplicateOverrideRowsRejected() {
      final var ex = assertThrows(IllegalArgumentException.class, () ->
        Telescope.map(
          UserEntity.class,
          UserDto.class,
          to(UserEntity::name, UserDto::fullName),
          to(UserEntity::email, UserDto::fullName) // same target — silent overwrite was the bug
        )
      );
      assertTrue(ex.getMessage().contains("duplicate"), ex.getMessage());
    }
  }
}
