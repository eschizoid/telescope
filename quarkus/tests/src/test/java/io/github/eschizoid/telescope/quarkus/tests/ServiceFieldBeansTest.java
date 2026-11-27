package io.github.eschizoid.telescope.quarkus.tests;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.eschizoid.telescope.conversion.BridgeProvider;
import io.github.eschizoid.telescope.conversion.EditMapper;
import io.github.eschizoid.telescope.quarkus.rest.TelescopeResponse;
import io.github.eschizoid.telescope.quarkus.tests.services.Catalog;
import io.github.eschizoid.telescope.quarkus.tests.services.Line;
import io.github.eschizoid.telescope.quarkus.tests.services.LineChanges;
import io.github.eschizoid.telescope.quarkus.tests.services.LineView;
import io.github.eschizoid.telescope.quarkus.tests.services.ShopMappers;
import io.quarkus.test.QuarkusUnitTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The extension registers a bean of each {@code @Bridge} field whose rows call a service, of the
 * field's own type and bound to the application's bean of each service; a resource injects it by
 * its type or by the field's name, and a mapped response converts through the bound bean of the
 * field declaring its pair.
 */
class ServiceFieldBeansTest {

  @RegisterExtension
  static final QuarkusUnitTest APP = new QuarkusUnitTest().withApplicationRoot(jar ->
    jar
      .addClasses(Lines.class, Catalogs.class)
      .addPackage(ShopMappers.class.getPackage())
      .addAsServiceProvider(BridgeProvider.class.getName(), ShopMappers.PROVIDERS)
  );

  @ApplicationScoped
  public static class Catalogs {

    @Produces
    @Singleton
    Catalog catalog() {
      return new Catalog(10);
    }
  }

  @Path("/lines")
  public static class Lines {

    private final EditMapper<LineChanges, Line> lines;

    Lines(final EditMapper<LineChanges, Line> lines) {
      this.lines = lines;
    }

    @GET
    @Path("/{sku}")
    @TelescopeResponse(to = LineView.class)
    public Line line(@PathParam("sku") final String sku) {
      return lines.forward(new LineChanges(sku));
    }
  }

  @Inject
  @Named("ShopMappers.LINE")
  EditMapper<LineChanges, Line> named;

  @Test
  @DisplayName("a resource injects the field's bound bean, and the mapped response converts through the field's bean")
  void boundBeansServe() {
    final var body = given().get("/lines/abc").then().statusCode(200).extract().asString();
    assertThat(body).isEqualTo("{\"sku\":\"abc\",\"price\":30,\"label\":\"L-abc\"}");
    assertThat(named.forward(new LineChanges("ab")).getPrice()).isEqualTo(20);
  }
}
