package io.github.eschizoid.telescope.spring.web.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.eschizoid.telescope.spring.web.TelescopeGetMapping;
import io.github.eschizoid.telescope.spring.web.TelescopePostMapping;
import io.github.eschizoid.telescope.spring.web.fixtures.UserDto;
import io.github.eschizoid.telescope.spring.web.fixtures.UserRestDto;
import io.github.eschizoid.telescope.spring.web.fixtures.rest.UserRestDtoProjection;
import java.util.List;
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
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/**
 * The layering a REST adapter has: the service returns {@link UserDto}, the endpoint returns it
 * unchanged, and the client receives {@link UserRestDto}. Bodies are read as maps: an unmapped body
 * carries {@code fullName}, which a typed read of {@code UserRestDto} would drop silently.
 */
@SpringBootTest(classes = RestDtoIntegrationTest.App.class, webEnvironment = WebEnvironment.RANDOM_PORT)
class RestDtoIntegrationTest {

  private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT =
    new ParameterizedTypeReference<>() {};

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @ComponentScan(basePackageClasses = UserRestDtoProjection.class)
  @Import({ UserService.class, UserRestController.class })
  static class App {}

  @Service
  static class UserService {

    UserDto findUser(final String id) {
      return new UserDto(id, "Ada Lovelace");
    }

    List<UserDto> findAll() {
      return List.of(new UserDto("1", "Ada Lovelace"), new UserDto("2", "Grace Hopper"));
    }

    UserDto create() {
      return new UserDto("3", "Alan Turing");
    }
  }

  @RestController
  static class UserRestController {

    private final UserService service;

    UserRestController(final UserService service) {
      this.service = service;
    }

    @TelescopeGetMapping(value = "/api/v1/users/{id}", to = UserRestDto.class)
    UserDto user(@PathVariable final String id) {
      return service.findUser(id);
    }

    @TelescopeGetMapping(value = "/api/v1/users", to = UserRestDto.class)
    List<UserDto> users() {
      return service.findAll();
    }

    @TelescopePostMapping(value = "/api/v1/users", to = UserRestDto.class)
    ResponseEntity<UserDto> create() {
      return ResponseEntity.status(HttpStatus.CREATED).header("Location", "/api/v1/users/3").body(service.create());
    }
  }

  @LocalServerPort
  private int port;

  private RestClient client() {
    return RestClient.create("http://localhost:" + port);
  }

  @Test
  void dtoReturnedDirectlyIsServedAsTheRestDto() {
    assertThat(client().get().uri("/api/v1/users/1").retrieve().body(JSON_OBJECT)).isEqualTo(
      Map.of("id", "1", "name", "Ada Lovelace")
    );
  }

  @Test
  void listOfDtosIsServedAsRestDtos() {
    assertThat(
      client()
        .get()
        .uri("/api/v1/users")
        .retrieve()
        .body(new ParameterizedTypeReference<List<Map<String, Object>>>() {})
    ).containsExactly(Map.of("id", "1", "name", "Ada Lovelace"), Map.of("id", "2", "name", "Grace Hopper"));
  }

  @Test
  void dtoInAResponseEntityIsServedAsTheRestDtoWithStatusAndHeaders() {
    final var response = client().post().uri("/api/v1/users").retrieve().toEntity(JSON_OBJECT);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(response.getHeaders().getFirst("Location")).isEqualTo("/api/v1/users/3");
    assertThat(response.getBody()).isEqualTo(Map.of("id", "3", "name", "Alan Turing"));
  }

  @Test
  void javaCallerOfTheEndpointGetsTheServiceDto() {
    final var controller = new UserRestController(new UserService());
    assertThat(controller.user("1")).isEqualTo(new UserDto("1", "Ada Lovelace"));
    assertThat(controller.create().getBody()).isEqualTo(new UserDto("3", "Alan Turing"));
  }
}
