package io.github.eschizoid.telescope.quarkus.tests;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.eschizoid.telescope.quarkus.rest.TelescopeRequest;
import io.github.eschizoid.telescope.quarkus.tests.fixtures.Order;
import io.github.eschizoid.telescope.quarkus.tests.fixtures.PlaceOrder;
import io.github.eschizoid.telescope.quarkus.tests.fixtures.PlaceOrderRequest;
import io.quarkus.test.QuarkusUnitTest;
import io.restassured.http.ContentType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * A {@code TelescopeRequest} handed on to code Quarkus REST does not route: a private helper, a
 * bean method and a lambda each take one as a plain value, and only the resource method's parameter
 * is read as a body.
 */
class RequestHelperTest {

  @RegisterExtension
  static final QuarkusUnitTest APP = new QuarkusUnitTest().withApplicationRoot(jar ->
    jar.addClasses(Orders.class, Audit.class).addPackage(Order.class.getPackage())
  );

  @ApplicationScoped
  public static class Audit {

    public String line(final TelescopeRequest<PlaceOrderRequest, PlaceOrder> request) {
      return request.original().clientRef() + "/" + request.mapped().customer();
    }
  }

  @Path("/orders")
  @Produces(MediaType.TEXT_PLAIN)
  @Consumes(MediaType.APPLICATION_JSON)
  public static class Orders {

    @Inject
    Audit audit;

    @POST
    public String place(final TelescopeRequest<PlaceOrderRequest, PlaceOrder> request) {
      final Function<TelescopeRequest<PlaceOrderRequest, PlaceOrder>, String> quantity = held ->
        String.valueOf(held.mapped().quantity());
      return summary(request) + " " + audit.line(request) + " " + quantity.apply(request);
    }

    private String summary(final TelescopeRequest<PlaceOrderRequest, PlaceOrder> request) {
      return request.mapped().customer();
    }
  }

  @Test
  @DisplayName("a helper, a bean method and a lambda that take a TelescopeRequest leave the build alone")
  void onlyTheRoutedParameterIsABody() {
    final var body = given()
      .contentType(ContentType.JSON)
      .body(Map.of("buyer", "Ada", "quantity", 2, "clientRef", "web-7"))
      .post("/orders")
      .then()
      .statusCode(200)
      .extract()
      .asString();
    assertThat(body).isEqualTo("Ada web-7/Ada 2");
  }
}
