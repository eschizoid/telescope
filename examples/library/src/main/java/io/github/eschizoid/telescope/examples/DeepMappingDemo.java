package io.github.eschizoid.telescope.examples;

import static io.github.eschizoid.telescope.Mapping.to;
import static io.github.eschizoid.telescope.Mapping.via;
import static io.github.eschizoid.telescope.WriteHint.WriteStrategy.CONSTRUCTOR;
import static io.github.eschizoid.telescope.WriteHint.writeBean;

import io.github.eschizoid.telescope.Mapper;
import io.github.eschizoid.telescope.Telescope;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Exercises the deep recursive {@code Telescope.map(A, B, MapStep...)} and {@code
 * Telescope.mapper(A, B, MapStep...)} factories: same-name auto-recursion, the {@code to} / {@code
 * via} override rows, the {@code Mapper#patch} sparse overlay, and the {@code WriteHint.writeBean}
 * write-strategy hint for an immutable all-args-only POJO.
 */
final class DeepMappingDemo {

  private DeepMappingDemo() {}

  static void main() {
    run();
  }

  // ---------- same-name 5-level structure (with one rename) ----------

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
    Map<String, AddressEntity> officesByRegion
  ) {}

  record CompanyDto(String name, int since, List<DepartmentDto> departments, Map<String, AddressDto> officesByRegion) {}

  // ---------- typed-transform demo: int year ↔ String ----------

  record EventEntity(String name, int year) {}

  record EventDto(String name, String year) {}

  // ---------- via() override fixture: a "loud" Address mapper ----------

  record LoudUserDto(String fullName, String email, AddressDto address) {}

  // ---------- writeBean demo: immutable all-args-only POJO ----------

  static final class OrderPojo {

    private final String sku;
    private final int qty;

    public OrderPojo(final String sku, final int qty) {
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
      return o instanceof OrderPojo p && Objects.equals(p.sku, sku) && p.qty == qty;
    }

    @Override
    public int hashCode() {
      return Objects.hash(sku, qty);
    }

    @Override
    public String toString() {
      return "OrderPojo[sku=" + sku + ", qty=" + qty + "]";
    }
  }

  record OrderRecord(String sku, int qty) {}

  static void run() {
    deepMapWithRenames();
    mapperPatchSparseOverlay();
    typedTransformRow();
    viaPreBuiltNestedMapper();
    writeBeanHint();
  }

  // Telescope.map: 4-level nesting with two type-pair renames that apply wherever the pair recurs.
  private static void deepMapWithRenames() {
    final Telescope<CompanyEntity, CompanyDto> mapper = Telescope.map(
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
      Map.of("US", new AddressEntity("NYC", "10001"), "EU", new AddressEntity("LON", "EC1A"))
    );

    final CompanyDto dto = mapper.read(entity);
    System.out.println("[map] founded→since         : " + dto.since());
    System.out.println(
      "[map] name→fullName (deep)  : " + dto.departments().getFirst().teams().getFirst().users().getFirst().fullName()
    );
    System.out.println("[map] Optional<User> head   : " + dto.departments().getFirst().head().orElseThrow().fullName());
    System.out.println("[map] Map<String,Address>   : " + dto.officesByRegion().get("EU").city());
  }

  // Mapper.patch — sparse overlay of a partial DTO onto a source entity. Null fields in the
  // partial leave the source untouched.
  private static void mapperPatchSparseOverlay() {
    record FlatEntity(String id, String email, String name) {}
    record FlatDto(String id, String email, String name) {}

    final Mapper<FlatEntity, FlatDto> mapper = Telescope.mapper(FlatEntity.class, FlatDto.class);
    final var base = new FlatEntity("u1", "old@x", "alice");
    final var partial = new FlatDto(null, "new@x", null);

    final var out = mapper.patch(base, partial);
    System.out.println("[mapper.patch] base         : " + base);
    System.out.println("[mapper.patch] partial      : " + partial);
    System.out.println("[mapper.patch] result       : " + out);
  }

  // Typed transform: int year ↔ String year, both forward and backward supplied.
  private static void typedTransformRow() {
    final var mapper = Telescope.mapper(
      EventEntity.class,
      EventDto.class,
      to(EventEntity::year, EventDto::year, Object::toString, Integer::parseInt)
    );
    final var entity = new EventEntity("KubeCon", 2025);
    final var dto = mapper.read(entity);
    System.out.println("[map/typed-transform] fwd   : " + dto);
    System.out.println("[map/typed-transform] back  : " + mapper.backward(dto));
  }

  // via(): drop a pre-built nested mapper instead of letting recursion build one.
  private static void viaPreBuiltNestedMapper() {
    // A "loud" Address mapper that upper-cases the city in the forward direction.
    final Mapper<AddressEntity, AddressDto> loudAddress = Telescope.mapper(
      AddressEntity.class,
      AddressDto.class,
      to(AddressEntity::city, AddressDto::city, String::toUpperCase, String::toLowerCase),
      to(AddressEntity::zip, AddressDto::zip)
    );

    final var userMapper = Telescope.mapper(
      UserEntity.class,
      LoudUserDto.class,
      to(UserEntity::name, LoudUserDto::fullName),
      via(UserEntity::address, LoudUserDto::address, loudAddress)
    );

    final var entity = new UserEntity("alice", "a@x", new AddressEntity("nyc", "10001"));
    final var dto = userMapper.read(entity);
    System.out.println("[map/via] loud city forward : " + dto);
  }

  // WriteHint.writeBean — pin the construction strategy for a POJO that the auto-detect can't
  // (or shouldn't) handle. Used here for an immutable all-args-only POJO.
  private static void writeBeanHint() {
    final var mapper = Telescope.mapper(OrderRecord.class, OrderPojo.class, writeBean(OrderPojo.class, CONSTRUCTOR));
    final var src = new OrderRecord("SKU-1", 42);
    final var pojo = mapper.read(src);
    System.out.println("[map/writeBean(CTOR)] fwd   : " + pojo);
    System.out.println("[map/writeBean(CTOR)] back  : " + mapper.backward(pojo));
  }
}
