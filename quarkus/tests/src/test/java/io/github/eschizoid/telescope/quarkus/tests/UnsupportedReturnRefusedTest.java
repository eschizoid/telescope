package io.github.eschizoid.telescope.quarkus.tests;

import static io.github.eschizoid.telescope.quarkus.tests.Refusals.refusedWith;
import static org.assertj.core.api.Assertions.fail;

import io.github.eschizoid.telescope.quarkus.rest.TelescopeResponse;
import io.github.eschizoid.telescope.quarkus.tests.fixtures.Order;
import io.github.eschizoid.telescope.quarkus.tests.fixtures.OrderDto;
import io.quarkus.test.QuarkusUnitTest;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** A mapped method whose return type is no supported shape fails the build. */
class UnsupportedReturnRefusedTest {

  @RegisterExtension
  static final QuarkusUnitTest APP = new QuarkusUnitTest()
    .withApplicationRoot(jar -> jar.addClasses(Orders.class).addPackage(Order.class.getPackage()))
    .assertException(
      refusedWith(
        "Orders#byNumber: unsupported return type java.util.Map<java.lang.String," +
          " io.github.eschizoid.telescope.quarkus.tests.fixtures.Order>. A mapped response returns S, List<S>,"
      )
    );

  @Path("/orders")
  public static class Orders {

    @GET
    @TelescopeResponse(to = OrderDto.class)
    public Map<String, Order> byNumber() {
      return Map.of();
    }
  }

  @Test
  @DisplayName("a map return type is refused with the shapes there are")
  void aMapFailsTheBuild() {
    fail("the application booted");
  }
}
