package io.github.eschizoid.telescope.spring.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.eschizoid.telescope.spring.web.WebMvcTestSupport.PlainProjection;
import io.github.eschizoid.telescope.spring.web.fixtures.User;
import io.github.eschizoid.telescope.spring.web.fixtures.UserDto;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

class MappedResponseMvcTest {

  private static final User ADA = new User("1", "Ada");
  private static final User BEA = new User("2", "Bea");

  @RestController
  static class UserController {

    @TelescopeGetMapping(value = "/users/{id}", to = UserDto.class)
    User user(@PathVariable final String id) {
      return ADA;
    }

    @TelescopeGetMapping(value = "/users", to = UserDto.class)
    List<User> users() {
      return List.of(ADA, BEA);
    }

    @TelescopeGetMapping(value = "/users/with-gap", to = UserDto.class)
    List<User> withGap() {
      return Arrays.asList(ADA, null);
    }

    @TelescopeGetMapping(value = "/users/set", to = UserDto.class)
    Set<User> set() {
      return new LinkedHashSet<>(List.of(ADA, BEA));
    }

    @TelescopeGetMapping(value = "/users/maybe/{id}", to = UserDto.class)
    Optional<User> maybe(@PathVariable final String id) {
      return "1".equals(id) ? Optional.of(ADA) : Optional.empty();
    }

    @TelescopeGetMapping(value = "/users/page", to = UserDto.class)
    Page<User> page() {
      return new PageImpl<>(List.of(ADA, BEA), PageRequest.of(0, 2), 5);
    }

    @TelescopePostMapping(value = "/users", to = UserDto.class)
    ResponseEntity<User> create() {
      return ResponseEntity.status(HttpStatus.CREATED).header("X-Trace", "abc").body(ADA);
    }

    @TelescopeGetMapping(value = "/users/missing", to = UserDto.class)
    ResponseEntity<User> missing() {
      return ResponseEntity.notFound().build();
    }

    @TelescopeGetMapping(value = "/users/entity-list", to = UserDto.class)
    ResponseEntity<List<User>> entityList() {
      return ResponseEntity.ok(List.of(ADA, BEA));
    }

    @TelescopeGetMapping(value = "/users/problem", to = UserDto.class)
    ResponseEntity<User> problem() {
      return ResponseEntity.of(ProblemDetail.forStatus(HttpStatus.NOT_FOUND)).build();
    }

    @TelescopeGetMapping(value = "/users/problems", to = UserDto.class)
    ResponseEntity<List<User>> problems() {
      return ResponseEntity.of(ProblemDetail.forStatus(HttpStatus.NOT_FOUND)).build();
    }

    @TelescopeGetMapping(value = "/users/none", to = UserDto.class)
    User none() {
      return null;
    }

    @GetMapping("/raw/{id}")
    User raw(@PathVariable final String id) {
      return ADA;
    }
  }

  @Test
  void responseCarriesTheTargetShape() {
    run(mvc ->
      mvc
        .perform(get("/users/1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("1"))
        .andExpect(jsonPath("$.fullName").value("Ada"))
        .andExpect(jsonPath("$.name").doesNotExist())
    );
  }

  @Test
  void listAndSetMapEachElement() {
    run(mvc -> {
      mvc
        .perform(get("/users"))
        .andExpect(jsonPath("$[0].fullName").value("Ada"))
        .andExpect(jsonPath("$[1].fullName").value("Bea"));
      mvc
        .perform(get("/users/set"))
        .andExpect(jsonPath("$[0].fullName").value("Ada"))
        .andExpect(jsonPath("$[1].fullName").value("Bea"));
    });
  }

  @Test
  void listWithAGapKeepsTheGap() {
    run(mvc ->
      mvc
        .perform(get("/users/with-gap"))
        .andExpect(jsonPath("$[0].fullName").value("Ada"))
        .andExpect(jsonPath("$[1]").isEmpty())
    );
  }

  @Test
  void optionalMapsAPresentValueAndKeepsEmpty() {
    run(mvc -> {
      mvc.perform(get("/users/maybe/1")).andExpect(jsonPath("$.fullName").value("Ada"));
      mvc.perform(get("/users/maybe/9")).andExpect(status().isOk()).andExpect(content().string("null"));
    });
  }

  @Test
  void pageMapsItsContent() {
    run(mvc ->
      mvc
        .perform(get("/users/page"))
        .andExpect(jsonPath("$.content[0].fullName").value("Ada"))
        .andExpect(jsonPath("$.content[1].fullName").value("Bea"))
    );
  }

  @Test
  void entityKeepsStatusAndHeaders() {
    run(mvc ->
      mvc
        .perform(post("/users"))
        .andExpect(status().isCreated())
        .andExpect(header().string("X-Trace", "abc"))
        .andExpect(jsonPath("$.fullName").value("Ada"))
    );
  }

  @Test
  void entityWithoutBodyKeepsItsStatus() {
    run(mvc -> mvc.perform(get("/users/missing")).andExpect(status().isNotFound()).andExpect(content().string("")));
  }

  @Test
  void entityOfAListMapsTheList() {
    run(mvc -> mvc.perform(get("/users/entity-list")).andExpect(jsonPath("$[1].fullName").value("Bea")));
  }

  // ResponseEntity.of(ProblemDetail).build() compiles for any ResponseEntity<T>, so an error body
  // need not be an S.
  @Test
  void problemDetailBodyIsWrittenUnmapped() {
    run(mvc -> {
      mvc.perform(get("/users/problem")).andExpect(status().isNotFound()).andExpect(jsonPath("$.status").value(404));
      mvc.perform(get("/users/problems")).andExpect(status().isNotFound()).andExpect(jsonPath("$.status").value(404));
    });
  }

  @Test
  void nullResponseIsWrittenAsNoBody() {
    run(mvc -> mvc.perform(get("/users/none")).andExpect(status().isOk()).andExpect(content().string("")));
  }

  @Test
  void unannotatedHandlerIsUntouched() {
    run(mvc ->
      mvc
        .perform(get("/raw/1"))
        .andExpect(jsonPath("$.name").value("Ada"))
        .andExpect(jsonPath("$.fullName").doesNotExist())
    );
  }

  @Test
  void javaCallerGetsTheDeclaredType() {
    WebMvcTestSupport.runner()
      .withUserConfiguration(PlainProjection.class, UserController.class)
      .run(context -> assertThat(context.getBean(UserController.class).user("1")).isEqualTo(ADA));
  }

  private interface MvcCheck {
    void check(MockMvc mvc) throws Exception;
  }

  private static void run(final MvcCheck check) {
    WebMvcTestSupport.runner()
      .withUserConfiguration(PlainProjection.class, UserController.class)
      .run(context -> check.check(mockMvc(context)));
  }

  private static MockMvc mockMvc(final AssertableWebApplicationContext context) {
    return MockMvcBuilders.webAppContextSetup(context.getSourceApplicationContext()).build();
  }
}
