package io.github.eschizoid.telescope.spring.smoke;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import io.github.eschizoid.telescope.spring.web.TelescopeGetMapping;
import java.util.ArrayList;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/**
 * Starts the auto-configurations the starter's jar lists in its {@code AutoConfiguration.imports},
 * read the way Spring Boot reads them, in a servlet web context with every jar on the module path.
 * It serves mapped requests and exits non-zero unless each response carries the mapped type. With
 * {@code full}, Spring Data and springdoc are on the path too, so the {@code Page} shape and the
 * springdoc configuration load.
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
    final var full = args.length > 0 && args[0].equals("full");
    if (!ModuleSmoke.class.getModule().isNamed()) throw new AssertionError("expected to run on the module path");
    final var starter = AccountView.class.getModule().getLayer().findModule("io.github.eschizoid.telescope.spring");
    if (starter.isEmpty() || !starter.get().isNamed()) throw new AssertionError(
      "expected the starter as a named module"
    );

    final var listed = new ArrayList<String>();
    for (final var name : ImportCandidates.load(AutoConfiguration.class, ModuleSmoke.class.getClassLoader())) {
      if (name.startsWith("io.github.eschizoid.telescope.")) listed.add(name);
    }
    if (!listed.contains("io.github.eschizoid.telescope.spring.web.TelescopeWebMvcAutoConfiguration")) {
      throw new AssertionError("the starter's AutoConfiguration.imports was not found: " + listed);
    }

    try (final var context = new AnnotationConfigWebApplicationContext()) {
      context.setServletContext(new MockServletContext());
      for (final var name : listed) context.register(Class.forName(name));
      context.register(Mvc.class, AccountController.class);
      if (full) context.register(Class.forName(ModuleSmoke.class.getPackageName() + ".PageController"));
      context.refresh();
      final var mvc = MockMvcBuilders.webAppContextSetup(context).build();

      expect(mvc, "/accounts/7", "{\"id\":\"7\",\"name\":\"Ada\"}");
      if (full) {
        expect(mvc, "/accounts", "\"content\":[{\"id\":\"1\",\"name\":\"Ada\"},{\"id\":\"2\",\"name\":\"Bea\"}]");
        SpringdocCheck.run(context);
      }
      System.out.println("module-path smoke (" + (full ? "full" : "bare") + "): ok");
    }
  }

  private static void expect(final MockMvc mvc, final String path, final String expected) throws Exception {
    final var body = mvc.perform(get(path)).andReturn().getResponse().getContentAsString();
    if (!body.contains(expected) || body.contains("passwordHash")) {
      throw new AssertionError(path + ": unexpected body " + body);
    }
  }
}
