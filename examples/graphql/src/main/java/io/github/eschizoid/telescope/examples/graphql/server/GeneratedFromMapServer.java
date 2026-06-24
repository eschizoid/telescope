package io.github.eschizoid.telescope.examples.graphql.server;

import io.github.eschizoid.telescope.examples.graphql.model.User;
import io.github.eschizoid.telescope.examples.graphql.model.UserFromMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Generated tier: the {@code createUser} resolver converts the argument Map with the GENERATED
 * {@code UserFromMap} (emitted from {@code @FromMap} on {@link User}). Reflection-free, so this is
 * the entry point the GraalVM native image builds — {@code --no-fallback}, no reachability config.
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
