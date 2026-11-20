package io.github.eschizoid.telescope.quarkus.tests;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.eschizoid.telescope.conversion.BridgeProvider;
import io.github.eschizoid.telescope.quarkus.rest.TelescopeRequest;
import io.github.eschizoid.telescope.quarkus.rest.TelescopeRequestBody;
import io.github.eschizoid.telescope.quarkus.tests.fixtures.Order;
import io.github.eschizoid.telescope.quarkus.tests.fixtures.PlaceOrder;
import io.github.eschizoid.telescope.quarkus.tests.fixtures.PlaceOrderRequest;
import io.quarkus.test.QuarkusUnitTest;
import io.restassured.http.ContentType;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Mapped request bodies over HTTP: each body is read and validated as {@link PlaceOrderRequest},
 * the type a client sends, which names the customer {@code buyer}, and the method receives the
 * {@link PlaceOrder} it works with. Each method echoes what it received, so a response shows what
 * the mapping handed it. The wire type's mapping is a {@code @Bridge} field's, so each body maps
 * through the bridge it generated, and the startup log says the bridge's provider is registered.
 */
class MappedRequestBodyTest {

  @RegisterExtension
  static final QuarkusUnitTest APP = new QuarkusUnitTest()
    .withApplicationRoot(jar ->
      jar
        .addClasses(Orders.class)
        .addPackage(Order.class.getPackage())
        .addAsResource("META-INF/services/" + BridgeProvider.class.getName())
    )
    .overrideConfigKey("quarkus.log.category.\"io.github.eschizoid.telescope.quarkus.rest\".level", "DEBUG")
    .setLogRecordPredicate(record -> record.getLoggerName().startsWith("io.github.eschizoid.telescope.quarkus.rest"))
    .assertLogRecords(records ->
      assertThat(records)
        .extracting(LogRecord::getMessage)
        .contains(
          "Orders#place(command) maps PlaceOrderRequest -> PlaceOrder through Telescope.mapperForward, with a" +
            " generated bridge's provider registered for the pair"
        )
    );

  @Path("/orders")
  @Produces(MediaType.APPLICATION_JSON)
  @Consumes(MediaType.APPLICATION_JSON)
  public static class Orders {

    @POST
    public PlaceOrder place(@TelescopeRequestBody(from = PlaceOrderRequest.class) @Valid final PlaceOrder command) {
      return command;
    }

    @POST
    @Path("/unvalidated")
    public PlaceOrder unvalidated(@TelescopeRequestBody(from = PlaceOrderRequest.class) final PlaceOrder command) {
      return command;
    }

    @POST
    @Path("/batch")
    public List<PlaceOrder> batch(
      @TelescopeRequestBody(from = PlaceOrderRequest.class) @Valid final List<PlaceOrder> commands
    ) {
      return commands;
    }

    @POST
    @Path("/distinct")
    public Set<PlaceOrder> distinct(
      @TelescopeRequestBody(from = PlaceOrderRequest.class) final Set<PlaceOrder> commands
    ) {
      return commands;
    }

    @POST
    @Path("/traced")
    public Map<String, Object> traced(@Valid final TelescopeRequest<PlaceOrderRequest, PlaceOrder> request) {
      return Map.of("clientRef", request.original().clientRef(), "mapped", request.mapped());
    }

    @POST
    @Path("/maybe")
    @Produces(MediaType.TEXT_PLAIN)
    public String maybe(
      @TelescopeRequestBody(from = PlaceOrderRequest.class, required = false) final PlaceOrder command
    ) {
      return command == null ? "none" : command.customer();
    }

    @POST
    @Path("/plain")
    public PlaceOrder plain(final PlaceOrder command) {
      return command;
    }
  }

  private static final Map<String, Object> ADA = Map.of("buyer", "Ada", "quantity", 2, "clientRef", "web-7");

  @Test
  @DisplayName("the method receives the body mapped from the wire type")
  void aBodyIsMapped() {
    final var body = given().contentType(ContentType.JSON).body(ADA).post("/orders").then().statusCode(200);
    assertThat(body.extract().asString()).isEqualTo("{\"customer\":\"Ada\",\"quantity\":2}");
  }

  @Test
  @DisplayName("a body that breaks the wire type's constraints is a 400 naming its fields, before it is mapped")
  void anInvalidBodyIsRefused() {
    final var body = given()
      .contentType(ContentType.JSON)
      .body(Map.of("buyer", " ", "quantity", 0))
      .post("/orders")
      .then()
      .statusCode(400)
      .extract()
      .jsonPath();
    assertThat(body.getList("violations.field")).containsExactlyInAnyOrder(
      "place.command.buyer",
      "place.command.quantity"
    );
  }

  @Test
  @DisplayName("a body whose parameter carries no @Valid is mapped without being validated")
  void anUnvalidatedBodyIsMapped() {
    final var body = given()
      .contentType(ContentType.JSON)
      .body(Map.of("buyer", " ", "quantity", 0))
      .post("/orders/unvalidated")
      .then()
      .statusCode(200)
      .extract()
      .asString();
    assertThat(body).isEqualTo("{\"customer\":\" \",\"quantity\":0}");
  }

  @Test
  @DisplayName("each element of a list body is mapped, in order")
  void aListBodyIsMapped() {
    final var json = given()
      .contentType(ContentType.JSON)
      .body(List.of(ADA, Map.of("buyer", "Grace", "quantity", 1)))
      .post("/orders/batch")
      .then()
      .statusCode(200)
      .extract()
      .jsonPath();
    assertThat(json.getList("customer")).containsExactly("Ada", "Grace");
    assertThat(json.getString("[0].clientRef")).isNull();
  }

  @Test
  @DisplayName("each element of a list body is validated as read")
  void anInvalidElementIsRefused() {
    final var body = given()
      .contentType(ContentType.JSON)
      .body(List.of(ADA, Map.of("buyer", "", "quantity", 1)))
      .post("/orders/batch")
      .then()
      .statusCode(400)
      .extract()
      .jsonPath();
    assertThat(body.getList("violations.field")).containsExactly("batch.commands[1].buyer");
  }

  @Test
  @DisplayName("a set body is read as a set of the wire type and mapped")
  void aSetBodyIsMapped() {
    final var json = given()
      .contentType(ContentType.JSON)
      .body(List.of(ADA, ADA))
      .post("/orders/distinct")
      .then()
      .statusCode(200)
      .extract()
      .jsonPath();
    assertThat(json.getList("customer")).containsExactly("Ada");
  }

  @Test
  @DisplayName("a TelescopeRequest carries the body as sent and as mapped")
  void aTelescopeRequestCarriesBoth() {
    final var json = given()
      .contentType(ContentType.JSON)
      .body(ADA)
      .post("/orders/traced")
      .then()
      .statusCode(200)
      .extract()
      .jsonPath();
    assertThat(json.getString("clientRef")).isEqualTo("web-7");
    assertThat(json.getString("mapped.customer")).isEqualTo("Ada");
  }

  @Test
  @DisplayName("a TelescopeRequest with @Valid validates the body as sent")
  void anInvalidTelescopeRequestIsRefused() {
    final var body = given()
      .contentType(ContentType.JSON)
      .body(Map.of("buyer", "Ada", "quantity", -1))
      .post("/orders/traced")
      .then()
      .statusCode(400)
      .extract()
      .jsonPath();
    assertThat(body.getList("violations.field")).containsExactly("traced.request.original.quantity");
  }

  @Test
  @DisplayName("a missing required body is a 400")
  void aMissingRequiredBodyIsRefused() {
    given().contentType(ContentType.JSON).post("/orders").then().statusCode(400);
  }

  @Test
  @DisplayName("a missing optional body reaches the method as null")
  void aMissingOptionalBodyIsNull() {
    final var body = given().contentType(ContentType.JSON).post("/orders/maybe").then().statusCode(200);
    assertThat(body.extract().asString()).isEqualTo("none");
  }

  @Test
  @DisplayName("a body no annotation names is read as its parameter's type, as before")
  void anUnmappedBodyIsReadAsDeclared() {
    final var body = given()
      .contentType(ContentType.JSON)
      .body(Map.of("customer", "Grace", "quantity", 3))
      .post("/orders/plain")
      .then()
      .statusCode(200)
      .extract()
      .asString();
    assertThat(body).isEqualTo("{\"customer\":\"Grace\",\"quantity\":3}");
  }
}
