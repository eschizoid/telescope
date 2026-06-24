package io.github.eschizoid.telescope.examples.graphql.server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the Map→POJO conversion end-to-end through the GraphQL server, for both tiers: the
 * runtime {@code Telescope.fromMap} and the generated {@code @FromMap} converter. The server fires
 * a real {@code createUser} mutation over HTTP and the response (the result Map's toString) is
 * asserted field-by-field — proving HTTP → graphql-java → Map→POJO → record → response for each
 * converter.
 */
class GraphQlServerTest {

  @Test
  @DisplayName("runtime Telescope.fromMap converts the GraphQL argument Map into a User")
  void runtimeTierConverts() throws Exception {
    assertConverted(GraphQlServer.serveOnce(RuntimeFromMapServer.converter(), GraphQlServer.CREATE_USER_MUTATION));
  }

  @Test
  @DisplayName("generated @FromMap converter converts the GraphQL argument Map into a User")
  void generatedTierConverts() throws Exception {
    assertConverted(GraphQlServer.serveOnce(GeneratedFromMapServer.converter(), GraphQlServer.CREATE_USER_MUTATION));
  }

  private static void assertConverted(final String response) {
    assertFalse(response.contains("errors="), () -> "GraphQL errors in response: " + response);
    assertTrue(response.contains("name=Alice"), response);
    assertTrue(response.contains("age=30"), () -> "GraphQL Int not coerced to int: " + response);
    assertTrue(response.contains("role=ADMIN"), () -> "enum not coerced: " + response);
    assertTrue(
      response.contains("city=New York") && response.contains("zip=10001"),
      () -> "nested input object not converted: " + response
    );
  }
}
