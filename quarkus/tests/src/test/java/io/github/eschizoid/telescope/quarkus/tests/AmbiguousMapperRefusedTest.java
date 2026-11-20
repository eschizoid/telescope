package io.github.eschizoid.telescope.quarkus.tests;

import static io.github.eschizoid.telescope.quarkus.tests.Refusals.refusedWith;
import static org.assertj.core.api.Assertions.fail;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.conversion.Mapper;
import io.github.eschizoid.telescope.quarkus.rest.TelescopeResponse;
import io.github.eschizoid.telescope.quarkus.tests.fixtures.Order;
import io.github.eschizoid.telescope.quarkus.tests.fixtures.Summary;
import io.quarkus.test.QuarkusUnitTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** A pair two mappers map, with no {@code using} to choose one, fails startup naming both. */
class AmbiguousMapperRefusedTest {

  @RegisterExtension
  static final QuarkusUnitTest APP = new QuarkusUnitTest()
    .withApplicationRoot(jar -> jar.addClasses(Orders.class, SecondMapper.class).addPackage(Order.class.getPackage()))
    .assertException(
      refusedWith(
        "Orders#summary: 2 mappers for Order -> Summary: SummaryProjection, a Mapper<Order, Summary> bean." +
          " Add using = ... to @TelescopeResponse to choose one."
      )
    );

  @Path("/orders")
  public static class Orders {

    @GET
    @TelescopeResponse(to = Summary.class)
    public Order summary() {
      return new Order("SO-1", "Ada", 1200);
    }
  }

  @ApplicationScoped
  public static class SecondMapper {

    @Produces
    Mapper<Order, Summary> summaries() {
      return Telescope.mapper(Order.class, Summary.class);
    }
  }

  @Test
  @DisplayName("a pair both a projection and a Mapper bean map is refused at startup")
  void twoMappersFailStartup() {
    fail("the application booted");
  }
}
