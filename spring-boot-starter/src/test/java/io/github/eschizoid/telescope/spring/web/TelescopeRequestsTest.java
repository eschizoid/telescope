package io.github.eschizoid.telescope.spring.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

import io.github.eschizoid.telescope.spring.web.fixtures.UserDto;
import io.github.eschizoid.telescope.spring.web.fixtures.request.CreateUserRequest;
import io.github.eschizoid.telescope.spring.web.fixtures.request.NewUser;
import io.github.eschizoid.telescope.spring.web.fixtures.request.plain.NewUserProjection;
import io.github.eschizoid.telescope.spring.web.fixtures.request.shout.ShoutingNewUserProjection;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Every request-body mapping is resolved at startup, and every refusal names the parameter. */
class TelescopeRequestsTest {

  private static final String ADA = "{\"displayName\":\"Ada\",\"emailAddress\":\"ADA@EXAMPLE.ORG\"}";

  @Configuration(proxyBeanMethods = false)
  @ComponentScan(basePackageClasses = NewUserProjection.class)
  static class PlainRequestProjection {}

  @Configuration(proxyBeanMethods = false)
  @ComponentScan(basePackageClasses = ShoutingNewUserProjection.class)
  static class ShoutingRequestProjection {}

  @RestController
  static class PlainController {

    @PostMapping("/plain")
    NewUser create(@TelescopeRequestBody(from = CreateUserRequest.class) final NewUser user) {
      return user;
    }
  }

  @RestController
  static class UsingController {

    @PostMapping("/shout")
    NewUser create(@TelescopeRequestBody(using = ShoutingNewUserProjection.class) final NewUser user) {
      return user;
    }

    @PostMapping("/shout/holder")
    NewUser holder(
      @TelescopeRequestBody(using = ShoutingNewUserProjection.class) final TelescopeRequest<
        CreateUserRequest,
        NewUser
      > request
    ) {
      return request.mapped();
    }
  }

  @RestController
  static class NoFromController {

    @PostMapping("/no-from")
    NewUser create(@TelescopeRequestBody final NewUser user) {
      return user;
    }
  }

  @RestController
  static class HolderWithFromController {

    @PostMapping("/holder-from")
    NewUser create(
      @TelescopeRequestBody(from = CreateUserRequest.class) final TelescopeRequest<CreateUserRequest, NewUser> request
    ) {
      return request.mapped();
    }
  }

  @RestController
  static class WildcardHolderController {

    @PostMapping("/wildcard")
    Object create(final TelescopeRequest<?, NewUser> request) {
      return request.mapped();
    }
  }

  @RestController
  static class OptionalController {

    @PostMapping("/optional")
    Object create(@TelescopeRequestBody(from = CreateUserRequest.class) final Optional<NewUser> user) {
      return user;
    }
  }

  @RestController
  static class MapController {

    @PostMapping("/map")
    Object create(@TelescopeRequestBody(from = CreateUserRequest.class) final Map<String, NewUser> users) {
      return users;
    }
  }

  @RestController
  static class BothAnnotationsController {

    @PostMapping("/both")
    NewUser create(@TelescopeRequestBody(from = CreateUserRequest.class) @RequestBody final NewUser user) {
      return user;
    }
  }

  @RestController
  static class WrongUsingController {

    @PostMapping("/wrong")
    UserDto create(@TelescopeRequestBody(using = NewUserProjection.class) final UserDto user) {
      return user;
    }
  }

  @Test
  void twoProjectionsForTheBodyFailStartup() {
    runner()
      .withUserConfiguration(PlainRequestProjection.class, ShoutingRequestProjection.class, PlainController.class)
      .run(context ->
        assertThat(context)
          .hasFailed()
          .getFailure()
          .hasStackTraceContaining("PlainController#create(user): 2 mappers for CreateUserRequest -> NewUser: ")
          .hasStackTraceContaining("NewUserProjection")
          .hasStackTraceContaining("ShoutingNewUserProjection")
          .hasStackTraceContaining("Add using = ... to @TelescopeRequestBody to choose one.")
      );
  }

  @Test
  void usingPicksTheProjectionAndSuppliesTheWireType() {
    runner()
      .withUserConfiguration(PlainRequestProjection.class, ShoutingRequestProjection.class, UsingController.class)
      .run(context -> {
        final var mvc = MockMvcBuilders.webAppContextSetup(context.getSourceApplicationContext()).build();
        mvc
          .perform(post("/shout").contentType(MediaType.APPLICATION_JSON).content(ADA))
          .andExpect(content().json("{\"name\":\"ADA\",\"email\":\"ADA@EXAMPLE.ORG\"}", true));
        mvc
          .perform(post("/shout/holder").contentType(MediaType.APPLICATION_JSON).content(ADA))
          .andExpect(content().json("{\"name\":\"ADA\",\"email\":\"ADA@EXAMPLE.ORG\"}", true));
      });
  }

  @Test
  void neitherFromNorUsingFailsStartup() {
    failsWith(NoFromController.class, "NoFromController#create(user): set from or using on @TelescopeRequestBody.");
  }

  @Test
  void fromOnAHolderFailsStartup() {
    failsWith(
      HolderWithFromController.class,
      "HolderWithFromController#create(request): from is not allowed on a TelescopeRequest parameter; its first type" +
        " argument is the wire type."
    );
  }

  @Test
  void holderWithoutConcreteTypeArgumentsFailsStartup() {
    failsWith(
      WildcardHolderController.class,
      "WildcardHolderController#create(request): TelescopeRequest needs two concrete type arguments"
    );
  }

  @Test
  void unsupportedParameterTypesFailStartup() {
    failsWith(
      OptionalController.class,
      "OptionalController#create(user): unsupported parameter type java.util.Optional<"
    );
    failsWith(MapController.class, "MapController#create(users): unsupported parameter type java.util.Map<");
  }

  @Test
  void requestBodyNextToTelescopeRequestBodyFailsStartup() {
    failsWith(
      BothAnnotationsController.class,
      "BothAnnotationsController#create(user): remove @RequestBody; @TelescopeRequestBody reads the body."
    );
  }

  @Test
  void usingWhoseTargetIsNotTheParameterFailsStartup() {
    runner()
      .withUserConfiguration(PlainRequestProjection.class, WrongUsingController.class)
      .run(context ->
        assertThat(context)
          .hasFailed()
          .getFailure()
          .hasStackTraceContaining(
            "WrongUsingController#create(user): NewUserProjection maps to NewUser, but the parameter is UserDto"
          )
      );
  }

  private static WebApplicationContextRunner runner() {
    return WebMvcTestSupport.runner();
  }

  private static void failsWith(final Class<?> controller, final String message) {
    runner()
      .withUserConfiguration(PlainRequestProjection.class, controller)
      .run(context -> assertThat(context).hasFailed().getFailure().hasStackTraceContaining(message));
  }
}
