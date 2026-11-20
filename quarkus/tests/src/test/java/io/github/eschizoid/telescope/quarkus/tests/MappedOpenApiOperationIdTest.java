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
 * With operation ids taken from method names and a duplicate refused, the application builds: the
 * generated operations take ids of their own, and the wire scan reads no class but those holding
 * mapped methods, so a class the application excludes from its document, whose method shares a
 * mapped method's name, is not scanned again.
 */
class MappedOpenApiOperationIdTest {

  @RegisterExtension
  static final QuarkusUnitTest APP = new QuarkusUnitTest()
    .withApplicationRoot(jar -> jar.addClasses(Orders.class, Legacy.class).addPackage(Order.class.getPackage()))
    .overrideConfigKey("mp.openapi.extensions.smallrye.operationIdStrategy", "METHOD")
    .overrideConfigKey("mp.openapi.extensions.smallrye.duplicateOperationIdBehavior", "FAIL")
    .overrideConfigKey("mp.openapi.scan.exclude.classes", Legacy.class.getName());

  @Path("/orders")
  @Produces(MediaType.APPLICATION_JSON)
  public static class Orders {

    @GET
    @Path("/first")
    @TelescopeResponse(to = OrderDto.class)
    public Order first() {
      return new Order("SO-1", "Ada", 1200);
    }

    @GET
    @Path("/second")
    @TelescopeResponse(to = OrderDto.class)
    public Order second() {
      return new Order("SO-2", "Ada", 1200);
    }
  }

  @Path("/legacy")
  @Produces(MediaType.APPLICATION_JSON)
  public static class Legacy {

    @GET
    public Order first() {
      return new Order("SO-0", "Ada", 1200);
    }
  }

  @Test
  @DisplayName("every mapped operation is documented, under its own operation id, beside an excluded namesake")
  void operationIdsDoNotCollide() {
    final var document = given().accept(ContentType.JSON).get("/q/openapi").then().statusCode(200).extract().jsonPath();
    for (final var path : new String[] { "/orders/first", "/orders/second" })
      assertThat(
        document.getString("paths.'" + path + "'.get.responses.'200'.content.'application/json'.schema.'$ref'")
      ).isEqualTo("#/components/schemas/OrderDto");
    assertThat(document.getString("paths.'/orders/first'.get.operationId")).isEqualTo("first");
    assertThat(document.getMap("paths")).doesNotContainKey("/legacy");
  }
}
