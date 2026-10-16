package io.github.eschizoid.telescope.spring.web.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.eschizoid.telescope.spring.web.TelescopePostMapping;
import io.github.eschizoid.telescope.spring.web.TelescopeRequest;
import io.github.eschizoid.telescope.spring.web.TelescopeRequestBody;
import io.github.eschizoid.telescope.spring.web.fixtures.request.CreateUserRequest;
import io.github.eschizoid.telescope.spring.web.fixtures.request.NewUser;
import io.github.eschizoid.telescope.spring.web.fixtures.request.NewUserView;
import io.github.eschizoid.telescope.spring.web.fixtures.request.plain.NewUserProjection;
import jakarta.validation.Valid;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * A real server with full auto-configuration: request bodies are read as the wire type, validated
 * and mapped, and springdoc documents the wire type as the request body.
 */
@SpringBootTest(classes = MappedRequestBodyIntegrationTest.App.class, webEnvironment = WebEnvironment.RANDOM_PORT)
class MappedRequestBodyIntegrationTest {

  private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT =
    new ParameterizedTypeReference<>() {};

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @ComponentScan(basePackageClasses = NewUserProjection.class)
  @Import(UserController.class)
  static class App {}

  @RestController
  static class UserController {

    @TelescopePostMapping(value = "/api/v1/users", to = NewUserView.class)
    NewUser create(@TelescopeRequestBody(from = CreateUserRequest.class) @Valid final NewUser user) {
      return user;
    }

    @PostMapping("/api/v1/users/audit")
    Map<String, Object> audit(@Valid final TelescopeRequest<CreateUserRequest, NewUser> request) {
      return Map.of("original", request.original().emailAddress(), "mapped", request.mapped().email());
    }
  }

  @LocalServerPort
  private int port;

  private RestClient client() {
    return RestClient.create("http://localhost:" + port);
  }

  private Map<String, Object> post(final String path, final String body) {
    return client().post().uri(path).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JSON_OBJECT);
  }

  @Test
  void bodyIsMappedOnTheWayInAndTheResponseOnTheWayOut() {
    assertThat(post("/api/v1/users", "{\"displayName\":\"Ada\",\"emailAddress\":\"A@X.ORG\"}")).isEqualTo(
      Map.of("name", "Ada")
    );
  }

  @Test
  void holderCarriesBothOverHttp() {
    assertThat(post("/api/v1/users/audit", "{\"displayName\":\"Ada\",\"emailAddress\":\"A@X.ORG\"}")).isEqualTo(
      Map.of("original", "A@X.ORG", "mapped", "a@x.org")
    );
  }

  @Test
  void invalidBodyIsABadRequestOverHttp() {
    final var refused = catchThrowableOfType(HttpClientErrorException.class, () ->
      post("/api/v1/users", "{\"displayName\":\"\",\"emailAddress\":\"a@x.org\"}")
    );
    assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  @SuppressWarnings("unchecked")
  void apiDocsShowTheWireTypeAsTheRequestBody() {
    final var docs = client().get().uri("/v3/api-docs").retrieve().body(JSON_OBJECT);
    final var paths = (Map<String, Object>) docs.get("paths");
    for (final var path : new String[] { "/api/v1/users", "/api/v1/users/audit" }) {
      final var operation = (Map<String, Object>) ((Map<String, Object>) paths.get(path)).get("post");
      assertThat(operation.get("requestBody").toString()).as(path).contains("#/components/schemas/CreateUserRequest");
      assertThat(operation.get("parameters")).as(path).isNull();
    }
    final var schemas = (Map<String, Object>) ((Map<String, Object>) docs.get("components")).get("schemas");
    assertThat(schemas).containsKey("CreateUserRequest").doesNotContainKey("NewUser");
  }
}
