package io.github.eschizoid.telescope.quarkus.tests;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.eschizoid.telescope.conversion.BridgeProvider;
import io.github.eschizoid.telescope.quarkus.rest.TelescopeRequestBody;
import io.github.eschizoid.telescope.quarkus.rest.TelescopeResponse;
import io.github.eschizoid.telescope.quarkus.tests.fixtures.Order;
import io.github.eschizoid.telescope.quarkus.tests.unpaired.Badge;
import io.github.eschizoid.telescope.quarkus.tests.unpaired.CityRecord;
import io.github.eschizoid.telescope.quarkus.tests.unpaired.CityView;
import io.github.eschizoid.telescope.quarkus.tests.unpaired.OrderCard;
import io.github.eschizoid.telescope.quarkus.tests.unpaired.Seal;
import io.github.eschizoid.telescope.quarkus.tests.unpaired.Stamp;
import io.github.eschizoid.telescope.quarkus.tests.unpaired.Unrelated;
import io.github.eschizoid.telescope.quarkus.tests.unpaired.elsewhere.BadgeBridgeProvider;
import io.quarkus.test.QuarkusUnitTest;
import io.restassured.http.ContentType;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The application boots with every body a mapper fills some of: a response and a request body where
 * some components pair by name and the others take their default, and three pairs that share no
 * name, mapped by the generated bridge beside the source, by a bridge a provider in another package
 * registers, and by a {@code Mapper} bean.
 */
class PartialPairTest {

  @RegisterExtension
  static final QuarkusUnitTest APP = new QuarkusUnitTest().withApplicationRoot(jar ->
    jar
      .addClasses(Orders.class, BadgeBridgeProvider.class)
      .addPackage(Unrelated.class.getPackage())
      .addPackage(Order.class.getPackage())
      .addAsServiceProvider(BridgeProvider.class, BadgeBridgeProvider.class)
  );

  @Path("/orders")
  @Produces(MediaType.APPLICATION_JSON)
  @Consumes(MediaType.APPLICATION_JSON)
  public static class Orders {

    @GET
    @Path("/card")
    @TelescopeResponse(to = OrderCard.class)
    public Order card() {
      return new Order("SO-1", "Ada", 1200);
    }

    @POST
    @Path("/from-card")
    public Order fromCard(@TelescopeRequestBody(from = OrderCard.class) final Order order) {
      return order;
    }

    @GET
    @Path("/stamp")
    @TelescopeResponse(to = Unrelated.class)
    public Stamp stamp() {
      return new Stamp("ST-42");
    }

    @GET
    @Path("/badge")
    @TelescopeResponse(to = Unrelated.class)
    public Badge badge() {
      return new Badge("gold");
    }

    @GET
    @Path("/city")
    @TelescopeResponse(to = CityRecord.class)
    public CityView city() {
      return () -> "Kyiv";
    }

    @GET
    @Path("/seal")
    @TelescopeResponse(to = Unrelated.class)
    public Seal seal() {
      return new Seal("wax", 3);
    }
  }

  @Test
  @DisplayName("a response some of whose components pair by name leaves the others at their default")
  void aPartialResponseBoots() {
    final var body = given().get("/orders/card").then().statusCode(200).extract().asString();
    assertThat(body).isEqualTo("{\"number\":\"SO-1\",\"nickname\":null}");
  }

  @Test
  @DisplayName("a request body some of whose components pair by name leaves the others at their default")
  void aPartialRequestBodyBoots() {
    final var body = given()
      .contentType(ContentType.JSON)
      .body("{\"number\":\"SO-2\",\"nickname\":\"rush\"}")
      .post("/orders/from-card")
      .then()
      .statusCode(200)
      .extract()
      .asString();
    assertThat(body).isEqualTo("{\"number\":\"SO-2\",\"customer\":null,\"costCents\":0}");
  }

  @Test
  @DisplayName("a pair that shares no name maps through the generated bridge beside its source")
  void aBridgedPairBoots() {
    final var body = given().get("/orders/stamp").then().statusCode(200).extract().asString();
    assertThat(body).isEqualTo("{\"zzz\":\"ST-42\",\"yyy\":5}");
  }

  @Test
  @DisplayName("a pair that shares no name maps through the bridge a provider registers for it")
  void aProvidedBridgeBoots() {
    final var body = given().get("/orders/badge").then().statusCode(200).extract().asString();
    assertThat(body).isEqualTo("{\"zzz\":\"gold\",\"yyy\":0}");
  }

  @Test
  @DisplayName("a pair that shares no name maps through the Mapper bean registered for it")
  void aMapperBeanBoots() {
    final var body = given().get("/orders/seal").then().statusCode(200).extract().asString();
    assertThat(body).isEqualTo("{\"zzz\":\"wax\",\"yyy\":3}");
  }

  @Test
  @DisplayName("an interface source one of whose getters pairs by name maps through it")
  void anInterfaceSourceBoots() {
    final var body = given().get("/orders/city").then().statusCode(200).extract().asString();
    assertThat(body).isEqualTo("{\"city\":\"Kyiv\"}");
  }
}
