package io.github.eschizoid.telescope.demo.spring;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.eschizoid.telescope.demo.spring.domain.Order;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * End-to-end integration test for the runtime-resolution path. Boots a real embedded Tomcat on a
 * random port and drives HTTP requests through Spring 7's {@link RestClient}. Pins:
 *
 * <ol>
 *   <li><b>POST round-trip preserves shape.</b> JSON → record → entity → DB → entity → record →
 *       JSON returns equal nested values (modulo Hibernate-assigned ids).
 *   <li><b>Pre-write normalisation hits a nested field.</b> The controller lowercases {@code
 *       customer.email} via a single {@code
 *       Telescope.of(Order.class).field(...).field(...).update(...)} call before persistence.
 *   <li><b>PATCH overlays only non-null fields.</b> Sending {@code {"orderNumber":"ORD-PATCHED"}}
 *       changes only the order number; customer, addresses, line items stay as the previous POST
 *       left them. Demonstrates {@code mapper.patch(existing, partial)}.
 * </ol>
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class OrderFlowTest {

  @LocalServerPort
  private int port;

  private RestClient client;

  @BeforeEach
  void setUp() {
    this.client = RestClient.create("http://localhost:" + port);
  }

  @Test
  void postRoundTripPreservesShapeAndLowercasesEmail() {
    final var body = client
      .post()
      .uri("/orders")
      .contentType(MediaType.APPLICATION_JSON)
      .body(OrderFixtures.sampleOrder())
      .retrieve()
      .body(Order.class);

    assertThat(body).isNotNull();
    assertThat(body.id()).isNotNull();
    assertThat(body.orderNumber()).isEqualTo("ORD-2026-0001");
    // Email lowercased server-side by the controller's pre-write normalisation.
    assertThat(body.customer().email()).isEqualTo("alice@example.com");
    assertThat(body.customer().id()).isNotNull();
    assertThat(body.shippingAddress().city()).isEqualTo("Brooklyn");
    assertThat(body.billingAddress().city()).isEqualTo("Brooklyn");
    assertThat(body.lineItems()).hasSize(2);
    assertThat(body.lineItems().get(0).sku()).isEqualTo("SKU-A");
    assertThat(body.lineItems().get(0).quantity()).isEqualTo(2);
    assertThat(body.lineItems().get(0).unitPrice()).isEqualByComparingTo("19.99");
    assertThat(body.lineItems().get(1).unitPrice()).isEqualByComparingTo("49.50");
    assertThat(body.giftWrap()).isPresent();
    assertThat(body.giftWrap().get().street()).isEqualTo("300 Gift Rd");
  }

  @Test
  void patchOnlyOverlaysProvidedFields() {
    final var created = client
      .post()
      .uri("/orders")
      .contentType(MediaType.APPLICATION_JSON)
      .body(OrderFixtures.sampleOrder())
      .retrieve()
      .body(Order.class);
    assertThat(created).isNotNull();
    final var id = created.id();

    final var patched = client
      .patch()
      .uri("/orders/" + id)
      .contentType(MediaType.APPLICATION_JSON)
      .body(OrderFixtures.patchOrderNumberOnly("ORD-PATCHED"))
      .retrieve()
      .body(Order.class);

    assertThat(patched).isNotNull();
    assertThat(patched.orderNumber()).isEqualTo("ORD-PATCHED");
    assertThat(patched.customer().email()).isEqualTo("alice@example.com");
    assertThat(patched.shippingAddress().city()).isEqualTo("Brooklyn");
  }

  @Test
  void bulkCreateUsesMapperLiftList() {
    // Demonstrates Mapper.liftList — the controller endpoint promotes the element-level
    // Mapper<Order, OrderEntity> to a Mapper<List<Order>, List<OrderEntity>> in one call,
    // then drives a single forward/save/backward through the lifted mapper.
    final var second = new Order(
      null,
      "ORD-2026-0002",
      OrderFixtures.sampleOrder().customer(),
      OrderFixtures.sampleOrder().shippingAddress(),
      OrderFixtures.sampleOrder().billingAddress(),
      OrderFixtures.sampleOrder().lineItems(),
      OrderFixtures.sampleOrder().giftWrap(),
      OrderFixtures.sampleOrder().metadata(),
      OrderFixtures.samplePayment()
    );
    final var bulk = client
      .post()
      .uri("/orders/bulk")
      .contentType(MediaType.APPLICATION_JSON)
      .body(List.of(OrderFixtures.sampleOrder(), second))
      .retrieve()
      .body(new ParameterizedTypeReference<List<Order>>() {});

    assertThat(bulk).isNotNull().hasSize(2);
    assertThat(bulk.get(0).orderNumber()).isEqualTo("ORD-2026-0001");
    assertThat(bulk.get(1).orderNumber()).isEqualTo("ORD-2026-0002");
    assertThat(bulk).allSatisfy(o -> {
      assertThat(o.id()).isNotNull();
      assertThat(o.lineItems()).hasSize(2);
    });
  }

  @Test
  void getReturns404ForUnknownId() {
    try {
      client.get().uri("/orders/999999").retrieve().body(Order.class);
      throw new AssertionError("expected 404");
    } catch (final HttpClientErrorException e) {
      assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
  }
}
