package io.github.eschizoid.telescope.quarkus.tests;

import static io.github.eschizoid.telescope.quarkus.tests.Refusals.refusedWith;
import static org.assertj.core.api.Assertions.fail;

import io.github.eschizoid.telescope.quarkus.rest.TelescopeRequest;
import io.github.eschizoid.telescope.quarkus.rest.TelescopeRequestBody;
import io.github.eschizoid.telescope.quarkus.tests.fixtures.Order;
import io.github.eschizoid.telescope.quarkus.tests.fixtures.PlaceOrder;
import io.github.eschizoid.telescope.quarkus.tests.fixtures.PlaceOrderRequest;
import io.quarkus.test.QuarkusUnitTest;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * {@code from} on a {@code TelescopeRequest} parameter, whose type argument names the wire type,
 * fails the build.
 */
class HolderFromRefusedTest {

  @RegisterExtension
  static final QuarkusUnitTest APP = new QuarkusUnitTest()
    .withApplicationRoot(jar -> jar.addClasses(Orders.class).addPackage(Order.class.getPackage()))
    .assertException(
      refusedWith(
        "Orders#place(request): from is not allowed on a TelescopeRequest parameter; its first type argument" +
          " is the wire type."
      )
    );

  @Path("/orders")
  public static class Orders {

    @POST
    public String place(
      @TelescopeRequestBody(from = PlaceOrderRequest.class) final TelescopeRequest<
        PlaceOrderRequest,
        PlaceOrder
      > request
    ) {
      return request.mapped().customer();
    }
  }

  @Test
  @DisplayName("from on a TelescopeRequest parameter is refused")
  void fromOnAHolderFailsTheBuild() {
    fail("the application booted");
  }
}
