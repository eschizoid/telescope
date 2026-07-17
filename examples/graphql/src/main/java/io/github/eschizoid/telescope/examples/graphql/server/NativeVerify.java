package io.github.eschizoid.telescope.examples.graphql.server;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.conversion.Mapper;
import io.github.eschizoid.telescope.examples.graphql.model.Account;
import io.github.eschizoid.telescope.examples.graphql.model.AccountBridge;
import io.github.eschizoid.telescope.examples.graphql.model.AccountEntity;
import io.github.eschizoid.telescope.examples.graphql.model.Address;
import io.github.eschizoid.telescope.examples.graphql.model.Role;
import io.github.eschizoid.telescope.examples.graphql.model.User;
import io.github.eschizoid.telescope.examples.graphql.model.UserFromMap;
import io.github.eschizoid.telescope.examples.graphql.model.UserView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * The native-image verifier for this example — and the entry point the {@code graalvmNative} build
 * bakes. It exercises telescope's full runtime + codegen surface over the example's own domain
 * models and asserts the converted values, so a compiled binary that produces wrong output (or
 * fails to build) is a red CI run, not a silent pass.
 *
 * <p>Each capability maps to a substrate mechanic whose survival under {@code
 * --initialize-at-build-time} + native-image's closed-world assumption is what this verifies:
 *
 * <ul>
 *   <li><b>record field update / read</b> — {@code Telescope.of(User).field(User::x)...}: {@code
 *       SerializedLambda} decode of the method reference + the cached LMF record reader + the
 *       canonical-constructor {@code MethodHandle} rebuild.
 *   <li><b>bean-getter read</b> — {@code Telescope.ofBean(AccountEntity).field(getter).read(e)}:
 *       the cached LMF getter {@code Function} dispatch, exercised on its own so a getter-LMF
 *       regression surfaces as its own line rather than hiding inside the bean mapper's write path.
 *   <li><b>runtime record→record mapper</b> — {@code Telescope.mapper(User, UserView).forward(u)}:
 *       LMF readers on the source, canonical-constructor rebuild on the target, carrying the nested
 *       {@link Address} record through unchanged and the {@link Role} enum through by identity.
 *   <li><b>runtime record→bean mapper</b> — {@code Telescope.mapper(Account, AccountEntity)}: the
 *       Beans LMF no-arg-constructor {@code Supplier} + LMF setter {@code BiConsumer} write path.
 *   <li><b>generated {@code @FromMap}</b> — {@code UserFromMap.fromMap(map)}: the reflection-free
 *       codegen control — no LMF, no {@code SerializedLambda}, just typed method calls.
 *   <li><b>generated {@code @Bridge}</b> — {@code AccountBridge.BRIDGE.read(a)}: the codegen bridge
 *       constant, a {@code Telescope<Account, AccountEntity>} that lands in the build-time image
 *       heap — which is why the telescope and generated-model classes take {@code
 *       --initialize-at-build-time} (see {@code build.gradle.kts}).
 * </ul>
 *
 * <p>A JVM run only validates the harness; a green native-image run is the real verdict, and {@link
 * #runtimeLabel()} says which one just passed.
 */
public final class NativeVerify {

  private NativeVerify() {}

  public static void main(final String[] args) {
    final var results = new ArrayList<Result>();
    results.add(
      guard("record field update (SerializedLambda + LMF reader + ctor MH)", NativeVerify::recordFieldUpdate)
    );
    results.add(guard("record read path (.field(...).read())", NativeVerify::recordReadPath));
    results.add(
      guard("bean getter read (ofBean.field(getter).read(), LMF getter Function)", NativeVerify::beanReadPath)
    );
    results.add(
      guard("runtime record → record mapper (LMF readers + ctor rebuild, nested + enum)", NativeVerify::recordMapper)
    );
    results.add(guard("runtime record → bean mapper (Beans LMF no-arg ctor + setters)", NativeVerify::beanMapper));
    results.add(guard("generated @FromMap converter (reflection-free codegen control)", NativeVerify::fromMap));
    results.add(guard("generated @Bridge constant (AccountBridge.BRIDGE.read())", NativeVerify::bridgeConstant));

    System.out.println();
    System.out.println("=== telescope native-image verification (graphql example) ===");
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
          " CAPABILITIES FAILED — the substrate needs native-image config" +
          " (see the failing lines)."
      );
    }
    System.exit(failures == 0 ? 0 : 1);
  }

  private static User sampleUser() {
    return new User("Ada", "ada@example.com", 36, Role.ADMIN, new Address("London", "NW1"));
  }

  // (a) record field update through the full method-reference decode + rebuild path.
  private static void recordFieldUpdate() {
    final var before = sampleUser();
    final var after = Telescope.of(User.class).field(User::name).update(before, String::toUpperCase);
    expect("ADA".equals(after.name()), "expected name=ADA, got " + after.name());
    final var deeper = Telescope.of(User.class)
      .field(User::address)
      .field(Address::city)
      .update(before, String::toUpperCase);
    expect(
      "LONDON".equals(deeper.address().city()) && deeper.age() == 36,
      "nested update or primitive carry-over failed: " + deeper
    );
  }

  // (b) LMF record read path — .read() dispatches the cached LMF record reader.
  private static void recordReadPath() {
    final var user = sampleUser();
    final String city = Telescope.of(User.class).field(User::address).field(Address::city).read(user);
    final int age = Telescope.of(User.class).field(User::age).read(user);
    expect("London".equals(city) && age == 36, "record read path mismatch: city=" + city + " age=" + age);
  }

  // (c) LMF bean-getter read — ofBean(...).field(getter).read() dispatches the cached LMF getter
  // Function. Read counterpart to the bean write path in (e); exercised on its own so a
  // getter-LMF regression surfaces as its own line rather than hiding inside the bean mapper.
  private static void beanReadPath() {
    final var entity = new AccountEntity();
    entity.setUsername("grace");
    final String username = Telescope.ofBean(AccountEntity.class).field(AccountEntity::getUsername).read(entity);
    expect("grace".equals(username), "bean getter read mismatch: " + username);
  }

  // (d) runtime record → record deep mapper: same-name inference over nested record + enum.
  private static void recordMapper() {
    final Mapper<User, UserView> mapper = Telescope.mapper(User.class, UserView.class);
    final var view = mapper.forward(sampleUser());
    expect(
      "Ada".equals(view.name()) &&
        view.age() == 36 &&
        view.role() == Role.ADMIN &&
        "London".equals(view.address().city()),
      "record mapper.forward mismatch: " + view
    );
  }

  // (e) runtime record → bean deep mapper: Beans LMF no-arg ctor Supplier + setter BiConsumer.
  private static void beanMapper() {
    final Mapper<Account, AccountEntity> mapper = Telescope.mapper(Account.class, AccountEntity.class);
    final var entity = mapper.forward(new Account("ada", "ada@example.com"));
    expect(
      "ada".equals(entity.getUsername()) && "ada@example.com".equals(entity.getEmail()),
      "bean mapper.forward mismatch: username=" + entity.getUsername() + " email=" + entity.getEmail()
    );
  }

  // (f) generated @FromMap converter — reflection-free codegen control; also exercises enum +
  // nested.
  private static void fromMap() {
    final Map<String, Object> address = new HashMap<>();
    address.put("city", "New York");
    address.put("zip", "10001");
    final Map<String, Object> map = new HashMap<>();
    map.put("name", "Alice");
    map.put("email", "alice@example.com");
    map.put("age", 30);
    map.put("role", "ADMIN");
    map.put("address", address);
    final var user = UserFromMap.fromMap(map);
    expect(
      "Alice".equals(user.name()) &&
        user.age() == 30 &&
        user.role() == Role.ADMIN &&
        "New York".equals(user.address().city()),
      "@FromMap conversion mismatch: " + user
    );
  }

  // (g) generated @Bridge constant — pure typed method calls, baked into the image heap.
  private static void bridgeConstant() {
    final var entity = AccountBridge.BRIDGE.read(new Account("grace", "grace@example.com"));
    expect(
      "grace".equals(entity.getUsername()) && "grace@example.com".equals(entity.getEmail()),
      "bridge constant mismatch: username=" + entity.getUsername() + " email=" + entity.getEmail()
    );
  }

  // "native-image" when running as a compiled binary, otherwise "JVM" — a green JVM run only
  // validates the harness; a green native-image run is the real verdict.
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
      System.err.println("[FAIL] " + name);
      t.printStackTrace();
      return new Result(name, false, t.getClass().getSimpleName() + ": " + t.getMessage());
    }
  }

  private record Result(String name, boolean passed, String detail) {}
}
