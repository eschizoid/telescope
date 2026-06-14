package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.mapping.Mapping.drop;
import static io.github.eschizoid.telescope.mapping.Mapping.to;
import static io.github.eschizoid.telescope.mapping.Mapping.via;
import static io.github.eschizoid.telescope.mapping.WriteHint.WriteStrategy.BUILDER;
import static io.github.eschizoid.telescope.mapping.WriteHint.WriteStrategy.CONSTRUCTOR;
import static io.github.eschizoid.telescope.mapping.WriteHint.WriteStrategy.FIELDS;
import static io.github.eschizoid.telescope.mapping.WriteHint.writeBean;
import static io.github.eschizoid.telescope.mapping.WriteHint.writeBeans;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.conversion.Mapper;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for the deep recursive {@link Telescope#map(Class, Class,
 * io.github.eschizoid.telescope.mapping.MapStep...)} / {@link Telescope#mapper(Class, Class,
 * io.github.eschizoid.telescope.mapping.MapStep...)} factories — the "explore the academia
 * boundaries" shape. Each test exercises a different facet of the recursion: same-name copy,
 * container traversal at multiple depths (List, Set, Map, Optional — N-level nestable), renames
 * keyed by type pair applying at any depth, typed transforms, nested mappers via {@code via},
 * per-target {@code writeBean} construction hints, and self-referencing structures (cycle
 * handling).
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

    // -- RED: via with a List-typed accessor should lift the element-level mapper through the list
    // --
    //
    // Today this throws "incompatible source/target shapes" because Via stores the mapper at
    // src/tgt accessor type level. When srcAcc returns List<X> and tgtAcc returns List<Y>, the
    // user expects to pass a Mapper<X, Y> and have it auto-lift via Iso.liftList. Without this,
    // every nested-collection mapping forces the user to either hoist the element-level rows up
    // to the parent or hand-roll list lifting.

    record TeamHeadEntity(String name, List<UserEntity> members) {}

    record TeamHeadDto(String name, List<UserDto> members) {}

    @Test
    @DisplayName("viaList(srcAcc, tgtAcc, mapper<X, Y>) lifts the element mapper through a List-typed accessor pair")
    void viaAutoLiftsThroughList() {
      // Build a UserEntity → UserDto element mapper with a rename that should fire on every list
      // element, not just at the auto-recursive default.
      final Mapper<UserEntity, UserDto> userMapper = Telescope.mapper(
        UserEntity.class,
        UserDto.class,
        to(UserEntity::name, UserDto::fullName)
      );
      // The team mapper hands the element mapper to viaList — telescope lifts it through List.
      final var teamMapper = Telescope.mapper(
        TeamHeadEntity.class,
        TeamHeadDto.class,
        via(TeamHeadEntity::members, TeamHeadDto::members, userMapper)
      );
      final var entity = new TeamHeadEntity(
        "platform",
        List.of(
          new UserEntity("alice", "a@x", new AddressEntity("NYC", "10001")),
          new UserEntity("bob", "b@x", new AddressEntity("NYC", "10001"))
        )
      );
      final var dto = teamMapper.read(entity);
      assertEquals("platform", dto.name());
      assertEquals(2, dto.members().size());
      // The userMapper's rename row would have fired if telescope lifted it through the list.
      assertEquals("alice", dto.members().get(0).fullName());
      assertEquals("bob", dto.members().get(1).fullName());
      assertEquals(entity, teamMapper.backward(dto));
    }

    // -- end via auto-lift --

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

    // Mutable bean pair to construct a literal VALUE cycle (records can't reference each other
    // bidirectionally after construction). Mirrors a bidirectional JPA association.
    static final class CycEntity {

      private String name;
      private CycEntity ref;

      public CycEntity() {}

      public String getName() {
        return name;
      }

      public void setName(final String name) {
        this.name = name;
      }

      public CycEntity getRef() {
        return ref;
      }

      public void setRef(final CycEntity ref) {
        this.ref = ref;
      }
    }

    static final class CycDto {

      private String name;
      private CycDto ref;

      public CycDto() {}

      public String getName() {
        return name;
      }

      public void setName(final String name) {
        this.name = name;
      }

      public CycDto getRef() {
        return ref;
      }

      public void setRef(final CycDto ref) {
        this.ref = ref;
      }
    }

    @Test
    @DisplayName("Value-level cycle in a bean graph severs at second encounter — no StackOverflowError")
    void valueCycleSeversCleanly() {
      // alice.ref → bob; bob.ref → alice. This is the literal-value cycle shape that bidirectional
      // Hibernate associations produce (entity.parent + entity.children pointing at the parent).
      // Before the per-traversal IdentityHashMap guard in DeepMap.lazyCacheIso, mapper.forward
      // would StackOverflow walking ref → ref → ref → ... indefinitely.
      final var mapper = Telescope.mapper(CycEntity.class, CycDto.class);
      final var alice = new CycEntity();
      alice.setName("alice");
      final var bob = new CycEntity();
      bob.setName("bob");
      alice.setRef(bob);
      bob.setRef(alice);

      final var dto = mapper.forward(alice);
      assertEquals("alice", dto.getName());
      assertEquals("bob", dto.getRef().getName());
      assertEquals("alice", dto.getRef().getRef().getName());
      // Cycle severed on revisit — the inner alice→bob→alice→bob link collapses to null instead
      // of recursing forever. The guard fires inside lazyCacheIso (the per-field recursive Iso);
      // the top-level mapper.forward call doesn't go through the guard, so the cycle is finite
      // but not the shortest possible (severing happens at the 4th level, not the 2nd). The graph
      // is finite by construction; structure is lost on the second occurrence — acknowledged
      // trade-off documented in the cycle guard's javadoc.
      assertNull(dto.getRef().getRef().getRef(), "fourth encounter (bob revisited) should be severed");
    }

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

  // ----- writeBean hint fixtures -----

  // Immutable all-args POJO: no setters, no no-arg ctor, no builder. autoWriter without the new
  // 4th rung would refuse; with the 4th rung (and -parameters compiled in) it auto-detects.
  static final class ImmutablePojo {

    private final String sku;
    private final int qty;

    public ImmutablePojo(final String sku, final int qty) {
      this.sku = sku;
      this.qty = qty;
    }

    public String getSku() {
      return sku;
    }

    public int getQty() {
      return qty;
    }

    @Override
    public boolean equals(final Object o) {
      return o instanceof ImmutablePojo p && Objects.equals(p.sku, sku) && p.qty == qty;
    }

    @Override
    public int hashCode() {
      return Objects.hash(sku, qty);
    }
  }

  record OrderRecord(String sku, int qty) {}

  // A bean with both a builder AND a no-arg ctor + fields, so the precedence test
  // (writeBean FIELDS over the autoWriter-chosen BUILDER) is observable.
  static final class DualPojo {

    private String name;
    private int score;

    public DualPojo() {}

    public static Builder builder() {
      return new Builder();
    }

    public String getName() {
      return name;
    }

    public int getScore() {
      return score;
    }

    @Override
    public boolean equals(final Object o) {
      return o instanceof DualPojo p && Objects.equals(p.name, name) && p.score == score;
    }

    @Override
    public int hashCode() {
      return Objects.hash(name, score);
    }

    static final class Builder {

      // Mark the builder path: the resulting DualPojo's name has "[built]" appended. The fields-
      // strategy path bypasses this entirely, so a FIELDS-hinted mapping yields "alice" while a
      // BUILDER-hinted (or autoWriter-default) mapping yields "alice[built]".
      private String name;
      private int score;

      public Builder name(final String n) {
        this.name = n + "[built]";
        return this;
      }

      public Builder score(final int s) {
        this.score = s;
        return this;
      }

      public DualPojo build() {
        final var p = new DualPojo();
        p.name = name;
        p.score = score;
        return p;
      }
    }
  }

  record DualRecord(String name, int score) {}

  // POJO with no builder, no no-arg ctor, and TWO public constructors of matching arity →
  // autoWriter
  // CTOR fallback must refuse (ambiguous). With an explicit writeBean(...) hint it could be made to
  // work — but the simpler test is that autoWriter refuses cleanly without a hint.
  static final class AmbiguousCtorPojo {

    private final String a;
    private final String b;

    public AmbiguousCtorPojo(final String a, final String b) {
      this.a = a;
      this.b = b;
    }

    // Decoy ctor of the same arity — auto fallback must reject.
    public AmbiguousCtorPojo(final String a, final Integer ignoredSentinel) {
      this.a = a;
      this.b = String.valueOf(ignoredSentinel);
    }

    public String getA() {
      return a;
    }

    public String getB() {
      return b;
    }
  }

  record AmbiguousRecord(String a, String b) {}

  @Nested
  @DisplayName("writeBean hints — explicit per-target construction strategy")
  class WriteHints {

    @Test
    @DisplayName("CONSTRUCTOR hint constructs an immutable all-args-only POJO round-trip")
    void constructorHintForImmutablePojo() {
      final var mapper = Telescope.mapper(
        OrderRecord.class,
        ImmutablePojo.class,
        writeBean(ImmutablePojo.class, CONSTRUCTOR)
      );
      final var src = new OrderRecord("SKU-1", 42);
      final var pojo = mapper.read(src);
      assertEquals("SKU-1", pojo.getSku());
      assertEquals(42, pojo.getQty());
      assertEquals(src, mapper.backward(pojo));
    }

    @Test
    @DisplayName("autoWriter constructor fallback handles a same-arity unambiguous immutable POJO without a hint")
    void autoWriterConstructorFallback() {
      // No writeBean hint — the new 4th rung in Beans.autoWriter picks ConstructorWriter on its own
      // because ImmutablePojo has exactly one public 2-arg ctor compiled with -parameters.
      final var mapper = Telescope.mapper(OrderRecord.class, ImmutablePojo.class);
      final var pojo = mapper.read(new OrderRecord("SKU-2", 7));
      assertEquals("SKU-2", pojo.getSku());
      assertEquals(7, pojo.getQty());
    }

    @Test
    @DisplayName("FIELDS hint wins over autoWriter when target also has a builder")
    void fieldsHintWinsOverBuilder() {
      // DualPojo has BOTH a builder() (autoWriter would pick BuilderWriter) and writable fields.
      // The FIELDS hint must bypass the builder entirely — observable because the builder mutates
      // the name (appends "[built]"), so a fields path yields the raw value.
      final var mapper = Telescope.mapper(DualRecord.class, DualPojo.class, writeBean(DualPojo.class, FIELDS));
      final var pojo = mapper.read(new DualRecord("alice", 9));
      assertEquals("alice", pojo.getName()); // not "alice[built]"
      assertEquals(9, pojo.getScore());
    }

    @Test
    @DisplayName("record-class hint is rejected at resolve time with a descriptive message")
    void recordClassHintRejected() {
      final var ex = assertThrows(IllegalArgumentException.class, () ->
        Telescope.map(ImmutablePojo.class, OrderRecord.class, writeBean(OrderRecord.class, CONSTRUCTOR))
      );
      assertTrue(ex.getMessage().contains("record"), ex.getMessage());
    }

    @Test
    @DisplayName("duplicate hints for the same target class are rejected at resolve time")
    void duplicateHintsRejected() {
      final var ex = assertThrows(IllegalArgumentException.class, () ->
        Telescope.map(
          OrderRecord.class,
          ImmutablePojo.class,
          writeBean(ImmutablePojo.class, CONSTRUCTOR),
          writeBean(ImmutablePojo.class, FIELDS)
        )
      );
      assertTrue(ex.getMessage().contains("Duplicate"), ex.getMessage());
    }

    @Test
    @DisplayName("BUILDER hint on a class without a static builder() throws eagerly (not at first to())")
    void incompatibleStrategyEager() {
      assertThrows(IllegalStateException.class, () ->
        Telescope.map(OrderRecord.class, ImmutablePojo.class, writeBean(ImmutablePojo.class, BUILDER))
      );
    }

    @Test
    @DisplayName("a hint whose target class is never reached during recursion is reported, not silently dropped")
    void unusedHintRejected() {
      final var ex = assertThrows(IllegalArgumentException.class, () ->
        Telescope.map(OrderRecord.class, ImmutablePojo.class, writeBean(DualPojo.class, FIELDS))
      );
      assertTrue(ex.getMessage().contains("Unused"), ex.getMessage());
    }

    @Test
    @DisplayName("writeBeans(STRATEGY) sets a default write strategy applied to every unhinted bean target")
    void writeBeansDefaultAppliesToAllTargets() {
      // DualPojo has both a builder() and field-injectable fields — autoWriter would pick BUILDER
      // (and the builder appends "[built]" to name). With writeBeans(FIELDS) as the default, the
      // engine must skip the builder and inject directly into fields, yielding the raw "alice".
      final var mapper = Telescope.mapper(DualRecord.class, DualPojo.class, writeBeans(FIELDS));
      final var pojo = mapper.read(new DualRecord("alice", 9));
      assertEquals("alice", pojo.getName()); // not "alice[built]"
      assertEquals(9, pojo.getScore());
    }

    @Test
    @DisplayName("per-class writeBean(X.class, …) overrides the writeBeans(…) default for that target")
    void perClassHintWinsOverDefault() {
      // Default says FIELDS, but per-class hint says BUILDER — builder wins for DualPojo, so the
      // built-in "[built]" suffix surfaces.
      final var mapper = Telescope.mapper(
        DualRecord.class,
        DualPojo.class,
        writeBeans(FIELDS),
        writeBean(DualPojo.class, BUILDER)
      );
      final var pojo = mapper.read(new DualRecord("alice", 9));
      assertEquals("alice[built]", pojo.getName());
    }

    @Test
    @DisplayName("two writeBeans(…) defaults are rejected eagerly (at most one default per call)")
    void duplicateDefaultRejected() {
      final var ex = assertThrows(IllegalArgumentException.class, () ->
        Telescope.map(DualRecord.class, DualPojo.class, writeBeans(FIELDS), writeBeans(BUILDER))
      );
      assertTrue(ex.getMessage().contains("writeBeans"), ex.getMessage());
    }

    @Test
    @DisplayName("autoWriter throws cleanly for ambiguous multi-ctor POJO when no hint is supplied")
    void ambiguousAutoFallbackRefuses() {
      // resolve() returns successfully — the Iso is built lazily. The throw fires when
      // Reflective.BEANS.construct runs (first iso.to() call) and Beans.autoWriter can't pick a
      // strategy.
      final var mapper = Telescope.mapper(AmbiguousRecord.class, AmbiguousCtorPojo.class);
      final var ex = assertThrows(IllegalStateException.class, () -> mapper.read(new AmbiguousRecord("x", "y")));
      assertTrue(
        ex.getMessage().contains("name-based write strategy") || ex.getMessage().contains("writeBean"),
        ex.getMessage()
      );
    }
  }

  // ----- Set + deeper-nesting fixtures -----

  record TagE(String name) {}

  record TagD(String name) {}

  record TaggedE(Set<TagE> tags) {}

  record TaggedD(Set<TagD> tags) {}

  record SetOptE(Set<Optional<TagE>> items) {}

  record SetOptD(Set<Optional<TagD>> items) {}

  // List<Map<String, Set<Record>>> — four levels of mixed containers nesting a record leaf.
  record FourLevelE(List<Map<String, Set<TagE>>> data) {}

  record FourLevelD(List<Map<String, Set<TagD>>> data) {}

  @Nested
  @DisplayName("Set containers and N-level mixed nesting")
  class SetAndDeepNesting {

    @Test
    @DisplayName("Set<Record> round-trips via Iso.liftSet")
    void setOfRecord() {
      final var mapper = Telescope.mapper(TaggedE.class, TaggedD.class);
      final var src = new TaggedE(new LinkedHashSet<>(List.of(new TagE("red"), new TagE("blue"))));
      final var dto = mapper.read(src);
      assertEquals(Set.of(new TagD("red"), new TagD("blue")), dto.tags());
      assertEquals(src, mapper.backward(dto));
    }

    @Test
    @DisplayName("Set<Optional<Record>> works at two container levels (Set.equals semantics)")
    void setOfOptional() {
      // Set semantics: Optional.empty() is one element regardless of how many empties are added —
      // the liftSet javadoc documents this. We assert via Set.equals, not multiset semantics.
      final var mapper = Telescope.mapper(SetOptE.class, SetOptD.class);
      final var src = new SetOptE(
        new LinkedHashSet<>(List.of(Optional.of(new TagE("a")), Optional.empty(), Optional.of(new TagE("b"))))
      );
      final var dto = mapper.read(src);
      assertEquals(Set.of(Optional.of(new TagD("a")), Optional.<TagD>empty(), Optional.of(new TagD("b"))), dto.items());
      assertEquals(src, mapper.backward(dto));
    }

    // -- Optional<X> ↔ nullable X cross-paradigm bridge --

    record HasOptional(String name, Optional<String> nickname) {}

    record HasNullable(String name, String nickname) {}

    @Test
    @DisplayName("Optional<X> source ↔ nullable X target: Optional.of(x) round-trips to x and back")
    void optionalToNullablePresent() {
      final var mapper = Telescope.mapper(HasOptional.class, HasNullable.class);
      final var src = new HasOptional("alice", Optional.of("ally"));
      final var dst = mapper.forward(src);
      assertEquals("alice", dst.name());
      assertEquals("ally", dst.nickname());
      assertEquals(src, mapper.backward(dst));
    }

    @Test
    @DisplayName("Optional<X> source ↔ nullable X target: Optional.empty() round-trips to null and back")
    void optionalToNullableEmpty() {
      final var mapper = Telescope.mapper(HasOptional.class, HasNullable.class);
      final var src = new HasOptional("alice", Optional.empty());
      final var dst = mapper.forward(src);
      assertEquals("alice", dst.name());
      assertEquals(null, dst.nickname());
      assertEquals(src, mapper.backward(dst));
    }

    @Test
    @DisplayName("nullable X source ↔ Optional<X> target: mirror direction round-trips identically")
    void nullableToOptionalMirror() {
      final var mapper = Telescope.mapper(HasNullable.class, HasOptional.class);
      final var srcPresent = new HasNullable("alice", "ally");
      final var dstPresent = mapper.forward(srcPresent);
      assertEquals("alice", dstPresent.name());
      assertEquals(Optional.of("ally"), dstPresent.nickname());
      assertEquals(srcPresent, mapper.backward(dstPresent));

      final var srcAbsent = new HasNullable("bob", null);
      final var dstAbsent = mapper.forward(srcAbsent);
      assertEquals(Optional.empty(), dstAbsent.nickname());
      assertEquals(srcAbsent, mapper.backward(dstAbsent));
    }

    // -- end Optional ↔ nullable --

    @Test
    @DisplayName("List<Map<K, Set<Record>>> resolves four nested container levels by construction")
    void fourLevelMixedContainer() {
      final var mapper = Telescope.mapper(FourLevelE.class, FourLevelD.class);
      final var src = new FourLevelE(
        List.of(
          Map.of(
            "alpha",
            new LinkedHashSet<>(List.of(new TagE("x"), new TagE("y"))),
            "beta",
            new LinkedHashSet<>(List.of(new TagE("z")))
          )
        )
      );
      final var dto = mapper.read(src);
      assertEquals(Set.of(new TagD("x"), new TagD("y")), dto.data().get(0).get("alpha"));
      assertEquals(Set.of(new TagD("z")), dto.data().get(0).get("beta"));
      assertEquals(src, mapper.backward(dto));
    }
  }

  @Nested
  @DisplayName("drop(srcAccessor) — intentionally exclude a source field from the strict mapper")
  class DropRow {

    record OrderRich(String orderNumber, String customer, String metadata) {}

    record OrderPartner(String orderNumber, String customer) {}

    @Test
    @DisplayName("without drop — strict mapper rejects the unmapped source field with a usable hint")
    void strictModeRejectsUnmappedSource() {
      final var ex = assertThrows(IllegalStateException.class, () ->
        Telescope.mapper(OrderRich.class, OrderPartner.class)
      );
      assertTrue(ex.getMessage().contains("metadata"), ex.getMessage());
      assertTrue(ex.getMessage().contains("no same-name target"), ex.getMessage());
    }

    @Test
    @DisplayName("with drop(OrderRich::metadata) — mapper builds; forward omits the field")
    void dropMakesForwardOmitTheField() {
      final var mapper = Telescope.mapper(OrderRich.class, OrderPartner.class, drop(OrderRich::metadata));
      final var src = new OrderRich("ORD-1", "alice", "secret-internal");
      final var dst = mapper.forward(src);
      assertEquals("ORD-1", dst.orderNumber());
      assertEquals("alice", dst.customer());
    }

    @Test
    @DisplayName("with drop — backward reconstructs the source with a null in the dropped slot")
    void dropMakesBackwardLeaveDroppedFieldNull() {
      final var mapper = Telescope.mapper(OrderRich.class, OrderPartner.class, drop(OrderRich::metadata));
      final var dst = new OrderPartner("ORD-1", "alice");
      final var rebuilt = mapper.backward(dst);
      assertEquals("ORD-1", rebuilt.orderNumber());
      assertEquals("alice", rebuilt.customer());
      assertNull(rebuilt.metadata());
    }

    @Test
    @DisplayName("drop composes with to(...) renames in the same mapper")
    void dropComposesWithRename() {
      final var mapper = Telescope.mapper(
        OrderRich.class,
        OrderPartner.class,
        to(OrderRich::customer, OrderPartner::customer),
        drop(OrderRich::metadata)
      );
      final var src = new OrderRich("ORD-1", "alice", "metadata-value");
      final var dst = mapper.forward(src);
      assertEquals("alice", dst.customer());
      assertEquals("ORD-1", dst.orderNumber());
    }

    @Test
    @DisplayName("duplicate drop on the same source field — same fail-fast guard as a duplicate to(...) row")
    void duplicateDropRejected() {
      final var ex = assertThrows(IllegalArgumentException.class, () ->
        Telescope.mapper(OrderRich.class, OrderPartner.class, drop(OrderRich::metadata), drop(OrderRich::metadata))
      );
      assertTrue(ex.getMessage().contains("metadata"), ex.getMessage());
      assertTrue(ex.getMessage().contains("duplicate"), ex.getMessage());
    }

    // --- two-arg drop(srcAcc, target) — scoped to a nested pair ---
    record CustomerRich(String name, Set<String> tags) {}

    record CustomerPartner(String name) {}

    record ShipmentRich(String code, CustomerRich customer) {}

    record ShipmentPartner(String code, CustomerPartner customer) {}

    @Test
    @DisplayName("two-arg drop scopes the elision to a specific nested (source, target) pair only")
    void twoArgDropAppliesAtNestedPair() {
      final var mapper = Telescope.mapper(
        ShipmentRich.class,
        ShipmentPartner.class,
        drop(CustomerRich::tags, CustomerPartner.class)
      );
      final var src = new ShipmentRich("S-1", new CustomerRich("alice", Set.of("vip", "newsletter")));
      final var dst = mapper.forward(src);
      assertEquals("S-1", dst.code());
      assertEquals("alice", dst.customer().name());
    }

    @Test
    @DisplayName("two-arg drop backward leaves the dropped nested slot null")
    void twoArgDropBackwardLeavesNull() {
      final var mapper = Telescope.mapper(
        ShipmentRich.class,
        ShipmentPartner.class,
        drop(CustomerRich::tags, CustomerPartner.class)
      );
      final var dst = new ShipmentPartner("S-1", new CustomerPartner("alice"));
      final var rebuilt = mapper.backward(dst);
      assertEquals("S-1", rebuilt.code());
      assertEquals("alice", rebuilt.customer().name());
      assertNull(rebuilt.customer().tags());
    }

    @Test
    @DisplayName("one-arg drop on a nested-source class binds to top target — doesn't reach the nested pair")
    void oneArgDropOnNestedSourceDoesNotReachNestedPair() {
      // Without the explicit nested target, the top-level mapper still rejects the unmapped source
      // because (CustomerRich, CustomerPartner) is the recursion's pair, not (CustomerRich,
      // top-target).
      final var ex = assertThrows(IllegalStateException.class, () ->
        Telescope.mapper(ShipmentRich.class, ShipmentPartner.class, drop(CustomerRich::tags))
      );
      assertTrue(ex.getMessage().contains("tags"), ex.getMessage());
    }
  }
}
