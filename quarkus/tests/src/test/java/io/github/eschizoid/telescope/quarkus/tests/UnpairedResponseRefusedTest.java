package io.github.eschizoid.telescope.quarkus.tests;

import static io.github.eschizoid.telescope.quarkus.tests.Refusals.refusedWith;
import static org.assertj.core.api.Assertions.fail;

import io.github.eschizoid.telescope.quarkus.rest.TelescopeResponse;
import io.github.eschizoid.telescope.quarkus.tests.fixtures.Order;
import io.github.eschizoid.telescope.quarkus.tests.unpaired.Unrelated;
import io.quarkus.test.QuarkusUnitTest;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * A response whose pair has no mapper, and whose same-name forward mapping pairs no component,
 * fails startup naming the method, the pair and the components on each side.
 */
class UnpairedResponseRefusedTest {

  @RegisterExtension
  static final QuarkusUnitTest APP = new QuarkusUnitTest()
    .withApplicationRoot(jar -> jar.addClasses(Orders.class, Unrelated.class).addPackage(Order.class.getPackage()))
    .assertException(
      refusedWith(
        "Orders#order: Order -> Unrelated pairs nothing: no field of Unrelated (zzz, yyy) is named like a field" +
          " of Order (number, customer, costCents), so the mapping would leave each at its default. Check the" +
          " type the endpoint names, or map the pair with a TelescopeProjection, a Mapper bean or a @Bridge."
      )
    );

  @Path("/orders")
  public static class Orders {

    @GET
    @TelescopeResponse(to = Unrelated.class)
    public Order order() {
      return new Order("SO-1", "Ada", 1200);
    }
  }

  @Test
  @DisplayName("a response whose pair pairs no component by name is refused at startup")
  void aPairThatPairsNothingFailsStartup() {
    fail("the application booted");
  }
}
