package io.github.eschizoid.telescope.spring.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.eschizoid.telescope.spring.web.fixtures.request.CreateUserRequest;
import io.github.eschizoid.telescope.spring.web.fixtures.request.NewUser;
import io.github.eschizoid.telescope.spring.web.fixtures.request.NewUserView;
import io.github.eschizoid.telescope.spring.web.fixtures.request.plain.NewUserProjection;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Request bodies read as the wire type and mapped before the handler runs. The wire DTO's names
 * differ from the domain type's and the projection lower-cases the email, so a body read straight
 * into {@code NewUser} never produces the values these tests expect.
 */
class MappedRequestBodyMvcTest {

  private static final String ADA = "{\"displayName\":\"Ada\",\"emailAddress\":\"ADA@EXAMPLE.ORG\"}";

  @Configuration(proxyBeanMethods = false)
  @ComponentScan(basePackageClasses = NewUserProjection.class)
  static class RequestProjection {}

  interface InterfaceApi {
    @PostMapping("/api/users")
    NewUser create(@TelescopeRequestBody(from = CreateUserRequest.class) NewUser user);
  }

  @RestController
  static class InterfaceController implements InterfaceApi {

    @Override
    public NewUser create(final NewUser user) {
      return user;
    }
  }

  @RestController
  static class UserController {

    final AtomicInteger calls = new AtomicInteger();

    @PostMapping("/users")
    NewUser create(@TelescopeRequestBody(from = CreateUserRequest.class) @Valid final NewUser user) {
      calls.incrementAndGet();
      return user;
    }

    @PostMapping("/users/holder")
    Map<String, Object> holder(@Valid final TelescopeRequest<CreateUserRequest, NewUser> request) {
      calls.incrementAndGet();
      return Map.of("original", request.original(), "mapped", request.mapped());
    }

    @PostMapping("/users/optional")
    String optional(@TelescopeRequestBody(from = CreateUserRequest.class, required = false) final NewUser user) {
      return user == null ? "none" : user.name();
    }

    @PostMapping("/users/batch")
    List<NewUser> batch(@TelescopeRequestBody(from = CreateUserRequest.class) final List<NewUser> users) {
      return users;
    }

    @PostMapping("/users/set")
    int set(@TelescopeRequestBody(from = CreateUserRequest.class) final Set<NewUser> users) {
      return users.size();
    }

    @TelescopePostMapping(value = "/users/view", to = NewUserView.class)
    NewUser view(@TelescopeRequestBody(from = CreateUserRequest.class) final NewUser user) {
      return user;
    }

    // A constraint on another parameter turns on Spring's method validation for the whole handler.
    @PostMapping("/teams/{team}/users")
    NewUser join(
      @PathVariable @Min(1) final long team,
      @TelescopeRequestBody(from = CreateUserRequest.class) @Valid final NewUser user
    ) {
      calls.incrementAndGet();
      return user;
    }

    @PostMapping("/users/batch/valid")
    int validBatch(@TelescopeRequestBody(from = CreateUserRequest.class) @Valid final List<NewUser> users) {
      calls.incrementAndGet();
      return users.size();
    }

    @PostMapping("/users/raw")
    CreateUserRequest raw(@RequestBody final CreateUserRequest request) {
      return request;
    }
  }

  @Test
  void bodyIsReadAsTheWireTypeAndMappedBeforeTheHandlerRuns() {
    run(mvc ->
      mvc
        .perform(json("/users", ADA))
        .andExpect(status().isOk())
        .andExpect(content().json("{\"name\":\"Ada\",\"email\":\"ada@example.org\"}", true))
    );
  }

  @Test
  void holderCarriesTheOriginalAndTheMappedValue() {
    run(mvc ->
      mvc
        .perform(json("/users/holder", ADA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.original.displayName").value("Ada"))
        .andExpect(jsonPath("$.original.emailAddress").value("ADA@EXAMPLE.ORG"))
        .andExpect(jsonPath("$.mapped.name").value("Ada"))
        .andExpect(jsonPath("$.mapped.email").value("ada@example.org"))
    );
  }

  @Test
  void invalidWireBodyIsABadRequestAndTheHandlerNeverRuns() {
    runWithController((mvc, controller) -> {
      final var blank = "{\"displayName\":\"\",\"emailAddress\":\"ada@example.org\"}";
      mvc.perform(json("/users", blank)).andExpect(status().isBadRequest());
      mvc.perform(json("/users/holder", blank)).andExpect(status().isBadRequest());
      assertThat(controller.calls).hasValue(0);
    });
  }

  @Test
  void wireBodyIsValidatedWhenMethodValidationAppliesToTheHandler() {
    runWithController((mvc, controller) -> {
      final var blank = "{\"displayName\":\"\",\"emailAddress\":\"ada@example.org\"}";
      mvc.perform(json("/teams/1/users", blank)).andExpect(status().isBadRequest());
      mvc.perform(json("/users/batch/valid", "[" + ADA + "," + blank + "]")).andExpect(status().isBadRequest());
      assertThat(controller.calls).hasValue(0);
      mvc.perform(json("/teams/1/users", ADA)).andExpect(status().isOk());
      mvc.perform(json("/users/batch/valid", "[" + ADA + "]")).andExpect(status().isOk());
    });
  }

  @Test
  void missingRequiredBodyIsABadRequest() {
    run(mvc -> {
      mvc.perform(post("/users").contentType(MediaType.APPLICATION_JSON)).andExpect(status().isBadRequest());
      mvc.perform(post("/users/holder").contentType(MediaType.APPLICATION_JSON)).andExpect(status().isBadRequest());
    });
  }

  @Test
  void missingOptionalBodyReachesTheHandlerAsNull() {
    run(mvc -> {
      mvc.perform(post("/users/optional").contentType(MediaType.APPLICATION_JSON)).andExpect(content().string("none"));
      mvc.perform(json("/users/optional", ADA)).andExpect(content().string("Ada"));
    });
  }

  @Test
  void malformedJsonIsABadRequest() {
    run(mvc -> mvc.perform(json("/users", "{\"displayName\":")).andExpect(status().isBadRequest()));
  }

  @Test
  void unsupportedContentTypeIsRefused() {
    run(mvc ->
      mvc
        .perform(post("/users").contentType(MediaType.TEXT_PLAIN).content("Ada"))
        .andExpect(status().isUnsupportedMediaType())
    );
  }

  @Test
  void listBodyMapsEachElementAndKeepsNulls() {
    run(mvc ->
      mvc
        .perform(json("/users/batch", "[" + ADA + ",null]"))
        .andExpect(content().json("[{\"name\":\"Ada\",\"email\":\"ada@example.org\"},null]", true))
    );
  }

  @Test
  void setBodyMapsEachElement() {
    run(mvc ->
      mvc
        .perform(json("/users/set", "[" + ADA + ",{\"displayName\":\"Bea\",\"emailAddress\":\"B@X.ORG\"}]"))
        .andExpect(content().string("2"))
    );
  }

  @Test
  void mappedRequestAndMappedResponseOnOneEndpoint() {
    run(mvc -> mvc.perform(json("/users/view", ADA)).andExpect(content().json("{\"name\":\"Ada\"}", true)));
  }

  @Test
  void plainRequestBodyIsUntouched() {
    run(mvc -> mvc.perform(json("/users/raw", ADA)).andExpect(content().json(ADA, true)));
  }

  @Test
  void bodyDeclaredOnAnInterfaceParameterIsMapped() {
    WebMvcTestSupport.runner()
      .withUserConfiguration(RequestProjection.class, InterfaceController.class)
      .run(context ->
        mockMvc(context)
          .perform(json("/api/users", ADA))
          .andExpect(content().json("{\"name\":\"Ada\",\"email\":\"ada@example.org\"}", true))
      );
  }

  private interface MvcCheck {
    void check(MockMvc mvc) throws Exception;
  }

  private interface ControllerCheck {
    void check(MockMvc mvc, UserController controller) throws Exception;
  }

  private static void run(final MvcCheck check) {
    runWithController((mvc, controller) -> check.check(mvc));
  }

  private static void runWithController(final ControllerCheck check) {
    WebMvcTestSupport.runner()
      .withUserConfiguration(RequestProjection.class, UserController.class)
      .run(context -> check.check(mockMvc(context), context.getBean(UserController.class)));
  }

  private static MockMvc mockMvc(final AssertableWebApplicationContext context) {
    return MockMvcBuilders.webAppContextSetup(context.getSourceApplicationContext()).build();
  }

  private static MockHttpServletRequestBuilder json(final String path, final String body) {
    return post(path).contentType(MediaType.APPLICATION_JSON).content(body);
  }
}
