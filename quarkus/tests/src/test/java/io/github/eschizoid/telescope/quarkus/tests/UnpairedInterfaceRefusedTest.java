package io.github.eschizoid.telescope.quarkus.tests;

import static io.github.eschizoid.telescope.quarkus.tests.Refusals.refusedWith;
import static org.assertj.core.api.Assertions.fail;

import io.github.eschizoid.telescope.quarkus.rest.TelescopeResponse;
import io.github.eschizoid.telescope.quarkus.tests.fixtures.Order;
import io.github.eschizoid.telescope.quarkus.tests.unpaired.CityView;
import io.github.eschizoid.telescope.quarkus.tests.unpaired.Unrelated;
import io.quarkus.test.QuarkusUnitTest;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * A response whose source is an interface, read by its getters, none of them named like a component
 * of the target, fails startup naming the method, the pair and both sides.
 */
class UnpairedInterfaceRefusedTest {

  @RegisterExtension
  static final QuarkusUnitTest APP = new QuarkusUnitTest()
    .withApplicationRoot(jar ->
      jar.addClasses(Cities.class, CityView.class, Unrelated.class).addPackage(Order.class.getPackage())
    )
    .assertException(
      refusedWith(
        "Cities#city: CityView -> Unrelated pairs nothing: no field of Unrelated (zzz, yyy) is named like a property" +
          " of CityView (city), so the mapping would leave each at its default. Check the type the endpoint names," +
          " or map the pair with a TelescopeProjection, a Mapper bean or a @Bridge."
      )
    );

  @Path("/cities")
  public static class Cities {

    @GET
    @TelescopeResponse(to = Unrelated.class)
    public CityView city() {
      return () -> "Kyiv";
    }
  }

  @Test
  @DisplayName("an interface source whose getters pair with no component is refused at startup")
  void anInterfaceThatPairsNothingFailsStartup() {
    fail("the application booted");
  }
}
