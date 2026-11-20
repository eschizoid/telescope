package io.github.eschizoid.telescope.quarkus.tests;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.eschizoid.telescope.quarkus.rest.TelescopeRequest;
import io.github.eschizoid.telescope.quarkus.rest.TelescopeRequestBody;
import io.github.eschizoid.telescope.quarkus.rest.TelescopeResponse;
import io.github.eschizoid.telescope.quarkus.tests.fixtures.Order;
import io.github.eschizoid.telescope.quarkus.tests.fixtures.OrderDto;
import io.github.eschizoid.telescope.quarkus.tests.fixtures.PlaceOrder;
import io.github.eschizoid.telescope.quarkus.tests.fixtures.PlaceOrderRequest;
import io.github.eschizoid.telescope.quarkus.tests.fixtures.SummaryProjection;
import io.github.eschizoid.telescope.quarkus.tests.openapi.Custom;
import io.github.eschizoid.telescope.quarkus.tests.openapi.Shipment;
import io.github.eschizoid.telescope.quarkus.tests.openapi.ShipmentDto;
import io.quarkus.test.QuarkusUnitTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.jboss.resteasy.reactive.RestResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * With SmallRye OpenAPI present, a mapped endpoint is documented with the type the client sees: the
 * success response with the response's wire type, the request body with the type the body is read
 * as, an array of it where the body is a list or a set. A method returning the domain type with no
 * mapping stays documented as SmallRye documents it.
 */
class MappedOpenApiTest {

  @RegisterExtension
  static final QuarkusUnitTest APP = new QuarkusUnitTest().withApplicationRoot(jar ->
    jar
      .addClasses(
        Orders.class,
        OrderApi.class,
        OrderApiResource.class,
        Shops.class,
        SecondShops.class,
        ShopOrders.class
      )
      .addPackage(Order.class.getPackage())
      .addPackage(Shipment.class.getPackage())
  );

  private static final String JSON = "content.'application/json'.schema";

  private static JsonPath document;

  @Path("/orders")
  @Produces(MediaType.APPLICATION_JSON)
  @Consumes(MediaType.APPLICATION_JSON)
  public static class Orders {

    @GET
    @Path("/{number}")
    @TelescopeResponse(to = OrderDto.class)
    public Order one(@PathParam("number") final String number) {
      return new Order(number, "Ada", 1200);
    }

    @GET
    @TelescopeResponse(to = OrderDto.class)
    public List<Order> all() {
      return List.of();
    }

    @GET
    @Path("/unique")
    @TelescopeResponse(to = OrderDto.class)
    public Set<Order> unique() {
      return Set.of();
    }

    @GET
    @Path("/later")
    @TelescopeResponse(to = OrderDto.class)
    public Uni<RestResponse<Order>> later() {
      return Uni.createFrom().item(RestResponse.ok(new Order("SO-1", "Ada", 1200)));
    }

    @GET
    @Path("/maybe")
    @TelescopeResponse(to = OrderDto.class)
    public Optional<Order> maybe() {
      return Optional.empty();
    }

    @GET
    @Path("/summary")
    @TelescopeResponse(using = SummaryProjection.class)
    public Order summary() {
      return new Order("SO-1", "Ada", 1200);
    }

    @POST
    @TelescopeResponse(to = OrderDto.class)
    public Order place(@TelescopeRequestBody(from = PlaceOrderRequest.class) final PlaceOrder command) {
      return new Order("SO-1", command.customer(), 1200);
    }

    @POST
    @Path("/batch")
    public List<PlaceOrder> batch(
      @TelescopeRequestBody(from = PlaceOrderRequest.class) final List<PlaceOrder> commands
    ) {
      return commands;
    }

    @POST
    @Path("/audited")
    public PlaceOrder audited(final TelescopeRequest<PlaceOrderRequest, PlaceOrder> request) {
      return request.mapped();
    }

    @GET
    @Path("/shipment")
    @TelescopeResponse(to = ShipmentDto.class)
    public Shipment shipment() {
      return new Shipment("SH-1", new Shipment.Address("Lisbon", "leave at the door"));
    }

    @GET
    @Path("/declared")
    @TelescopeResponse(to = OrderDto.class)
    @APIResponse(
      responseCode = "200",
      description = "Declared",
      content = @Content(schema = @Schema(implementation = Custom.class))
    )
    public Order declared() {
      return new Order("SO-1", "Ada", 1200);
    }

    @POST
    @Path("/declared")
    public PlaceOrder declaredBody(
      @RequestBody(content = @Content(schema = @Schema(implementation = Custom.class))) @TelescopeRequestBody(
        from = PlaceOrderRequest.class
      ) final PlaceOrder command
    ) {
      return command;
    }

    @POST
    @Path("/optional")
    public String optional(
      @TelescopeRequestBody(from = PlaceOrderRequest.class, required = false) final PlaceOrder command
    ) {
      return command == null ? "none" : command.customer();
    }

    @GET
    @Path("/raw")
    public Order raw() {
      return new Order("SO-1", "Ada", 1200);
    }
  }

  @Path("/api-orders")
  @Produces(MediaType.APPLICATION_JSON)
  public interface OrderApi {
    @GET
    @Path("/{number}")
    @TelescopeResponse(to = OrderDto.class)
    Order get(@PathParam("number") String number);
  }

  public static class OrderApiResource implements OrderApi {

    @Override
    public Order get(final String number) {
      return new Order(number, "Ada", 1200);
    }
  }

  @Path("/shops")
  public static class Shops {

    @Path("/{shop}")
    public ShopOrders shop(@PathParam("shop") final String shop) {
      return new ShopOrders();
    }
  }

  @Path("/second-shop")
  public static class SecondShops {

    @Path("/orders")
    public ShopOrders orders() {
      return new ShopOrders();
    }
  }

  @Produces(MediaType.APPLICATION_JSON)
  public static class ShopOrders {

    @GET
    @TelescopeResponse(to = OrderDto.class)
    public Order latest() {
      return new Order("SO-1", "Ada", 1200);
    }
  }

  // RestAssured is pointed at the application once each test starts, so the document is read in the
  // first test that needs it.
  private static JsonPath document() {
    if (document == null) document = given()
      .accept(ContentType.JSON)
      .get("/q/openapi")
      .then()
      .statusCode(200)
      .extract()
      .jsonPath();
    return document;
  }

  private static String response(final String path, final String method) {
    return "paths.'" + path + "'." + method + ".responses.'200'." + JSON;
  }

  private static String request(final String path) {
    return "paths.'" + path + "'.post.requestBody." + JSON;
  }

  @Test
  @DisplayName("a single response is documented as the wire type, and the wire type's schema is a component")
  void aSingleResponseIsTheWireType() {
    assertThat(document().getString(response("/orders/{number}", "get") + ".'$ref'")).isEqualTo(
      "#/components/schemas/OrderDto"
    );
    assertThat(document().getMap("components.schemas.OrderDto.properties")).containsOnlyKeys("number", "customer");
  }

  @Test
  @DisplayName("a list response is an array of the wire type, and a set one an array of unique items")
  void containersAreArraysOfTheWireType() {
    assertThat(document().getString(response("/orders", "get") + ".type")).isEqualTo("array");
    assertThat(document().getString(response("/orders", "get") + ".items.'$ref'")).isEqualTo(
      "#/components/schemas/OrderDto"
    );
    assertThat(document().getString(response("/orders/unique", "get") + ".type")).isEqualTo("array");
    assertThat(document().getBoolean(response("/orders/unique", "get") + ".uniqueItems")).isTrue();
    assertThat(document().getString(response("/orders/unique", "get") + ".items.'$ref'")).isEqualTo(
      "#/components/schemas/OrderDto"
    );
  }

  @Test
  @DisplayName("a Uni of a RestResponse and an Optional unwrap to the wire type")
  void wrappersUnwrapToTheWireType() {
    assertThat(document().getString(response("/orders/later", "get") + ".'$ref'")).isEqualTo(
      "#/components/schemas/OrderDto"
    );
    assertThat(document().getString(response("/orders/maybe", "get") + ".'$ref'")).isEqualTo(
      "#/components/schemas/OrderDto"
    );
  }

  @Test
  @DisplayName("a response a projection maps is documented as the projection's target")
  void aProjectionsTargetIsTheWireType() {
    assertThat(document().getString(response("/orders/summary", "get") + ".'$ref'")).isEqualTo(
      "#/components/schemas/Summary"
    );
  }

  @Test
  @DisplayName("a request body is documented as the type it is read as, beside its mapped response")
  void aRequestBodyIsTheWireType() {
    assertThat(document().getString(request("/orders") + ".'$ref'")).isEqualTo(
      "#/components/schemas/PlaceOrderRequest"
    );
    assertThat(document().getString(response("/orders", "post") + ".'$ref'")).isEqualTo(
      "#/components/schemas/OrderDto"
    );
    assertThat(document().getMap("components.schemas.PlaceOrderRequest.properties")).containsOnlyKeys(
      "buyer",
      "quantity",
      "clientRef"
    );
  }

  @Test
  @DisplayName("a list body is an array of the wire type, and a TelescopeRequest body its first type argument")
  void requestShapesAreTheWireType() {
    assertThat(document().getString(request("/orders/batch") + ".type")).isEqualTo("array");
    assertThat(document().getString(request("/orders/batch") + ".items.'$ref'")).isEqualTo(
      "#/components/schemas/PlaceOrderRequest"
    );
    assertThat(document().getString(request("/orders/audited") + ".'$ref'")).isEqualTo(
      "#/components/schemas/PlaceOrderRequest"
    );
  }

  @Test
  @DisplayName("a response no annotation maps stays documented as the type the method returns")
  void anUnmappedResponseIsLeftAlone() {
    assertThat(document().getString(response("/orders/raw", "get") + ".'$ref'")).isEqualTo(
      "#/components/schemas/Order"
    );
    assertThat(document().getString(response("/orders/batch", "post") + ".items.'$ref'")).isEqualTo(
      "#/components/schemas/PlaceOrder"
    );
  }

  @Test
  @DisplayName("a method declared on an interface, and one on a sub-resource a locator returns, are found by path")
  void interfacesAndSubResourcesAreMatched() {
    assertThat(document().getString(response("/api-orders/{number}", "get") + ".'$ref'")).isEqualTo(
      "#/components/schemas/OrderDto"
    );
    assertThat(document().getString(response("/shops/{shop}", "get") + ".'$ref'")).isEqualTo(
      "#/components/schemas/OrderDto"
    );
  }

  @Test
  @DisplayName("a record the wire type holds is added to the components with it")
  void aNestedWireTypeIsAComponent() {
    assertThat(document().getString(response("/orders/shipment", "get") + ".'$ref'")).isEqualTo(
      "#/components/schemas/ShipmentDto"
    );
    assertThat(document().getString("components.schemas.ShipmentDto.properties.address.'$ref'")).isEqualTo(
      "#/components/schemas/AddressDto"
    );
    assertThat(document().getMap("components.schemas.AddressDto.properties")).containsOnlyKeys("city");
  }

  @Test
  @DisplayName("a sub-resource two locators reach is documented under each of their paths")
  void everyLocatorsPathIsDocumented() {
    assertThat(document().getString(response("/shops/{shop}", "get") + ".'$ref'")).isEqualTo(
      "#/components/schemas/OrderDto"
    );
    assertThat(document().getString(response("/second-shop/orders", "get") + ".'$ref'")).isEqualTo(
      "#/components/schemas/OrderDto"
    );
  }

  @Test
  @DisplayName("a schema the method names itself, for its response or its body, is kept")
  void aDeclaredSchemaIsKept() {
    assertThat(document().getString(response("/orders/declared", "get") + ".'$ref'")).isEqualTo(
      "#/components/schemas/Custom"
    );
    assertThat(document().getString(request("/orders/declared") + ".'$ref'")).isEqualTo("#/components/schemas/Custom");
  }

  @Test
  @DisplayName("a body the method does not require is documented as not required, one it does as required")
  void anOptionalBodyIsNotRequired() {
    assertThat(document().getString(request("/orders/optional") + ".'$ref'")).isEqualTo(
      "#/components/schemas/PlaceOrderRequest"
    );
    assertThat(document().getBoolean("paths.'/orders/optional'.post.requestBody.required")).isNotEqualTo(true);
    assertThat(document().getBoolean("paths.'/orders'.post.requestBody.required")).isTrue();
  }
}
