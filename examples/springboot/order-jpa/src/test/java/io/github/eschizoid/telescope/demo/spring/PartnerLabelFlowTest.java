package io.github.eschizoid.telescope.demo.spring;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.eschizoid.telescope.demo.spring.domain.Order;
import io.github.eschizoid.telescope.demo.spring.partner.PartnerAddress;
import io.github.eschizoid.telescope.demo.spring.partner.PartnerCustomer;
import io.github.eschizoid.telescope.demo.spring.partner.PartnerShippingLabel;
import io.github.eschizoid.telescope.demo.spring.partner.PartnerShippingLabelPath;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * End-to-end integration test for the Lombok + Jackson + telescope coexistence slice. Boots the
 * embedded Tomcat, persists an order through the runtime controller, then asks the partner-label
 * controller for the partner SDK's wire-format view. Pins:
 *
 * <ol>
 *   <li><b>Telescope handles the record → Lombok-bean deep mapping.</b> The {@code Mapper<Order,
 *       PartnerShippingLabel>} bean is wired through telescope's deep-mapping factory; the
 *       conversion exercises one rename row ({@code orderNumber → trackingReference}), one
 *       auto-lifted {@code via(...)} row ({@code List<LineItem>} → {@code List<PartnerLineItem>}),
 *       and same-name auto-recursion for {@code customer} / {@code shippingAddress} / {@code
 *       billingAddress} / {@code giftWrap}.
 *   <li><b>Lombok-emitted setters drive the rebuild.</b> {@code writeBeans(SETTERS)} on the partner
 *       target side; the {@code telescope-lombok} processor's emitted holder constants accelerate
 *       the per-component reads/writes.
 *   <li><b>Jackson serialises the result in snake_case.</b> The response JSON has {@code
 *       tracking_reference}, {@code ship_to_customer}, {@code ship_to_address}, etc. — the
 *       {@code @JsonProperty} annotations on the Lombok beans translate Java's {@code camelCase}
 *       property names at marshal time.
 * </ol>
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class PartnerLabelFlowTest {

  @LocalServerPort
  private int port;

  private RestClient client;

  @BeforeEach
  void setUp() {
    this.client = RestClient.create("http://localhost:" + port);
  }

  @Test
  void partnerLabelExposesSnakeCaseWireFormatBackedByTelescopeMapper() {
    // Persist an order via the runtime controller so the partner endpoint has something to load.
    final var created = client
      .post()
      .uri("/orders")
      .contentType(MediaType.APPLICATION_JSON)
      .body(OrderFixtures.sampleOrder())
      .retrieve()
      .body(Order.class);
    assertThat(created).isNotNull();
    final var id = created.id();

    // The partner endpoint returns the Lombok-shaped DTO; telescope built it via the deep-mapping
    // factory; Jackson serialises it with snake_case via @JsonProperty.
    final var label = client.get().uri("/orders/" + id + "/partner-label").retrieve().body(PartnerShippingLabel.class);

    assertThat(label).isNotNull();
    assertThat(label.getTrackingReference()).isEqualTo("ORD-2026-0001");
    assertThat(label.getCustomer()).isNotNull();
    assertThat(label.getCustomer().getEmail()).isEqualTo("alice@example.com");
    assertThat(label.getShippingAddress()).isNotNull();
    assertThat(label.getShippingAddress().getCity()).isEqualTo("Brooklyn");
    assertThat(label.getBillingAddress().getCity()).isEqualTo("Brooklyn");
    assertThat(label.getItems()).hasSize(2);
    assertThat(label.getItems().get(0).getSku()).isEqualTo("SKU-A");
    assertThat(label.getItems().get(0).getQuantity()).isEqualTo(2);
    assertThat(label.getItems().get(0).getUnitPrice()).isEqualByComparingTo("19.99");
    assertThat(label.getGiftWrap()).isNotNull();
    assertThat(label.getGiftWrap().getStreet()).isEqualTo("300 Gift Rd");

    // Cross-check Jackson's wire format: ask for the raw JSON and confirm snake_case keys land.
    final var raw = client.get().uri("/orders/" + id + "/partner-label").retrieve().body(JsonNode.class);

    assertThat(raw).isNotNull();
    assertThat(raw.has("tracking_reference")).as("snake_case rename via @JsonProperty").isTrue();
    assertThat(raw.has("ship_to_customer")).isTrue();
    assertThat(raw.has("ship_to_address")).isTrue();
    assertThat(raw.has("bill_to_address")).isTrue();
    assertThat(raw.has("items")).isTrue();
    assertThat(raw.has("gift_wrap")).isTrue();
    // Java property names must NOT leak — would mean Jackson found a camelCase getter Jackson
    // shouldn't have. The @JsonProperty annotations win.
    assertThat(raw.has("trackingReference")).as("camelCase should not leak").isFalse();
    assertThat(raw.has("shippingAddress")).isFalse();
  }

  @Test
  void lombokEmittedPartnerShippingLabelPathDrivesNestedTypedNavigation() {
    // The telescope-lombok processor emits a full navigator tree against the Lombok @Data graph:
    //   PartnerShippingLabelPath  → customer() / shippingAddress() / billingAddress() / giftWrap()
    // / items()
    //   PartnerCustomerPath       → id() / name() / email()
    //   PartnerAddressPath        → street() / city() / state() / zip()
    //   PartnerLineItemPath       → id() / sku() / quantity() / unitPrice()
    //   PartnerShippingLabelItemsStep → each() → PartnerLineItemPath
    //
    // Each method is compile-time-bound; no SerializedLambda decode, no string-keyed lookup. The
    // chain below drills two levels into the Lombok-shaped graph (customer().email()) and runs an
    // immutable update through synthesised setters. Same shape as the @Focus-driven OrderPath on
    // the record side — but emitted by LombokFocusProcessor against @Data's AST patches.
    //
    // Why this test, not main code: Lombok's round-deferred emission generates the Path in the
    // FINAL annotation-processing round, after main-source symbol resolution finishes. Any consumer
    // compilation phase (a downstream module, or this test) sees the Path on its classpath
    // normally; same-module main code does not. Pattern: put Lombok-Path usage behind a module
    // boundary (here, the test classes).
    final var original = PartnerShippingLabel.builder()
      .id(7L)
      .trackingReference("ORD-2026-9999")
      .customer(PartnerCustomer.builder().id(11L).name("Alice").email("ALICE@EXAMPLE.COM").build())
      .shippingAddress(PartnerAddress.builder().street("100 Main").city("Brooklyn").state("NY").zip("11201").build())
      .billingAddress(PartnerAddress.builder().street("100 Main").city("Brooklyn").state("NY").zip("11201").build())
      .items(List.of())
      .build();

    final var lowered = PartnerShippingLabelPath.start().customer().email().update(original, String::toLowerCase);

    assertThat(lowered.getCustomer().getEmail()).isEqualTo("alice@example.com");
    // Everything else flows through unchanged — the lens setter rebuilds only the customer's email
    // sub-leaf and keeps the rest of the graph intact (other PartnerCustomer fields, sibling
    // properties, the whole nested PartnerAddress, etc).
    assertThat(lowered.getCustomer().getName()).isEqualTo("Alice");
    assertThat(lowered.getCustomer().getId()).isEqualTo(11L);
    assertThat(lowered.getTrackingReference()).isEqualTo("ORD-2026-9999");
    assertThat(lowered.getShippingAddress().getCity()).isEqualTo("Brooklyn");
    // Original untouched.
    assertThat(original.getCustomer().getEmail()).isEqualTo("ALICE@EXAMPLE.COM");
  }
}
