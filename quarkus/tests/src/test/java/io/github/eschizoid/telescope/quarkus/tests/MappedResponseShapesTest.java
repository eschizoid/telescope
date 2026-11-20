package io.github.eschizoid.telescope.quarkus.tests;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.eschizoid.telescope.quarkus.rest.TelescopeResponse;
import io.github.eschizoid.telescope.quarkus.tests.fixtures.Order;
import io.github.eschizoid.telescope.quarkus.tests.fixtures.OrderDto;
import io.quarkus.test.QuarkusUnitTest;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.jboss.resteasy.reactive.RestResponse;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Every return shape a mapped response supports, over HTTP: the body is the response type, written
 * by the JSON writer as if the method had declared it, while a Java caller of the method still
 * receives the domain type. The cost each {@link Order} carries never reaches the client.
 */
class MappedResponseShapesTest {

  @RegisterExtension
  static final QuarkusUnitTest APP = new QuarkusUnitTest().withApplicationRoot(jar ->
    jar.addClasses(
      Orders.class,
      Order.class,
      OrderDto.class,
      ApiError.class,
      Conflict.class,
      Errors.class,
      Inherited.class,
      InheritedImpl.class,
      Base.class,
      Concrete.class
    )
  );

  static final Order ADA = new Order("SO-1", "Ada", 1200);
  static final Order GRACE = new Order("SO-2", "Grace", 3400);

  /** The body an exception mapper writes: a list, as a mapped list endpoint's own body is. */
  public record ApiError(String code) {}

  /** Thrown by an endpoint to have the mapper below answer. */
  public static class Conflict extends RuntimeException {

    private static final long serialVersionUID = 1L;
  }

  public static class Errors {

    @ServerExceptionMapper
    public RestResponse<List<ApiError>> conflict(final Conflict conflict) {
      return RestResponse.status(RestResponse.Status.CONFLICT, List.of(new ApiError("C1")));
    }
  }

  /** A resource whose mapped method is declared on an interface. */
  @Path("/inherited")
  public interface Inherited {
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @TelescopeResponse(to = OrderDto.class)
    Order get();
  }

  public static class InheritedImpl implements Inherited {

    @Override
    public Order get() {
      return ADA;
    }
  }

  /** A resource whose mapped method is declared on an abstract superclass. */
  public abstract static class Base {

    @GET
    @Path("/order")
    @Produces(MediaType.APPLICATION_JSON)
    @TelescopeResponse(to = OrderDto.class)
    public Order order() {
      return GRACE;
    }
  }

  @Path("/concrete")
  public static class Concrete extends Base {}

  @Path("/orders")
  @Produces(MediaType.APPLICATION_JSON)
  public static class Orders {

    @GET
    @Path("/value")
    @TelescopeResponse(to = OrderDto.class)
    public Order value() {
      return ADA;
    }

    @GET
    @Path("/list")
    @TelescopeResponse(to = OrderDto.class)
    public List<Order> list() {
      return List.of(ADA, GRACE);
    }

    @GET
    @Path("/collection")
    @TelescopeResponse(to = OrderDto.class)
    public Collection<Order> collection() {
      return new ArrayList<>(List.of(GRACE, ADA));
    }

    @GET
    @Path("/set")
    @TelescopeResponse(to = OrderDto.class)
    public Set<Order> set() {
      final var orders = new LinkedHashSet<Order>();
      orders.add(GRACE);
      orders.add(ADA);
      return orders;
    }

    @GET
    @Path("/optional")
    @TelescopeResponse(to = OrderDto.class)
    public Optional<Order> optional() {
      return Optional.of(ADA);
    }

    @GET
    @Path("/rest-response")
    @TelescopeResponse(to = OrderDto.class)
    public RestResponse<Order> restResponse() {
      return RestResponse.ResponseBuilder.<Order>created(URI.create("/orders/SO-1"))
        .header("X-Order", "SO-1")
        .entity(ADA)
        .build();
    }

    @GET
    @Path("/rest-response-list")
    @TelescopeResponse(to = OrderDto.class)
    public RestResponse<List<Order>> restResponseList() {
      return RestResponse.ok(List.of(ADA, GRACE));
    }

    @GET
    @Path("/uni")
    @TelescopeResponse(to = OrderDto.class)
    public Uni<Order> uni() {
      return Uni.createFrom().item(GRACE);
    }

    @GET
    @Path("/uni-rest-response")
    @TelescopeResponse(to = OrderDto.class)
    public Uni<RestResponse<Order>> uniRestResponse() {
      return Uni.createFrom().item(RestResponse.accepted(ADA));
    }

    @GET
    @Path("/completion-stage")
    @TelescopeResponse(to = OrderDto.class)
    public CompletionStage<List<Order>> completionStage() {
      return CompletableFuture.completedFuture(List.of(GRACE));
    }

    @GET
    @Path("/missing")
    @TelescopeResponse(to = OrderDto.class)
    public Order missing() {
      throw new WebApplicationException(
        Response.status(Response.Status.NOT_FOUND).type(MediaType.TEXT_PLAIN).entity("no such order").build()
      );
    }

    @GET
    @Path("/conflict")
    @TelescopeResponse(to = OrderDto.class)
    public List<Order> conflict() {
      throw new Conflict();
    }

    @GET
    @Path("/nothing")
    @TelescopeResponse(to = OrderDto.class)
    public Order nothing() {
      return null;
    }
  }

  @Inject
  Orders orders;

  @Test
  @DisplayName("a single value is written as the response type")
  void aValueIsMapped() {
    final var json = given().get("/orders/value").then().statusCode(200).extract().jsonPath();
    assertThat(json.getString("number")).isEqualTo("SO-1");
    assertThat(json.getString("customer")).isEqualTo("Ada");
    assertThat(json.getMap("")).doesNotContainKey("costCents");
  }

  @Test
  @DisplayName("a list is written as a list of the response type, in order")
  void aListIsMapped() {
    final var body = given().get("/orders/list").then().statusCode(200).extract().asString();
    assertThat(body).isEqualTo(
      "[{\"number\":\"SO-1\",\"customer\":\"Ada\"},{\"number\":\"SO-2\",\"customer\":\"Grace\"}]"
    );
  }

  @Test
  @DisplayName("a collection is written as a list of the response type")
  void aCollectionIsMapped() {
    final var json = given().get("/orders/collection").then().statusCode(200).extract().jsonPath();
    assertThat(json.getList("number")).containsExactly("SO-2", "SO-1");
    assertThat(json.getString("[0].costCents")).isNull();
  }

  @Test
  @DisplayName("a set is written in its iteration order")
  void aSetIsMapped() {
    final var json = given().get("/orders/set").then().statusCode(200).extract().jsonPath();
    assertThat(json.getList("number")).containsExactly("SO-2", "SO-1");
    assertThat(json.getString("[0].costCents")).isNull();
  }

  @Test
  @DisplayName("an optional is written as the response type it holds")
  void anOptionalIsMapped() {
    final var body = given().get("/orders/optional").then().statusCode(200).extract().asString();
    assertThat(body).isEqualTo("{\"number\":\"SO-1\",\"customer\":\"Ada\"}");
  }

  @Test
  @DisplayName("a RestResponse keeps its status and headers while its body is mapped")
  void aRestResponseKeepsStatusAndHeaders() {
    final var response = given().get("/orders/rest-response").then().statusCode(201).extract();
    assertThat(response.header("Location")).endsWith("/orders/SO-1");
    assertThat(response.header("X-Order")).isEqualTo("SO-1");
    assertThat(response.asString()).isEqualTo("{\"number\":\"SO-1\",\"customer\":\"Ada\"}");
  }

  @Test
  @DisplayName("a RestResponse of a list is written as a list of the response type")
  void aRestResponseOfAListIsMapped() {
    final var json = given().get("/orders/rest-response-list").then().statusCode(200).extract().jsonPath();
    assertThat(json.getList("customer")).containsExactly("Ada", "Grace");
    assertThat(json.getString("[1].costCents")).isNull();
  }

  @Test
  @DisplayName("a Uni is mapped once it completes")
  void aUniIsMapped() {
    final var body = given().get("/orders/uni").then().statusCode(200).extract().asString();
    assertThat(body).isEqualTo("{\"number\":\"SO-2\",\"customer\":\"Grace\"}");
  }

  @Test
  @DisplayName("a Uni of a RestResponse keeps the status it completes with")
  void aUniOfARestResponseIsMapped() {
    final var body = given().get("/orders/uni-rest-response").then().statusCode(202).extract().asString();
    assertThat(body).isEqualTo("{\"number\":\"SO-1\",\"customer\":\"Ada\"}");
  }

  @Test
  @DisplayName("a CompletionStage of a list is mapped once it completes")
  void aCompletionStageIsMapped() {
    final var body = given().get("/orders/completion-stage").then().statusCode(200).extract().asString();
    assertThat(body).isEqualTo("[{\"number\":\"SO-2\",\"customer\":\"Grace\"}]");
  }

  @Test
  @DisplayName("the body an exception writes is left as it is")
  void anErrorBodyIsNotMapped() {
    final var body = given().get("/orders/missing").then().statusCode(404).extract().asString();
    assertThat(body).isEqualTo("no such order");
  }

  @Test
  @DisplayName("an exception mapper's list body on a list endpoint is written as the mapper wrote it")
  void anExceptionMappersListIsNotMapped() {
    final var body = given().get("/orders/conflict").then().statusCode(409).extract().asString();
    assertThat(body).isEqualTo("[{\"code\":\"C1\"}]");
  }

  @Test
  @DisplayName("a mapped method declared on the interface a resource implements is mapped")
  void anInterfaceMethodIsMapped() {
    final var body = given().get("/inherited").then().statusCode(200).extract().asString();
    assertThat(body).isEqualTo("{\"number\":\"SO-1\",\"customer\":\"Ada\"}");
  }

  @Test
  @DisplayName("a mapped method a resource inherits from its abstract superclass is mapped")
  void anInheritedMethodIsMapped() {
    final var body = given().get("/concrete/order").then().statusCode(200).extract().asString();
    assertThat(body).isEqualTo("{\"number\":\"SO-2\",\"customer\":\"Grace\"}");
  }

  // Controls: these hold whether or not the filter maps anything, and pin what the mapping must
  // leave alone, a body that is absent and the value a Java caller receives.

  @Test
  @DisplayName("control: a null return writes no body")
  void aNullReturnWritesNoBody() {
    given().get("/orders/nothing").then().statusCode(204);
  }

  @Test
  @DisplayName("control: a Java caller of the method still receives the domain type")
  void aJavaCallerReceivesTheDomainType() {
    assertThat(orders.value()).isEqualTo(ADA);
    assertThat(orders.list()).containsExactly(ADA, GRACE);
  }
}
