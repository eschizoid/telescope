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
import org.eclipse.microprofile.openapi.annotations.extensions.Extension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * An application that documents only the operations of one profile still has its mapped operation
 * of that profile documented with the wire type, though the generated operations carry no profile.
 */
class MappedOpenApiProfileTest {

  @RegisterExtension
  static final QuarkusUnitTest APP = new QuarkusUnitTest()
    .withApplicationRoot(jar -> jar.addClasses(Orders.class).addPackage(Order.class.getPackage()))
    .overrideConfigKey("mp.openapi.extensions.smallrye.scan.profiles", "public");

  @Path("/orders")
  @Produces(MediaType.APPLICATION_JSON)
  public static class Orders {

    @GET
    @Path("/public")
    @Extension(name = "x-smallrye-profile-public", value = "")
    @TelescopeResponse(to = OrderDto.class)
    public Order published() {
      return new Order("SO-1", "Ada", 1200);
    }

    @GET
    @Path("/internal")
    @TelescopeResponse(to = OrderDto.class)
    public Order internal() {
      return new Order("SO-2", "Ada", 1200);
    }
  }

  @Test
  @DisplayName("a mapped operation of the profile the document keeps is documented as the wire type")
  void theProfilesOperationIsTheWireType() {
    final var document = given().accept(ContentType.JSON).get("/q/openapi").then().statusCode(200).extract().jsonPath();
    assertThat(
      document.getString("paths.'/orders/public'.get.responses.'200'.content.'application/json'.schema.'$ref'")
    ).isEqualTo("#/components/schemas/OrderDto");
    assertThat(document.getMap("paths")).doesNotContainKey("/orders/internal");
  }
}
