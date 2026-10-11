package io.github.eschizoid.telescope.spring.web.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.eschizoid.telescope.spring.web.TelescopeGetMapping;
import io.github.eschizoid.telescope.spring.web.fixtures.Account;
import io.github.eschizoid.telescope.spring.web.fixtures.AccountView;
import io.github.eschizoid.telescope.spring.web.fixtures.User;
import io.github.eschizoid.telescope.spring.web.fixtures.UserDto;
import io.github.eschizoid.telescope.spring.web.fixtures.plain.PlainUserDtoProjection;
import org.junit.jupiter.api.Test;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/** springdoc builds each {@link GroupedOpenApi} document separately from the default one. */
@SpringBootTest(classes = MappedResponseGroupIntegrationTest.App.class, webEnvironment = WebEnvironment.RANDOM_PORT)
class MappedResponseGroupIntegrationTest {

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @ComponentScan(basePackageClasses = PlainUserDtoProjection.class)
  @Import(GroupedController.class)
  static class App {

    @Bean
    GroupedOpenApi users() {
      return GroupedOpenApi.builder().group("users").pathsToMatch("/users/**").build();
    }

    @Bean
    GroupedOpenApi accounts() {
      return GroupedOpenApi.builder().group("accounts").pathsToMatch("/accounts/**").build();
    }
  }

  @RestController
  static class GroupedController {

    @TelescopeGetMapping(value = "/users/{id}", to = UserDto.class)
    User user(@PathVariable final String id) {
      return new User(id, "Ada");
    }

    @TelescopeGetMapping(value = "/accounts/{id}", to = AccountView.class)
    Account account(@PathVariable final String id) {
      return new Account(id, "Ada", "secret-hash");
    }
  }

  @LocalServerPort
  private int port;

  private String docs(final String group) {
    return RestClient.create("http://localhost:" + port)
      .get()
      .uri("/v3/api-docs/" + group)
      .retrieve()
      .body(String.class);
  }

  @Test
  void eachGroupDocumentsItsOwnResponseTypes() {
    final var users = docs("users");
    assertThat(users).contains("\"#/components/schemas/UserDto\"").contains("\"UserDto\":");
    assertThat(users).doesNotContain("\"#/components/schemas/User\"").doesNotContain("AccountView");

    final var accounts = docs("accounts");
    assertThat(accounts).contains("\"#/components/schemas/AccountView\"").contains("\"AccountView\":");
    assertThat(accounts).doesNotContain("passwordHash").doesNotContain("UserDto");
  }
}
