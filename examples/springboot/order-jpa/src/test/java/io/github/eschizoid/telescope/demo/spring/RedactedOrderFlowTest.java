package io.github.eschizoid.telescope.demo.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.eschizoid.telescope.demo.spring.domain.Order;
import io.github.eschizoid.telescope.demo.spring.domain.RedactedOrder;
import io.github.eschizoid.telescope.demo.spring.mapping.RedactedOrderTelescopes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * Bughunt slice — exercises {@link RedactedOrderTelescopes#REDACT REDACT}, a hand-rolled lossy /
 * unidirectional {@code Telescope<Order, RedactedOrder>} built via {@code
 * Telescope.from(...).to(...).using(forward, backward)}.
 *
 * <p>Three angles:
 *
 * <ol>
 *   <li>HTTP — POST a full order to {@code /orders/runtime}, GET {@code /orders/{id}/redacted}, and
 *       assert Spring serialised the {@link RedactedOrder} record cleanly with the redaction
 *       applied to email + city.
 *   <li>In-process forward — call {@code REDACT.read(order)} directly to assert the redaction logic
 *       without HTTP framing.
 *   <li>Backward refusal — the lossy direction throws {@link UnsupportedOperationException} (we
 *       drive it via {@code REDACT.update(...)} since {@code Iso.reverse().read} is not exposed on
 *       the public surface; any write call on the telescope rebuilds via the {@code backward}
 *       function and therefore propagates the explicit refusal).
 * </ol>
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class RedactedOrderFlowTest {

  @LocalServerPort
  private int port;

  private RestClient client;

  @BeforeEach
  void setUp() {
    this.client = RestClient.create("http://localhost:" + port);
  }

  @Test
  void redactedEndpointReturnsObfuscatedProjection() {
    final var created = client
      .post()
      .uri("/orders/runtime")
      .contentType(MediaType.APPLICATION_JSON)
      .body(OrderFixtures.sampleOrder())
      .retrieve()
      .body(Order.class);
    assertThat(created).isNotNull();
    final var id = created.id();

    final var redacted = client.get().uri("/orders/" + id + "/redacted").retrieve().body(RedactedOrder.class);

    assertThat(redacted).isNotNull();
    assertThat(redacted.id()).isEqualTo(id);
    assertThat(redacted.orderNumber()).isEqualTo("ORD-2026-0001");
    // Email is xxx@<original-domain>; original was lowercased to alice@example.com by the runtime
    // controller.
    assertThat(redacted.redactedCustomerEmail()).isEqualTo("xxx@example.com");
    // City "Brooklyn" → "B***".
    assertThat(redacted.redactedShippingCity()).isEqualTo("B***");

    // Raw JSON — confirm Spring/Jackson serialises every record component (no extra customer/
    // shippingAddress/lineItems leaking through, which would mean we accidentally returned an
    // Order).
    final var raw = client.get().uri("/orders/" + id + "/redacted").retrieve().body(JsonNode.class);
    assertThat(raw).isNotNull();
    assertThat(raw.has("id")).isTrue();
    assertThat(raw.has("orderNumber")).isTrue();
    assertThat(raw.has("redactedCustomerEmail")).isTrue();
    assertThat(raw.has("redactedShippingCity")).isTrue();
    assertThat(raw.has("customer")).isFalse();
    assertThat(raw.has("lineItems")).isFalse();
    assertThat(raw.has("billingAddress")).isFalse();
  }

  @Test
  void forwardReadObfuscatesEmailAndCity() {
    final var order = OrderFixtures.sampleOrder();
    final var redacted = RedactedOrderTelescopes.REDACT.read(order);

    assertThat(redacted).isNotNull();
    assertThat(redacted.orderNumber()).isEqualTo("ORD-2026-0001");
    // Original (pre-controller) email is "ALICE@example.com" — keep the domain, replace local part.
    assertThat(redacted.redactedCustomerEmail()).isEqualTo("xxx@example.com");
    assertThat(redacted.redactedShippingCity()).isEqualTo("B***");
  }

  @Test
  void backwardDirectionRefusesToInvert() {
    final var order = OrderFixtures.sampleOrder();
    // Any write through this telescope must exercise the backward function. update(...) does so:
    // forward → fn → backward. Identity fn isolates the backward-throw.
    assertThatThrownBy(() -> RedactedOrderTelescopes.REDACT.update(order, redacted -> redacted))
      .isInstanceOf(UnsupportedOperationException.class)
      .hasMessage(RedactedOrderTelescopes.BACKWARD_MESSAGE);
  }
}
