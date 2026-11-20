package io.github.eschizoid.telescope.quarkus.tests;

import static io.github.eschizoid.telescope.quarkus.tests.Refusals.refusedWith;
import static org.assertj.core.api.Assertions.fail;

import io.github.eschizoid.telescope.quarkus.rest.TelescopeResponse;
import io.github.eschizoid.telescope.quarkus.tests.fixtures.Order;
import io.github.eschizoid.telescope.quarkus.tests.fixtures.OrderDto;
import io.quarkus.test.QuarkusUnitTest;
import io.smallrye.mutiny.Multi;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** A mapped method that returns a {@code Multi} fails the build. */
class MultiReturnRefusedTest {

  @RegisterExtension
  static final QuarkusUnitTest APP = new QuarkusUnitTest()
    .withApplicationRoot(jar -> jar.addClasses(Orders.class).addPackage(Order.class.getPackage()))
    .assertException(
      refusedWith(
        "Orders#stream: a Multi writes its elements as they arrive, past the response filters, so they" +
          " cannot be mapped. Return Uni<List<S>> instead."
      )
    );

  @Path("/orders")
  public static class Orders {

    @GET
    @TelescopeResponse(to = OrderDto.class)
    public Multi<Order> stream() {
      return Multi.createFrom().item(new Order("SO-1", "Ada", 1200));
    }
  }

  @Test
  @DisplayName("a Multi is refused, naming Uni of a list")
  void aMultiFailsTheBuild() {
    fail("the application booted");
  }
}
