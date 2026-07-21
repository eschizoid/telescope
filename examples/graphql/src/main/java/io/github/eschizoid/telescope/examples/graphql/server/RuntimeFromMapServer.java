package io.github.eschizoid.telescope.examples.graphql.server;

import static io.github.eschizoid.telescope.mapping.MapExtractStep.extract;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.conversion.ForwardMapper;
import io.github.eschizoid.telescope.examples.graphql.model.Address;
import io.github.eschizoid.telescope.examples.graphql.model.Role;
import io.github.eschizoid.telescope.examples.graphql.model.User;
import java.util.Map;
import java.util.function.Function;

/**
 * Runtime tier: the {@code createUser} resolver converts the argument Map with the RUNTIME {@code
 * Telescope.fromMap(...)} (it recovers field names from method references via {@code
 * SerializedLambda}). Runs cleanly on the JVM. Under native-image the {@code SerializedLambda}
 * decode needs this class registered as a lambda-capturing type in {@code
 * serialization-config.json} — this class is deliberately left unregistered so the unconfigured
 * failure mode stays reproducible; {@link NativeVerify} is the registered call site whose
 * method-reference navigation proves that same decode natively. See the module README.
 */
public final class RuntimeFromMapServer {

  private RuntimeFromMapServer() {}

  /** The runtime Map→User converter, shared by the demo entry point and the tests. */
  public static Function<Map<String, Object>, User> converter() {
    final ForwardMapper<Map<String, Object>, Address> addressMapper = Telescope.fromMap(
      Address.class,
      extract("city", Address::city, Object::toString),
      extract("zip", Address::zip, Object::toString)
    );
    final ForwardMapper<Map<String, Object>, User> userMapper = Telescope.fromMap(
      User.class,
      extract("name", User::name, Object::toString),
      extract("email", User::email, Object::toString),
      extract("age", User::age, v -> v == null ? 0 : Integer.parseInt(v.toString())),
      extract("role", User::role, v -> v instanceof Role r ? r : Role.valueOf(v.toString())),
      extract("address", User::address, v -> addressMapper.forward(GraphQlServer.asMap(v)))
    );
    return userMapper::forward;
  }

  public static void main(final String[] args) throws Exception {
    System.out.println("[runtime] " + GraphQlServer.serveOnce(converter(), GraphQlServer.CREATE_USER_MUTATION));
  }
}
