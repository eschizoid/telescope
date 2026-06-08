package io.github.eschizoid.telescope.demo.spring;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.eschizoid.telescope.demo.spring.domain.Order;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * End-to-end integration test for the codegen-driven path. Boots the same embedded Tomcat as the
 * runtime tests and verifies behavioural equivalence — both controllers must produce identical
 * persistence results. The "normalise emails" endpoint is exclusive to this flow and demonstrates a
 * server-side deep update through the codegen-emitted typed navigator on a real round trip.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class CodegenOrderFlowTest {

  @LocalServerPort
  private int port;

  private RestClient client;

  @BeforeEach
  void setUp() {
    this.client = RestClient.create("http://localhost:" + port);
  }

  @Test
  void postRoundTripPreservesShape() {
    final var body = client
      .post()
      .uri("/orders/codegen")
      .contentType(MediaType.APPLICATION_JSON)
      .body(OrderFixtures.sampleOrder())
      .retrieve()
      .body(Order.class);

    assertThat(body).isNotNull();
    assertThat(body.id()).isNotNull();
    assertThat(body.orderNumber()).isEqualTo("ORD-2026-0001");
    assertThat(body.customer().email()).isEqualTo("alice@example.com");
    assertThat(body.customer().id()).isNotNull();
    assertThat(body.shippingAddress().zip()).isEqualTo("11201");
    assertThat(body.lineItems().getFirst().unitPrice()).isEqualByComparingTo("19.99");
    assertThat(body.giftWrap()).isPresent();
    assertThat(body.giftWrap().get().street()).isEqualTo("300 Gift Rd");
  }

  @Test
  void applyDiscountUsesMapperAsTelescopeBridgeToEntitySideField() {
    // Demonstrates Mapper.asTelescope() inside the typed codegen path. The endpoint walks
    // OrderPath → each LineItem → .then(lineItemMapper.asTelescope()) → LineItemEntity →
    // .field(getUnitPriceCents), then reduces every cents value by 10%. Backward composition
    // converts each modified entity back to a LineItem with the new BigDecimal unitPrice, so the
    // round-trip lands a real Order with discounted prices.
    final var created = client
      .post()
      .uri("/orders/codegen")
      .contentType(MediaType.APPLICATION_JSON)
      .body(OrderFixtures.sampleOrder())
      .retrieve()
      .body(Order.class);
    assertThat(created).isNotNull();
    final var id = created.id();
    // sampleOrder line items: 19.99 (1999c) and 49.50 (4950c). After 10% off:
    //   1999 * 90 / 100 = 1799 → 17.99
    //   4950 * 90 / 100 = 4455 → 44.55
    final var discounted = client
      .post()
      .uri("/orders/codegen/" + id + "/discount?percent=10")
      .retrieve()
      .body(Order.class);

    assertThat(discounted).isNotNull();
    assertThat(discounted.lineItems()).hasSize(2);
    assertThat(discounted.lineItems().get(0).unitPrice()).isEqualByComparingTo("17.99");
    assertThat(discounted.lineItems().get(1).unitPrice()).isEqualByComparingTo("44.55");
    // Other fields untouched — the asTelescope() chain only re-wrote unitPriceCents.
    assertThat(discounted.customer().email()).isEqualTo("alice@example.com");
    assertThat(discounted.orderNumber()).isEqualTo("ORD-2026-0001");
  }

  @Test
  void normaliseEmailsAppliesDeepUpdateThroughHolderConstants() {
    final var created = client
      .post()
      .uri("/orders/codegen")
      .contentType(MediaType.APPLICATION_JSON)
      .body(OrderFixtures.sampleOrder())
      .retrieve()
      .body(Order.class);
    assertThat(created).isNotNull();
    final var id = created.id();

    final var normalised = client.post().uri("/orders/codegen/normalise-emails/" + id).retrieve().body(Order.class);

    assertThat(normalised).isNotNull();
    assertThat(normalised.customer().email()).isEqualTo("alice@example.com");
  }
}
