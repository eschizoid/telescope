package io.github.eschizoid.telescope.quarkus.tests;

import static io.github.eschizoid.telescope.quarkus.tests.Refusals.refusedWith;
import static org.assertj.core.api.Assertions.fail;

import io.github.eschizoid.telescope.quarkus.rest.TelescopeResponse;
import io.github.eschizoid.telescope.quarkus.tests.fixtures.Order;
import io.quarkus.test.QuarkusUnitTest;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** A {@code TelescopeResponse} that names neither {@code to} nor {@code using} fails the build. */
class MissingTargetRefusedTest {

  @RegisterExtension
  static final QuarkusUnitTest APP = new QuarkusUnitTest()
    .withApplicationRoot(jar -> jar.addClasses(Orders.class).addPackage(Order.class.getPackage()))
    .assertException(refusedWith("Orders#order: set to or using on @TelescopeResponse."));

  @Path("/orders")
  public static class Orders {

    @GET
    @TelescopeResponse
    public Order order() {
      return new Order("SO-1", "Ada", 1200);
    }
  }

  @Test
  @DisplayName("a response annotation with no target is refused")
  void noTargetFailsTheBuild() {
    fail("the application booted");
  }
}
