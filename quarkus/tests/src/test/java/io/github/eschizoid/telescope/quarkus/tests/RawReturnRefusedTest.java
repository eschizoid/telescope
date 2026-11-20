package io.github.eschizoid.telescope.quarkus.tests;

import static io.github.eschizoid.telescope.quarkus.tests.Refusals.refusedWith;
import static org.assertj.core.api.Assertions.fail;

import io.github.eschizoid.telescope.quarkus.rest.TelescopeResponse;
import io.github.eschizoid.telescope.quarkus.tests.fixtures.Order;
import io.github.eschizoid.telescope.quarkus.tests.fixtures.OrderDto;
import io.quarkus.test.QuarkusUnitTest;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import org.jboss.resteasy.reactive.RestResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * A mapped method returning a generic type used raw fails the build: a raw {@code RestResponse}
 * names no type for the values it holds, and would otherwise write the domain value unmapped.
 */
class RawReturnRefusedTest {

  @RegisterExtension
  static final QuarkusUnitTest APP = new QuarkusUnitTest()
    .withApplicationRoot(jar -> jar.addClasses(Orders.class).addPackage(Order.class.getPackage()))
    .assertException(
      refusedWith(
        "Orders#raw: unsupported return type org.jboss.resteasy.reactive.RestResponse. A mapped response returns S," +
          " List<S>,"
      )
    );

  @Path("/orders")
  public static class Orders {

    @SuppressWarnings("rawtypes")
    @GET
    @TelescopeResponse(to = OrderDto.class)
    public RestResponse raw() {
      return RestResponse.ok(new Order("SO-1", "Ada", 1200));
    }
  }

  @Test
  @DisplayName("a raw RestResponse is refused with the shapes there are")
  void aRawRestResponseFailsTheBuild() {
    fail("the application booted");
  }
}
