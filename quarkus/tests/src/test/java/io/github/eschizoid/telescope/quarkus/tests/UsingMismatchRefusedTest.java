package io.github.eschizoid.telescope.quarkus.tests;

import static io.github.eschizoid.telescope.quarkus.tests.Refusals.refusedWith;
import static org.assertj.core.api.Assertions.fail;

import io.github.eschizoid.telescope.quarkus.rest.TelescopeResponse;
import io.github.eschizoid.telescope.quarkus.tests.fixtures.Order;
import io.github.eschizoid.telescope.quarkus.tests.fixtures.PlaceOrder;
import io.github.eschizoid.telescope.quarkus.tests.fixtures.SummaryProjection;
import io.quarkus.test.QuarkusUnitTest;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** A {@code using} projection that reads another type than the method returns fails startup. */
class UsingMismatchRefusedTest {

  @RegisterExtension
  static final QuarkusUnitTest APP = new QuarkusUnitTest()
    .withApplicationRoot(jar -> jar.addClasses(Orders.class).addPackage(Order.class.getPackage()))
    .assertException(refusedWith("Orders#place: SummaryProjection maps Order, but the method returns PlaceOrder"));

  @Path("/orders")
  public static class Orders {

    @GET
    @TelescopeResponse(using = SummaryProjection.class)
    public PlaceOrder place() {
      return new PlaceOrder("Ada", 1);
    }
  }

  @Test
  @DisplayName("a projection that reads another type than the method returns is refused at startup")
  void aMismatchedProjectionFailsStartup() {
    fail("the application booted");
  }
}
