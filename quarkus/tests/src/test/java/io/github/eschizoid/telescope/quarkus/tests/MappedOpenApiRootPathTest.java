package io.github.eschizoid.telescope.quarkus.tests;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.eschizoid.telescope.quarkus.rest.TelescopeResponse;
import io.github.eschizoid.telescope.quarkus.tests.fixtures.Order;
import io.github.eschizoid.telescope.quarkus.tests.fixtures.OrderDto;
import io.quarkus.test.QuarkusUnitTest;
import io.restassured.http.ContentType;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The REST path prefixes every path of the document, and an application that narrows the classes
 * SmallRye OpenAPI scans still has its mapped endpoints documented with their wire types.
 */
class MappedOpenApiRootPathTest {

  @RegisterExtension
  static final QuarkusUnitTest APP = new QuarkusUnitTest()
    .withApplicationRoot(jar -> jar.addClasses(Orders.class).addPackage(Order.class.getPackage()))
    .overrideConfigKey("quarkus.rest.path", "/api")
    .overrideConfigKey("mp.openapi.scan.classes", Orders.class.getName());

  @Path("/orders")
  @Produces(MediaType.APPLICATION_JSON)
  public static class Orders {

    @GET
    @TelescopeResponse(to = OrderDto.class)
    public Order one() {
      return new Order("SO-1", "Ada", 1200);
    }
  }

  @Test
  @DisplayName("a mapped response under the REST path, in a scan narrowed to its class, is the wire type")
  void theRestPathPrefixesTheMatchedPath() {
    final var document = given().accept(ContentType.JSON).get("/q/openapi").then().statusCode(200).extract().jsonPath();
    assertThat(document.getMap("paths")).containsOnlyKeys("/api/orders");
    assertThat(
      document.getString("paths.'/api/orders'.get.responses.'200'.content.'application/json'.schema.'$ref'")
    ).isEqualTo("#/components/schemas/OrderDto");
  }
}
