package io.github.eschizoid.telescope.quarkus.tests;

import static io.github.eschizoid.telescope.quarkus.tests.Refusals.refusedWith;
import static org.assertj.core.api.Assertions.fail;

import io.github.eschizoid.telescope.quarkus.rest.TelescopeResponse;
import io.github.eschizoid.telescope.quarkus.tests.fixtures.Order;
import io.github.eschizoid.telescope.quarkus.tests.fixtures.OrderDto;
import io.quarkus.test.QuarkusUnitTest;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** A mapped method that returns a bare {@code Response} fails the build. */
class ResponseReturnRefusedTest {

  @RegisterExtension
  static final QuarkusUnitTest APP = new QuarkusUnitTest()
    .withApplicationRoot(jar -> jar.addClasses(Orders.class).addPackage(Order.class.getPackage()))
    .assertException(
      refusedWith(
        "Orders#raw: a Response names no entity type to find a mapper for. Return RestResponse<S> instead," +
          " where S is the type the mapper reads."
      )
    );

  @Path("/orders")
  public static class Orders {

    @GET
    @TelescopeResponse(to = OrderDto.class)
    public Response raw() {
      return Response.ok(new Order("SO-1", "Ada", 1200)).build();
    }
  }

  @Test
  @DisplayName("a bare Response is refused, naming RestResponse")
  void aResponseFailsTheBuild() {
    fail("the application booted");
  }
}
