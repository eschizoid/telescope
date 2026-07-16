package io.github.eschizoid.telescope.nativesmoke;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.conversion.Mapper;
import java.util.ArrayList;

/**
 * A self-contained GraalVM native-image smoke test for telescope's reflection-free runtime
 * substrate. Every capability below rides the cached {@code LambdaMetafactory}-built {@code
 * Function} / {@code Supplier} / {@code BiConsumer} readers and writers plus the {@code
 * SerializedLambda} field-name decode that {@code .field(Record::accessor)} relies on — the exact
 * machinery whose survival under {@code --initialize-at-build-time} + the native-image closed-world
 * assumption is unverified.
 *
 * <p>Running this class <em>is</em> the test: each capability prints {@code PASS} / {@code FAIL}
 * and a mismatch (or thrown exception) sets a non-zero exit code. When {@code native-image} bakes
 * and runs this binary, a green run proves the substrate; a red run (or a build-time failure) is
 * the real finding.
 *
 * <p>The capabilities exercised, mapped to the substrate mechanic each stresses:
 *
 * <ul>
 *   <li><b>record field update</b> — {@code Telescope.of(Record).field(Record::x).update(...)}:
 *       {@code SerializedLambda} decode of the method reference + LMF record reader + the cached
 *       canonical-constructor {@code MethodHandle} rebuild.
 *   <li><b>record → record mapper</b> — {@code Telescope.mapper(A, B).forward(a)}: LMF readers on
 *       the source, canonical-constructor rebuild on the target.
 *   <li><b>bean → bean mapper</b> — {@code Telescope.mapper(A, B).forward(a)} over POJOs: LMF
 *       getter readers + the no-arg-ctor + LMF setter writer path in {@code Beans}.
 *   <li><b>LMF read path (record + bean)</b> — {@code .field(...).read(source)}: the pure
 *       LMF-dispatched read (the {@code Records.read} / {@code Beans.readProperty} equivalent
 *       reached through the public surface, since {@code :internal} is JPMS-sealed to {@code
 *       :core}).
 *   <li><b>codegen {@code @Bridge}</b> — the generated {@code SmokeBeanABridge.BRIDGE} constant, a
 *       {@code Telescope<SmokeBeanA, SmokeBeanB>} emitted at compile time. This path uses NO
 *       runtime reflection (typed method calls only) and so is the control: it should always
 *       survive native image. It doubles as a check that the codegen navigator classes are
 *       reachable.
 * </ul>
 */
public final class NativeSmokeMain {

  private NativeSmokeMain() {}

  /** A small record graph for the record-side capabilities. */
  public record Address(String city, String zip) {}

  /** Top-level record with a nested record and a primitive, to stress boxing on the LMF path. */
  public record User(String name, int age, Address address) {}

  /** Record mapper target — same field names / types as {@link User} for same-name deep mapping. */
  public record UserDto(String name, int age, Address address) {}

  public static void main(final String[] args) {
    final var results = new ArrayList<Result>();

    results.add(
      guard("record field update (SerializedLambda + LMF reader + ctor MH)", NativeSmokeMain::recordFieldUpdate)
    );
    results.add(
      guard("record → record mapper.forward (LMF readers + canonical-ctor rebuild)", NativeSmokeMain::recordMapper)
    );
    results.add(
      guard("bean → bean mapper.forward (LMF getters + no-arg ctor + LMF setters)", NativeSmokeMain::beanMapper)
    );
    results.add(guard("LMF record read path (.field(...).read())", NativeSmokeMain::recordReadPath));
    results.add(guard("LMF bean read path (.field(...).read())", NativeSmokeMain::beanReadPath));
    results.add(guard("codegen @Bridge constant (SmokeBeanABridge.BRIDGE.read())", NativeSmokeMain::bridgeConstant));

    System.out.println();
    System.out.println("=== telescope native-image smoke test ===");
    var failures = 0;
    for (final var r : results) {
      final var detail = r.detail() == null ? "" : "  — " + r.detail();
      System.out.println((r.passed() ? "PASS  " : "FAIL  ") + r.name() + detail);
      if (!r.passed()) failures++;
    }
    System.out.println();
    if (failures == 0) {
      System.out.println("ALL " + results.size() + " CAPABILITIES PASSED on this runtime (" + runtimeLabel() + ").");
    } else {
      System.out.println(
        failures +
          " of " +
          results.size() +
          " CAPABILITIES FAILED — the substrate needs native-image config (see the failing" +
          " lines)."
      );
    }
    System.exit(failures == 0 ? 0 : 1);
  }

  // (a) record field update through the full method-reference decode + rebuild path.
  private static void recordFieldUpdate() {
    final var before = new User("ada", 36, new Address("london", "NW1"));
    final var after = Telescope.of(User.class).field(User::name).update(before, String::toUpperCase);
    expect("ADA".equals(after.name()), "expected name=ADA, got " + after.name());
    // Nested navigation + a primitive-carrying rebuild: the age (int) must survive the copy
    // unboxed.
    final var deeper = Telescope.of(User.class)
      .field(User::address)
      .field(Address::city)
      .update(before, String::toUpperCase);
    expect(
      "LONDON".equals(deeper.address().city()) && deeper.age() == 36,
      "nested update or primitive carry-over failed: " + deeper
    );
  }

  // (b) record → record deep mapper (same-name auto mapping over the whole graph).
  private static void recordMapper() {
    final Mapper<User, UserDto> mapper = Telescope.mapper(User.class, UserDto.class);
    final var dto = mapper.forward(new User("grace", 45, new Address("nyc", "10001")));
    expect(
      "grace".equals(dto.name()) && dto.age() == 45 && "nyc".equals(dto.address().city()),
      "record mapper.forward mismatch: " + dto
    );
  }

  // (c) bean → bean deep mapper: exercises Beans' LMF getter readers + no-arg-ctor Supplier +
  // setters.
  private static void beanMapper() {
    final var src = new SmokeBeanA();
    src.setId("id-1");
    src.setEmail("Alan@Example.com");
    src.setName("Alan");
    final Mapper<SmokeBeanA, SmokeBeanB> mapper = Telescope.mapper(SmokeBeanA.class, SmokeBeanB.class);
    final var out = mapper.forward(src);
    expect(
      "id-1".equals(out.getId()) && "Alan@Example.com".equals(out.getEmail()) && "Alan".equals(out.getName()),
      "bean mapper.forward mismatch: id=" + out.getId() + " email=" + out.getEmail() + " name=" + out.getName()
    );
  }

  // (d) LMF record read path — .read() dispatches the cached LMF record reader (Records.read
  // equivalent).
  private static void recordReadPath() {
    final var user = new User("linus", 54, new Address("helsinki", "00100"));
    final String city = Telescope.of(User.class).field(User::address).field(Address::city).read(user);
    final int age = Telescope.of(User.class).field(User::age).read(user);
    expect("helsinki".equals(city) && age == 54, "record read path mismatch: city=" + city + " age=" + age);
  }

  // LMF bean read path — .read() dispatches the cached LMF getter (Beans.readProperty equivalent).
  private static void beanReadPath() {
    final var bean = new SmokeBeanA();
    bean.setId("id-2");
    bean.setEmail("Barbara@Example.com");
    bean.setName("Barbara");
    final String email = Telescope.ofBean(SmokeBeanA.class).field(SmokeBeanA::getEmail).read(bean);
    expect("Barbara@Example.com".equals(email), "bean read path mismatch: " + email);
  }

  // (e) codegen @Bridge constant — pure typed method calls, no runtime reflection. Control path.
  private static void bridgeConstant() {
    final var a = new SmokeBeanA();
    a.setId("id-3");
    a.setEmail("Katherine@Example.com");
    a.setName("Katherine");
    // SmokeBeanABridge is emitted by :codegen from @Bridge(SmokeBeanB.class) on SmokeBeanA.
    // BRIDGE is a Telescope<SmokeBeanA, SmokeBeanB>; .read(a) runs the forward conversion.
    final var b = SmokeBeanABridge.BRIDGE.read(a);
    expect(
      "id-3".equals(b.getId()) && "Katherine@Example.com".equals(b.getEmail()) && "Katherine".equals(b.getName()),
      "bridge constant mismatch: id=" + b.getId() + " email=" + b.getEmail() + " name=" + b.getName()
    );
  }

  // "native-image" when running as a compiled binary (org.graalvm.nativeimage.imagecode is set),
  // otherwise "JVM". Lets the same main honestly say which runtime just proved the substrate — a
  // green JVM run only validates the harness; a green native-image run is the real verdict.
  private static String runtimeLabel() {
    return System.getProperty("org.graalvm.nativeimage.imagecode") != null ? "native-image" : "JVM";
  }

  private static void expect(final boolean condition, final String message) {
    if (!condition) throw new AssertionError(message);
  }

  private static Result guard(final String name, final Runnable capability) {
    try {
      capability.run();
      return new Result(name, true, null);
    } catch (final Throwable t) {
      // Print the full stack immediately so a native-image run surfaces the failing mechanic (an
      // LMF build failure, a missing reflection registration, a SerializedLambda decode gap).
      System.err.println("[FAIL] " + name);
      t.printStackTrace();
      return new Result(name, false, t.getClass().getSimpleName() + ": " + t.getMessage());
    }
  }

  private record Result(String name, boolean passed, String detail) {}
}
