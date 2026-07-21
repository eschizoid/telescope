package io.github.eschizoid.telescope.examples.graphql.server;

import com.sun.net.httpserver.HttpServer;
import graphql.GraphQL;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import io.github.eschizoid.telescope.examples.graphql.model.User;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Function;

/**
 * Reusable graphql-java + JDK {@link HttpServer} harness. The {@code createUser} resolver is
 * parameterized on the {@code Map<String, Object> -> User} converter, so the only difference
 * between the runtime and generated tiers is which converter is passed in. {@link #serveOnce}
 * performs the whole round-trip and returns the response body — verification is the caller's job
 * (see the tests), so the entry points stay assertion-free demos.
 */
public final class GraphQlServer {

  private GraphQlServer() {}

  /** The canonical createUser mutation the demos and tests fire. */
  public static final String CREATE_USER_MUTATION = """
    mutation {
      createUser(input: {
        name: "Alice", email: "alice@example.com", age: 30, role: ADMIN,
        address: { city: "New York", zip: "10001" }
      }) { name email age role address { city zip } }
    }
    """;

  private static final String SDL = """
    type Query { ping: String }
    type Mutation { createUser(input: UserInput!): User }
    input UserInput { name: String, email: String, age: Int, role: Role, address: AddressInput }
    input AddressInput { city: String, zip: String }
    enum Role { ADMIN USER }
    type User { name: String, email: String, age: Int, role: Role, address: Address }
    type Address { city: String, zip: String }
    """;

  /**
   * Start a server with the given resolver, run one query against it, stop, and return the response
   * body.
   */
  public static String serveOnce(final Function<Map<String, Object>, User> userFromMap, final String query)
    throws Exception {
    final var graphql = build(userFromMap);
    final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/graphql", exchange -> {
      try (final InputStream in = exchange.getRequestBody()) {
        final var gql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        final var body = String.valueOf(graphql.execute(gql).toSpecification()).getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
      } finally {
        exchange.close();
      }
    });
    server.start();
    try {
      final var port = server.getAddress().getPort();
      return HttpClient.newHttpClient()
        .send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/graphql"))
            .header("Content-Type", "application/graphql")
            .POST(HttpRequest.BodyPublishers.ofString(query))
            .build(),
          HttpResponse.BodyHandlers.ofString()
        )
        .body();
    } finally {
      server.stop(0);
    }
  }

  private static GraphQL build(final Function<Map<String, Object>, User> userFromMap) {
    final var wiring = RuntimeWiring.newRuntimeWiring()
      .type("Mutation", b -> b.dataFetcher("createUser", env -> userFromMap.apply(asMap(env.getArgument("input")))))
      .build();
    final var registry = new SchemaParser().parse(SDL);
    final var schema = new SchemaGenerator().makeExecutableSchema(registry, wiring);
    return GraphQL.newGraphQL(schema).build();
  }

  /** Null-safe erased-Map cast, shared by the harness and the runtime tier's nested converter. */
  @SuppressWarnings("unchecked")
  static Map<String, Object> asMap(final Object raw) {
    return raw instanceof Map<?, ?> ? (Map<String, Object>) raw : null;
  }
}
