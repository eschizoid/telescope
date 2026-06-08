package io.github.eschizoid.telescope.demo.spring;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.eschizoid.telescope.demo.spring.domain.Order;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
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
 *   <li><b>Pre-write normalisation hits a nested field.</b> The controller lowercases
 *       {@code customer.email} via a single {@code Telescope.of(Order.class).field(...).field(...).update(...)}
 *       call before persistence.
 *   <li><b>PATCH overlays only non-null fields.</b> Sending {@code {"orderNumber":"ORD-PATCHED"}}
 *       changes only the order number; customer, addresses, line items stay as the previous POST
 *       left them. Demonstrates {@code mapper.patch(existing, partial)}.
 * </ol>
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class RuntimeOrderFlowTest {

  @LocalServerPort private int port;

  private RestClient client;

  @BeforeEach
  void setUp() {
    this.client = RestClient.create("http://localhost:" + port);
  }

  @Test
  void postRoundTripPreservesShapeAndLowercasesEmail() {
    final var body = client
      .post()
      .uri("/orders/runtime")
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
    assertThat(body.lineItems().getFirst().sku()).isEqualTo("SKU-A");
    assertThat(body.lineItems().getFirst().quantity()).isEqualTo(2);
    assertThat(body.lineItems().getFirst().unitPrice()).isEqualByComparingTo("19.99");
    assertThat(body.lineItems().get(1).unitPrice()).isEqualByComparingTo("49.50");
    assertThat(body.giftWrap()).isPresent();
    assertThat(body.giftWrap().get().street()).isEqualTo("300 Gift Rd");
  }

  @Test
  void patchOnlyOverlaysProvidedFields() {
    final var created = client
      .post()
      .uri("/orders/runtime")
      .contentType(MediaType.APPLICATION_JSON)
      .body(OrderFixtures.sampleOrder())
      .retrieve()
      .body(Order.class);
    assertThat(created).isNotNull();
    final var id = created.id();

    final var patched = client
      .patch()
      .uri("/orders/runtime/" + id)
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
  void getReturns404ForUnknownId() {
    try {
      client.get().uri("/orders/runtime/999999").retrieve().body(Order.class);
      throw new AssertionError("expected 404");
    } catch (final HttpClientErrorException e) {
      assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
  }
}
