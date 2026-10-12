package io.github.eschizoid.telescope.spring.smoke;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import io.github.eschizoid.telescope.spring.TelescopeAutoConfiguration;
import io.github.eschizoid.telescope.spring.web.TelescopeGetMapping;
import io.github.eschizoid.telescope.spring.web.TelescopeWebMvcAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/**
 * Starts the starter's auto-configuration in a servlet web context with every jar on the module
 * path, serves one mapped request, and exits non-zero unless the response carries the mapped type.
 * Spring Data and springdoc are absent, so it also shows the starter loads without them.
 */
public final class ModuleSmoke {

  private ModuleSmoke() {}

  public record Account(String id, String name, String passwordHash) {}

  public record AccountView(String id, String name) {}

  @Configuration(proxyBeanMethods = false)
  @EnableWebMvc
  public static class Mvc {}

  @RestController
  public static class AccountController {

    @TelescopeGetMapping(value = "/accounts/{id}", to = AccountView.class)
    public Account account(@PathVariable("id") final String id) {
      return new Account(id, "Ada", "secret-hash");
    }
  }

  public static void main(final String[] args) throws Exception {
    if (!ModuleSmoke.class.getModule().isNamed()) throw new AssertionError("expected to run on the module path");
    try (final var context = new AnnotationConfigWebApplicationContext()) {
      context.setServletContext(new MockServletContext());
      context.register(
        TelescopeAutoConfiguration.class,
        TelescopeWebMvcAutoConfiguration.class,
        Mvc.class,
        AccountController.class
      );
      context.refresh();
      final var body = MockMvcBuilders.webAppContextSetup(context)
        .build()
        .perform(get("/accounts/7"))
        .andReturn()
        .getResponse()
        .getContentAsString();
      if (!body.equals("{\"id\":\"7\",\"name\":\"Ada\"}")) throw new AssertionError("unexpected body: " + body);
      System.out.println("module-path smoke: " + body);
    }
  }
}
