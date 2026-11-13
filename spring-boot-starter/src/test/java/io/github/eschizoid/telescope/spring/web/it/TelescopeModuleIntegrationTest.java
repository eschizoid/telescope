package io.github.eschizoid.telescope.spring.web.it;

import static io.github.eschizoid.telescope.mapping.Mapping.to;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.conversion.Mapper;
import io.github.eschizoid.telescope.spring.jackson.TelescopeModuleCustomizer;
import io.github.eschizoid.telescope.spring.web.TelescopeGetMapping;
import io.github.eschizoid.telescope.spring.web.TelescopePostMapping;
import io.github.eschizoid.telescope.spring.web.TelescopeRequestBody;
import io.github.eschizoid.telescope.spring.web.fixtures.User;
import io.github.eschizoid.telescope.spring.web.fixtures.UserDto;
import io.github.eschizoid.telescope.spring.web.fixtures.request.CreateUserRequest;
import io.github.eschizoid.telescope.spring.web.fixtures.request.NewUser;
import io.github.eschizoid.telescope.spring.web.fixtures.request.NewUserView;
import io.github.eschizoid.telescope.spring.web.fixtures.request.plain.NewUserProjection;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/**
 * A real server with full auto-configuration and telescope-jackson on the classpath. The module
 * reads and writes the domain type it carries in its wire shape, and the mapped request and
 * response bodies, which convert other types, behave as they do without it.
 */
@SpringBootTest(classes = TelescopeModuleIntegrationTest.App.class, webEnvironment = WebEnvironment.RANDOM_PORT)
class TelescopeModuleIntegrationTest {

  private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT =
    new ParameterizedTypeReference<>() {};

  /** A ticket as the API writes it. */
  public record TicketWire(String subject) {}

  /** A ticket as the service holds it. */
  public record Ticket(String title) {}

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @ComponentScan(basePackageClasses = NewUserProjection.class)
  @Import(Endpoints.class)
  static class App {

    @Bean
    TelescopeModuleCustomizer tickets() {
      final Mapper<TicketWire, Ticket> tickets = Telescope.mapper(
        TicketWire.class,
        Ticket.class,
        to(TicketWire::subject, Ticket::title)
      );
      return module -> module.add(tickets);
    }

    @Bean
    Mapper<User, UserDto> userDto() {
      return Telescope.mapper(User.class, UserDto.class, to(User::name, UserDto::fullName));
    }
  }

  @RestController
  static class Endpoints {

    @GetMapping("/tickets/1")
    Ticket ticket() {
      return new Ticket("printer");
    }

    @PostMapping("/tickets")
    String open(@RequestBody final Ticket ticket) {
      return ticket.title();
    }

    @TelescopeGetMapping(value = "/users/1", to = UserDto.class)
    User user() {
      return new User("1", "Ada");
    }

    @TelescopePostMapping(value = "/users", to = NewUserView.class)
    NewUser create(@TelescopeRequestBody(from = CreateUserRequest.class) final NewUser user) {
      return user;
    }
  }

  @LocalServerPort
  private int port;

  private RestClient client() {
    return RestClient.create("http://localhost:" + port);
  }

  @Test
  @DisplayName("the domain type the module carries is written and read in its wire shape")
  void moduleMapsItsDomainType() {
    assertThat(client().get().uri("/tickets/1").retrieve().body(JSON_OBJECT)).isEqualTo(Map.of("subject", "printer"));
    assertThat(
      client()
        .post()
        .uri("/tickets")
        .contentType(MediaType.APPLICATION_JSON)
        .body("{\"subject\":\"scanner\"}")
        .retrieve()
        .body(String.class)
    ).isEqualTo("scanner");
  }

  @Test
  @DisplayName("mapped responses and request bodies are unchanged by the module")
  void mappedBodiesAreUnchanged() {
    assertThat(client().get().uri("/users/1").retrieve().body(JSON_OBJECT)).isEqualTo(
      Map.of("id", "1", "fullName", "Ada")
    );
    assertThat(
      client()
        .post()
        .uri("/users")
        .contentType(MediaType.APPLICATION_JSON)
        .body("{\"displayName\":\"Ada\",\"emailAddress\":\"A@X.ORG\"}")
        .retrieve()
        .body(JSON_OBJECT)
    ).isEqualTo(Map.of("name", "Ada"));
  }
}
