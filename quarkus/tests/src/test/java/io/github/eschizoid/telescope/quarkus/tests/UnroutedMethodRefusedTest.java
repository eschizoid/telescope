package io.github.eschizoid.telescope.quarkus.tests;

import static io.github.eschizoid.telescope.quarkus.tests.Refusals.refusedWith;
import static org.assertj.core.api.Assertions.fail;

import io.github.eschizoid.telescope.quarkus.rest.TelescopeResponse;
import io.github.eschizoid.telescope.quarkus.tests.fixtures.Order;
import io.github.eschizoid.telescope.quarkus.tests.fixtures.OrderDto;
import io.quarkus.test.QuarkusUnitTest;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** A {@code TelescopeResponse} on a method Quarkus REST does not route fails the build. */
class UnroutedMethodRefusedTest {

  @RegisterExtension
  static final QuarkusUnitTest APP = new QuarkusUnitTest()
    .withApplicationRoot(jar -> jar.addClasses(Orders.class).addPackage(Order.class.getPackage()))
    .assertException(
      refusedWith(
        "Orders#helper: @TelescopeResponse is on a method Quarkus REST does not route. Annotate it with" +
          " @GET, @POST, @PUT, @PATCH, @DELETE, @HEAD or @OPTIONS."
      )
    );

  @Path("/orders")
  public static class Orders {

    @GET
    public String ping() {
      return "pong";
    }

    @TelescopeResponse(to = OrderDto.class)
    public Order helper() {
      return new Order("SO-1", "Ada", 1200);
    }
  }

  @Test
  @DisplayName("a response annotation on a method no HTTP method routes is refused")
  void anUnroutedMethodFailsTheBuild() {
    fail("the application booted");
  }
}
