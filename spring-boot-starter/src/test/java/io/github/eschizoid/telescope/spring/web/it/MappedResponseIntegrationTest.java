package io.github.eschizoid.telescope.spring.web.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.eschizoid.telescope.spring.web.TelescopeGetMapping;
import io.github.eschizoid.telescope.spring.web.TelescopePostMapping;
import io.github.eschizoid.telescope.spring.web.fixtures.Account;
import io.github.eschizoid.telescope.spring.web.fixtures.AccountView;
import io.github.eschizoid.telescope.spring.web.fixtures.User;
import io.github.eschizoid.telescope.spring.web.fixtures.UserDto;
import io.github.eschizoid.telescope.spring.web.fixtures.plain.PlainUserDtoProjection;
import io.github.eschizoid.telescope.spring.web.fixtures.upper.UpperUserDtoProjection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/**
 * A real application on a random port, started with full auto-configuration and called over HTTP:
 * the one test that goes through {@code AutoConfiguration.imports}, the embedded server, and
 * springdoc's own {@code /v3/api-docs}.
 */
@SpringBootTest(classes = MappedResponseIntegrationTest.App.class, webEnvironment = WebEnvironment.RANDOM_PORT)
class MappedResponseIntegrationTest {

  private static final User ADA = new User("1", "Ada");
  private static final User BEA = new User("2", "Bea");

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @ComponentScan(basePackageClasses = { PlainUserDtoProjection.class, UpperUserDtoProjection.class })
  @Import(UserController.class)
  static class App {}

  @RestController
  static class UserController {

    @TelescopeGetMapping(value = "/users/{id}", using = PlainUserDtoProjection.class)
    User user(@PathVariable final String id) {
      return "1".equals(id) ? ADA : BEA;
    }

    @TelescopeGetMapping(value = "/users", using = PlainUserDtoProjection.class)
    List<User> users() {
      return List.of(ADA, BEA);
    }

    @TelescopeGetMapping(value = "/users/{id}/upper", using = UpperUserDtoProjection.class)
    User upper(@PathVariable final String id) {
      return ADA;
    }

    @TelescopeGetMapping(value = "/accounts/{id}", to = AccountView.class)
    Optional<Account> account(@PathVariable final String id) {
      return Optional.of(new Account(id, "Ada", "secret-hash"));
    }

    @TelescopePostMapping(value = "/users", using = PlainUserDtoProjection.class)
    ResponseEntity<User> create() {
      return ResponseEntity.status(HttpStatus.CREATED).header("X-Trace", "abc").body(BEA);
    }
  }

  @LocalServerPort
  private int port;

  private RestClient client() {
    return RestClient.create("http://localhost:" + port);
  }

  @Test
  void singleValueIsMappedOverHttp() {
    assertThat(client().get().uri("/users/1").retrieve().body(UserDto.class)).isEqualTo(new UserDto("1", "Ada"));
    assertThat(client().get().uri("/users/2").retrieve().body(UserDto.class)).isEqualTo(new UserDto("2", "Bea"));
  }

  @Test
  void listIsMappedElementByElementOverHttp() {
    assertThat(
      client().get().uri("/users").retrieve().body(new ParameterizedTypeReference<List<UserDto>>() {})
    ).containsExactly(new UserDto("1", "Ada"), new UserDto("2", "Bea"));
  }

  @Test
  void usingPicksTheProjectionOverHttp() {
    assertThat(client().get().uri("/users/1/upper").retrieve().body(UserDto.class)).isEqualTo(new UserDto("1", "ADA"));
  }

  // Read as a map: the unmapped body would carry passwordHash, which a typed read of AccountView
  // would drop silently.
  @Test
  void sameNameTargetIsMappedWithoutAMapperOverHttp() {
    assertThat(
      client().get().uri("/accounts/7").retrieve().body(new ParameterizedTypeReference<Map<String, Object>>() {})
    ).isEqualTo(Map.of("id", "7", "name", "Ada"));
  }

  @Test
  void entityKeepsStatusAndHeadersOverHttp() {
    final var response = client().post().uri("/users").retrieve().toEntity(UserDto.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(response.getHeaders().getFirst("X-Trace")).isEqualTo("abc");
    assertThat(response.getBody()).isEqualTo(new UserDto("2", "Bea"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void apiDocsShowTheResponseType() {
    final var docs = client()
      .get()
      .uri("/v3/api-docs")
      .retrieve()
      .body(new ParameterizedTypeReference<Map<String, Object>>() {});
    final var schemas = (Map<String, Object>) ((Map<String, Object>) docs.get("components")).get("schemas");
    assertThat(schemas).containsKeys("UserDto", "AccountView");
    final var paths = (Map<String, Object>) docs.get("paths");
    final var get = ((Map<String, Object>) paths.get("/users/{id}")).get("get").toString();
    assertThat(get).contains("#/components/schemas/UserDto").doesNotContain("#/components/schemas/User}");
  }
}
