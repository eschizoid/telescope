package io.github.eschizoid.telescope.benchmarks;

import io.github.eschizoid.telescope.Telescope;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/**
 * JMH micro-benchmarks for Telescope's deep-copy DSL.
 *
 * <p>Compares the cost of the three update paths against each other:
 *
 * <ul>
 *   <li>Reflection-based {@code .field(User::x)} field access (the convenient default).
 *   <li>The reflection-free {@code Telescope.lens(getter, setter)} constant (what the
 *       {@code @Focus} processor emits).
 *   <li>A hand-written record copy baseline.
 * </ul>
 *
 * <p>It also measures record&rarr;record conversion ({@code map}) and the POJO optics: native deep
 * navigation ({@code ofBean}), POJO&harr;POJO conversion ({@code mapBean}), and the
 * POJO&rarr;record bridge ({@code fromBean}) — each against a hand-written baseline.
 *
 * <p>The record benchmarks walk a small tree {@code Company -> Department -> Address} and update
 * the deeply-nested {@code Address::city}; the POJO benchmarks walk an identical mutable-bean
 * mirror.
 *
 * <p>For measured numbers and how to read them, see {@code benchmarks/README.md}.
 *
 * <p>Run with:
 *
 * <pre>{@code
 * ./gradlew :benchmarks:jmh
 * }</pre>
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class TelescopeBenchmark {

  // ---- Realistic record tree ------------------------------------------------------------------

  public record Address(String city, String zip) {}

  public record Department(String name, int headcount, Address address) {}

  public record Company(String name, Department department) {}

  // Source/target records for the record-to-record mapping benchmark.
  public record UserEntity(String id, String email, String name) {}

  public record UserDto(String id, String email, String fullName) {}

  // ---- Mutable POJO mirror of the record tree (JavaBeans: no-arg ctor + getters + setters) -----

  public static final class AddressBean {

    private String city;
    private String zip;

    public String getCity() {
      return city;
    }

    public String getZip() {
      return zip;
    }

    public void setCity(final String city) {
      this.city = city;
    }

    public void setZip(final String zip) {
      this.zip = zip;
    }
  }

  public static final class DepartmentBean {

    private String name;
    private int headcount;
    private AddressBean address;

    public String getName() {
      return name;
    }

    public int getHeadcount() {
      return headcount;
    }

    public AddressBean getAddress() {
      return address;
    }

    public void setName(final String name) {
      this.name = name;
    }

    public void setHeadcount(final int headcount) {
      this.headcount = headcount;
    }

    public void setAddress(final AddressBean address) {
      this.address = address;
    }
  }

  public static final class CompanyBean {

    private String name;
    private DepartmentBean department;

    public String getName() {
      return name;
    }

    public DepartmentBean getDepartment() {
      return department;
    }

    public void setName(final String name) {
      this.name = name;
    }

    public void setDepartment(final DepartmentBean department) {
      this.department = department;
    }
  }

  // Source/target POJOs for the POJO<->POJO mapBean benchmark, and a POJO for the fromBean bridge.
  public static final class UserBeanA {

    private String id;
    private String email;
    private String name;

    public String getId() {
      return id;
    }

    public String getEmail() {
      return email;
    }

    public String getName() {
      return name;
    }

    public void setId(final String id) {
      this.id = id;
    }

    public void setEmail(final String email) {
      this.email = email;
    }

    public void setName(final String name) {
      this.name = name;
    }
  }

  public static final class UserBeanB {

    private String id;
    private String email;
    private String name;

    public String getId() {
      return id;
    }

    public String getEmail() {
      return email;
    }

    public String getName() {
      return name;
    }

    public void setId(final String id) {
      this.id = id;
    }

    public void setEmail(final String email) {
      this.email = email;
    }

    public void setName(final String name) {
      this.name = name;
    }
  }

  // ---- Fixtures -------------------------------------------------------------------------------

  private Company company;
  private UserEntity entity;

  // (a) Reflection path: Telescope.of(...).field(...).field(...).field(...) built once, reused.
  private Telescope<Company, String> reflectionCity;

  // (c) Reflection-free path: hand-rolled Telescope.lens constants composed via .then(...).
  //     These stand in for the constants the @Focus annotation processor generates.
  private Telescope<Company, String> lensCity;

  // (d) Record-to-record forward conversion built once, reused.
  private Telescope<UserEntity, UserDto> userMapper;

  // POJO optics fixtures.
  private CompanyBean companyBean;
  private UserBeanA userBeanA;
  private UserBeanA userBeanForBridge;
  private BenchUserA benchUserA;

  // (e) native POJO deep navigation (rebuild-via-strategy at each level).
  private Telescope<CompanyBean, String> ofBeanCity;

  // (f) POJO<->POJO conversion.
  private Telescope<UserBeanA, UserBeanB> mapBeanConv;

  // (g) POJO->record bridge.
  private Telescope<UserBeanA, UserEntity> fromBeanConv;

  @Setup
  public void setup() {
    company = new Company("Acme", new Department("Platform", 12, new Address("nyc", "10001")));
    entity = new UserEntity("u1", "alice@example.com", "Alice");

    // (a) reflection: method-reference field navigation, resolved via SerializedLambda + record
    // reflection.
    reflectionCity = Telescope.of(Company.class)
      .field(Company::department)
      .field(Department::address)
      .field(Address::city);

    // (c) reflection-free: explicit getter/setter pairs, no reflection on the hot path.
    final Telescope<Company, Department> companyDepartment = Telescope.lens(Company::department, (c, d) ->
      new Company(c.name(), d)
    );
    final Telescope<Department, Address> departmentAddress = Telescope.lens(Department::address, (d, a) ->
      new Department(d.name(), d.headcount(), a)
    );
    final Telescope<Address, String> addressCity = Telescope.lens(Address::city, (a, city) ->
      new Address(city, a.zip())
    );
    lensCity = companyDepartment.then(departmentAddress).then(addressCity);

    // (d) reflection-based record-to-record mapper (renames name -> fullName across the boundary).
    userMapper = Telescope.map(UserEntity.class)
      .to(UserDto.class)
      .field(UserEntity::id)
      .to(UserDto::id)
      .field(UserEntity::email)
      .to(UserDto::email)
      .field(UserEntity::name)
      .to(UserDto::fullName)
      .build();

    // POJO mirror of the record tree.
    final var addr = new AddressBean();
    addr.setCity("nyc");
    addr.setZip("10001");
    final var dept = new DepartmentBean();
    dept.setName("Platform");
    dept.setHeadcount(12);
    dept.setAddress(addr);
    companyBean = new CompanyBean();
    companyBean.setName("Acme");
    companyBean.setDepartment(dept);

    userBeanA = new UserBeanA();
    userBeanA.setId("u1");
    userBeanA.setEmail("alice@example.com");
    userBeanA.setName("Alice");
    userBeanForBridge = userBeanA;

    benchUserA = new BenchUserA();
    benchUserA.setId("u1");
    benchUserA.setEmail("alice@example.com");
    benchUserA.setName("Alice");

    // (e) native POJO deep navigation, built once and reused.
    ofBeanCity = Telescope.ofBean(CompanyBean.class)
      .field(CompanyBean::getDepartment)
      .field(DepartmentBean::getAddress)
      .field(AddressBean::getCity);

    // (f) POJO<->POJO conversion.
    mapBeanConv = Telescope.mapBean(UserBeanA.class).to(UserBeanB.class).build();

    // (g) POJO->record bridge (forward read uses getters; viaFields picks the reverse strategy).
    fromBeanConv = Telescope.fromBean(UserBeanA.class).to(UserEntity.class).viaFields();
  }

  // ---- (a) deep-field update via reflection ----------------------------------------------------

  @Benchmark
  public void reflectionFieldUpdate(final Blackhole bh) {
    bh.consume(reflectionCity.update(company, String::toUpperCase));
  }

  // ---- (b) hand-rolled record copy baseline ----------------------------------------------------

  @Benchmark
  public void handRolledCopyUpdate(final Blackhole bh) {
    final var d = company.department();
    final var a = d.address();
    final var updated = new Company(
      company.name(),
      new Department(d.name(), d.headcount(), new Address(a.city().toUpperCase(), a.zip()))
    );
    bh.consume(updated);
  }

  // ---- (c) deep-field update via reflection-free Telescope.lens constants ----------------------

  @Benchmark
  public void lensConstantUpdate(final Blackhole bh) {
    bh.consume(lensCity.update(company, String::toUpperCase));
  }

  // ---- (d) forward record -> record conversion via Telescope.map(...).build() ------------------

  @Benchmark
  public void mapperForwardRead(final Blackhole bh) {
    bh.consume(userMapper.read(entity));
  }

  // ---- (e) native POJO deep-field update via ofBean (rebuild-via-strategy at each level) --------

  @Benchmark
  public void ofBeanFieldUpdate(final Blackhole bh) {
    bh.consume(ofBeanCity.update(companyBean, String::toUpperCase));
  }

  // ---- hand-rolled mutable-POJO copy baseline (for the ofBean comparison) -----------------------

  @Benchmark
  public void handRolledBeanCopyUpdate(final Blackhole bh) {
    final var src = companyBean.getDepartment().getAddress();
    final var addr = new AddressBean();
    addr.setCity(src.getCity().toUpperCase());
    addr.setZip(src.getZip());
    final var dept = new DepartmentBean();
    dept.setName(companyBean.getDepartment().getName());
    dept.setHeadcount(companyBean.getDepartment().getHeadcount());
    dept.setAddress(addr);
    final var out = new CompanyBean();
    out.setName(companyBean.getName());
    out.setDepartment(dept);
    bh.consume(out);
  }

  // ---- (f) POJO -> POJO conversion via mapBean --------------------------------------------------

  @Benchmark
  public void mapBeanForwardRead(final Blackhole bh) {
    bh.consume(mapBeanConv.read(userBeanA));
  }

  // ---- (g) POJO -> record conversion via fromBean -----------------------------------------------

  @Benchmark
  public void fromBeanForwardRead(final Blackhole bh) {
    bh.consume(fromBeanConv.read(userBeanForBridge));
  }

  // ---- (h) POJO -> POJO conversion via the generated @Bridge constant (reflection-free) ---------
  // The compile-time counterpart to (f) mapBeanForwardRead: same conversion, no runtime reflection.

  @Benchmark
  public void bridgeForwardRead(final Blackhole bh) {
    bh.consume(BenchUserABridge.BRIDGE.read(benchUserA));
  }
}
