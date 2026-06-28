package io.github.eschizoid.telescope.examples;

import static io.github.eschizoid.telescope.mapping.MapExtractStep.extract;

import io.github.eschizoid.telescope.Telescope;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Map → POJO with {@code Telescope.fromMap(...)} — the shape a GraphQL server hits.
 *
 * <p>In graphql-java every argument arrives as a {@code String}, primitive, enum name, or {@code
 * Map<String, Object>} (input objects nest as maps). The usual fix is Jackson's {@code
 * convertValue}, which works but is reflection-heavy and a chore to register for GraalVM native
 * image. {@code fromMap} builds a reusable converter from typed accessor rows: each row names a map
 * key, a target field via a method reference (compile-checked, not a string on the target side),
 * and a per-key converter that coerces the raw value. An absent key passes {@code null} to its
 * converter (which can null-default an optional field); a target component with no row at all takes
 * the field's JLS default.
 *
 * <p>This is the <em>runtime</em> tier: {@code fromMap} binds the accessors through {@code
 * LambdaMetafactory}, so it is well clear of naive reflection but still resolves at runtime. The
 * generated-code tier (a compile-time processor emitting the same converter with direct field
 * writes, no reflection metadata) is the native-image-friendly follow-up this demo motivates.
 */
final class GraphQlMapToPojoDemo {

  private GraphQlMapToPojoDemo() {}

  enum Role {
    ADMIN,
    USER,
  }

  record Address(String city, String zip) {}

  record User(String name, String email, int age, Role role, Address address) {}

  static void main() {
    run();
  }

  static void run() {
    // The nested input-object converter is itself a fromMap mapper — composition, built once.
    final var addressMapper = Telescope.fromMap(
      Address.class,
      extract("city", Address::city, Object::toString),
      extract("zip", Address::zip, Object::toString)
    );

    // Built once, reused for every request. Keys come from the GraphQL argument map; the target
    // side is method-reference typed. Converters coerce the raw Object (String/Integer/Map/...) to
    // the field's type — exactly where GraphQL's "everything is a String/primitive/enum/Map" lands.
    final var userMapper = Telescope.fromMap(
      User.class,
      extract("name", User::name, Object::toString),
      extract("email", User::email, Object::toString),
      extract("age", User::age, v -> v == null ? 0 : Integer.parseInt(v.toString())), // optional Int → int
      extract("role", User::role, v -> Role.valueOf(v.toString())), // GraphQL enum name → enum
      extract("address", User::address, v -> addressMapper.forward(asMap(v))) // nested input object
    );

    // What graphql-java would hand you for `createUser(input: { ... })`.
    final var input = new LinkedHashMap<String, Object>();
    input.put("name", "Alice");
    input.put("email", "alice@example.com");
    input.put("age", "30"); // arrives as a String from the query
    input.put("role", "ADMIN"); // enum name as a String
    input.put("address", Map.of("city", "New York", "zip", "10001")); // nested input object

    final var user = userMapper.forward(input);
    System.out.println("[fromMap] full input        : " + user);
    require(user.equals(new User("Alice", "alice@example.com", 30, Role.ADMIN, new Address("New York", "10001"))));

    // Optional GraphQL input field: 'age' is absent here, so its row's converter receives null and
    // coerces it to 0 — no throw. (Components with no extract row at all take the JLS default too.)
    final var partial = new LinkedHashMap<String, Object>();
    partial.put("name", "Bob");
    partial.put("email", "bob@example.com");
    partial.put("role", "USER");
    partial.put("address", Map.of("city", "Austin", "zip", "73301"));
    final var bob = userMapper.forward(partial);
    System.out.println("[fromMap] missing 'age' → 0 : " + bob);
    require(bob.age() == 0 && bob.name().equals("Bob"));

    System.out.println("[fromMap] OK — runtime Map→POJO works; codegen tier would make this native-image-friendly.");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMap(final Object raw) {
    return (Map<String, Object>) raw;
  }

  private static void require(final boolean condition) {
    if (!condition) throw new AssertionError("GraphQlMapToPojoDemo assertion failed");
  }
}
