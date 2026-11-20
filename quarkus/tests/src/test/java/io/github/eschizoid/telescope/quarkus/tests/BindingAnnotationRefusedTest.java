package io.github.eschizoid.telescope.quarkus.tests;

import static io.github.eschizoid.telescope.quarkus.tests.Refusals.refusedWith;
import static org.assertj.core.api.Assertions.fail;

import io.github.eschizoid.telescope.quarkus.rest.TelescopeRequestBody;
import io.github.eschizoid.telescope.quarkus.tests.fixtures.Order;
import io.github.eschizoid.telescope.quarkus.tests.fixtures.PlaceOrderRequest;
import io.quarkus.test.QuarkusUnitTest;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** A mapped body parameter that Quarkus REST binds from elsewhere fails the build. */
class BindingAnnotationRefusedTest {

  @RegisterExtension
  static final QuarkusUnitTest APP = new QuarkusUnitTest()
    .withApplicationRoot(jar -> jar.addClasses(Orders.class).addPackage(Order.class.getPackage()))
    .assertException(refusedWith("Orders#place(customer): remove @HeaderParam; @TelescopeRequestBody reads the body."));

  @Path("/orders")
  public static class Orders {

    @POST
    public String place(
      @TelescopeRequestBody(from = PlaceOrderRequest.class) @HeaderParam("X-Customer") final String customer
    ) {
      return customer;
    }
  }

  @Test
  @DisplayName("a mapped body parameter bound from a header is refused")
  void aHeaderParameterFailsTheBuild() {
    fail("the application booted");
  }
}
