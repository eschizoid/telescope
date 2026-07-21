package io.github.eschizoid.telescope.examples.graphql.server;

import io.github.eschizoid.telescope.examples.graphql.model.User;
import io.github.eschizoid.telescope.examples.graphql.model.UserFromMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Generated tier: the {@code createUser} resolver converts the argument Map with the GENERATED
 * {@code UserFromMap} (emitted from {@code @FromMap} on {@link User}). Reflection-free by
 * construction — no {@code SerializedLambda}, no reachability config, nothing to register. The
 * native image's entry point is {@link NativeVerify}, which exercises this converter as one of its
 * eight capabilities.
 */
public final class GeneratedFromMapServer {

  private GeneratedFromMapServer() {}

  /**
   * The generated, reflection-free Map→User converter, shared by the demo entry point and the
   * tests.
   */
  public static Function<Map<String, Object>, User> converter() {
    return UserFromMap::fromMap;
  }

  public static void main(final String[] args) throws Exception {
    System.out.println("[generated] " + GraphQlServer.serveOnce(converter(), GraphQlServer.CREATE_USER_MUTATION));
  }
}
