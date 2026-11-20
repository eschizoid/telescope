package io.github.eschizoid.telescope.quarkus.tests;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.eschizoid.telescope.quarkus.rest.TelescopeRequestBody;
import io.github.eschizoid.telescope.quarkus.rest.TelescopeResponse;
import io.github.eschizoid.telescope.quarkus.tests.fixtures.Memo;
import io.github.eschizoid.telescope.quarkus.tests.fixtures.NoteProjection;
import io.github.eschizoid.telescope.quarkus.tests.fixtures.Order;
import io.github.eschizoid.telescope.quarkus.tests.fixtures.OrderDto;
import io.github.eschizoid.telescope.quarkus.tests.fixtures.Receipt;
import io.github.eschizoid.telescope.quarkus.tests.fixtures.Summary;
import io.github.eschizoid.telescope.quarkus.tests.fixtures.SummaryProjection;
import io.quarkus.test.QuarkusUnitTest;
import io.restassured.http.ContentType;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Each place a mapped body's mapper comes from, told apart by what it writes: the projection for
 * {@code Order -> Summary} upper-cases the customer, the {@code Mapper} bean for {@code Order ->
 * Receipt} renames it to buyer, and the same-name forward mapping for {@code Order -> OrderDto}
 * copies it as it is.
 */
class MapperLookupTest {

  @RegisterExtension
  static final QuarkusUnitTest APP = new QuarkusUnitTest().withApplicationRoot(jar ->
    jar.addClasses(Orders.class).addPackage(Order.class.getPackage())
  );

  static final Order ADA = new Order("SO-1", "Ada", 1200);

  @Path("/lookup")
  @Produces(MediaType.APPLICATION_JSON)
  public static class Orders {

    @GET
    @Path("/projection")
    @TelescopeResponse(to = Summary.class)
    public Order projection() {
      return ADA;
    }

    @GET
    @Path("/using")
    @TelescopeResponse(using = SummaryProjection.class)
    public Order using() {
      return ADA;
    }

    @GET
    @Path("/mapper")
    @TelescopeResponse(to = Receipt.class)
    public Order mapper() {
      return ADA;
    }

    @GET
    @Path("/forward")
    @TelescopeResponse(to = OrderDto.class)
    public Order forward() {
      return ADA;
    }

    @POST
    @Path("/memo")
    @Consumes(MediaType.APPLICATION_JSON)
    public Memo memo(@TelescopeRequestBody(using = NoteProjection.class) final Memo memo) {
      return memo;
    }
  }

  @Test
  @DisplayName("the one projection bean for the pair maps it")
  void theProjectionMapsThePair() {
    final var json = given().get("/lookup/projection").then().statusCode(200).extract().jsonPath();
    assertThat(json.getString("customer")).isEqualTo("ADA");
  }

  @Test
  @DisplayName("using names the projection and supplies the response type")
  void usingSuppliesTheTarget() {
    final var json = given().get("/lookup/using").then().statusCode(200).extract().jsonPath();
    assertThat(json.getString("customer")).isEqualTo("ADA");
    assertThat(json.getLong("costCents")).isEqualTo(1200);
  }

  @Test
  @DisplayName("the Mapper bean the registry holds for the pair maps it")
  void theRegisteredMapperMapsThePair() {
    final var json = given().get("/lookup/mapper").then().statusCode(200).extract().jsonPath();
    assertThat(json.getString("buyer")).isEqualTo("Ada");
  }

  @Test
  @DisplayName("with no bean for the pair, the same-name forward mapping maps it")
  void theForwardMappingMapsThePair() {
    final var body = given().get("/lookup/forward").then().statusCode(200).extract().asString();
    assertThat(body).isEqualTo("{\"number\":\"SO-1\",\"customer\":\"Ada\"}");
  }

  @Test
  @DisplayName("using on a request body supplies the wire type from the projection")
  void usingSuppliesTheWireType() {
    final var body = given()
      .contentType(ContentType.JSON)
      .body(Map.of("text", "call back"))
      .post("/lookup/memo")
      .then()
      .statusCode(200)
      .extract()
      .asString();
    assertThat(body).isEqualTo("{\"text\":\"call back\"}");
  }
}
