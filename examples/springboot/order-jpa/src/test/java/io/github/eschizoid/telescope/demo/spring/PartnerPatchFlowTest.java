package io.github.eschizoid.telescope.demo.spring;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.eschizoid.telescope.conversion.Mapper;
import io.github.eschizoid.telescope.demo.spring.domain.Order;
import io.github.eschizoid.telescope.demo.spring.partner.PartnerCustomer;
import io.github.eschizoid.telescope.demo.spring.partner.PartnerShippingLabel;
import io.github.eschizoid.telescope.demo.spring.persistence.OrderEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * Bug-hunt slice: pin {@code Mapper.patch(...)} in the Lombok-bean → record direction.
 *
 * <p>The existing demo only patches in the record → bean direction (see {@code
 * RuntimeOrderController.patch}). Here the source is the {@code Order} record and the target is the
 * Lombok-{@code @Data} {@code PartnerShippingLabel}. The patch table is built at the top level of
 * the type pair; entries are keyed on bean property names and write through the record-side
 * canonical-constructor reflective. Pre-1.0 risk: dispatch silently drops values, no-ops, or fills
 * with nulls when the source.kind != target.kind on this corner.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class PartnerPatchFlowTest {

  @LocalServerPort
  private int port;

  @Autowired
  private Mapper<Order, PartnerShippingLabel> partnerLabelMapper;

  @Autowired
  private Mapper<Order, OrderEntity> orderMapper;

  private RestClient client;

  @BeforeEach
  void setUp() {
    this.client = RestClient.create("http://localhost:" + port);
  }

  /**
   * Direct unit-style assertion on the mapper bean — partial PartnerShippingLabel with only
   * customer set; everything else on the resulting Order must be the original base value. This pins
   * the cross-paradigm dispatch independently of the HTTP plumbing.
   */
  @Test
  void patchFromPartialPartnerLabelOverlaysOnlyNonNullCustomer() {
    final var base = OrderFixtures.sampleOrder();
    final var partialCustomer = PartnerCustomer.builder()
      .id(99L)
      .name("Patched Name")
      .email("patched@example.com")
      .build();

    // Only customer is set on the partial — every other field is null. Top-level patch must
    // overlay customer, leave the rest untouched.
    final var partial = PartnerShippingLabel.builder().customer(partialCustomer).build();

    final var patched = partnerLabelMapper.patch(base, partial);

    assertThat(patched).as("patch should not return null on a non-empty partial").isNotNull();
    // Customer was patched.
    assertThat(patched.customer()).isNotNull();
    assertThat(patched.customer().email()).isEqualTo("patched@example.com");
    assertThat(patched.customer().name()).isEqualTo("Patched Name");

    // Everything else preserved from the base — this is the load-bearing assertion. If patch
    // silently overwrites non-null sources with nulls (because the partial bean has null bean
    // fields and the dispatch reads them as "patch with null"), we'd see nulls here.
    assertThat(patched.orderNumber()).isEqualTo(base.orderNumber());
    assertThat(patched.shippingAddress()).isEqualTo(base.shippingAddress());
    assertThat(patched.billingAddress()).isEqualTo(base.billingAddress());
    assertThat(patched.lineItems()).isEqualTo(base.lineItems());
    assertThat(patched.giftWrap()).isEqualTo(base.giftWrap());
  }

  /**
   * Partial with a sparsely-populated nested PartnerCustomer (only email). Pins the load-bearing
   * concern: top-level patch writes the WHOLE nested target back through the backward Iso, so a
   * partner customer with only email set should produce a Customer with only email set (others
   * null). What we want to confirm: the backward Iso preserves the present field through the
   * cross-paradigm conversion, and the top-level patch substitutes that into base.customer.
   */
  @Test
  void patchFromPartialPartnerLabelWithOnlyEmailOnCustomerPreservesEmail() {
    final var base = OrderFixtures.sampleOrder();
    final var partial = PartnerShippingLabel.builder()
      .customer(PartnerCustomer.builder().email("only-email@example.com").build())
      .build();

    final var patched = partnerLabelMapper.patch(base, partial);

    assertThat(patched).isNotNull();
    assertThat(patched.customer()).isNotNull();
    // Load-bearing: the email survived the cross-paradigm backward conversion at top-level patch.
    assertThat(patched.customer().email()).isEqualTo("only-email@example.com");
    // The whole customer is replaced (top-level patch substitutes wholesale). Name/id should
    // reflect the partial — which means they're null (no merge with base.customer).
    assertThat(patched.customer().name()).isNull();
    assertThat(patched.customer().id()).isNull();
  }

  /**
   * No-op partial: every bean field is null. patch should return base untouched (or an equal copy).
   * Pins the empty-overlay short-circuit on the cross-paradigm corner.
   */
  @Test
  void patchFromEmptyPartialPartnerLabelLeavesBaseIntact() {
    final var base = OrderFixtures.sampleOrder();
    final var empty = PartnerShippingLabel.builder().build();

    final var patched = partnerLabelMapper.patch(base, empty);

    assertThat(patched).isEqualTo(base);
  }

  /**
   * In-process roundtrip: patch a partial PartnerShippingLabel onto an Order then run that Order
   * through {@code orderMapper.forward → backward}, simulating the persistence boundary without
   * Hibernate. Pins that the cross-paradigm patch result survives a subsequent entity-side
   * conversion when the only patched leaf is a deeply-nested scalar.
   */
  @Test
  void patchedOrderSurvivesOrderEntityForwardBackwardRoundtrip() {
    final var base = OrderFixtures.sampleOrder();
    final var partial = PartnerShippingLabel.builder()
      .customer(PartnerCustomer.builder().email("only-email@example.com").build())
      .build();

    final var patched = partnerLabelMapper.patch(base, partial);
    final var asEntity = orderMapper.forward(patched);
    final var roundtripped = orderMapper.backward(asEntity);

    assertThat(roundtripped).isNotNull();
    assertThat(roundtripped.customer()).isNotNull();
    assertThat(roundtripped.customer().email()).isEqualTo("only-email@example.com");
  }

  /**
   * Integration-flow check: POST an order, then PATCH /orders/{id}/from-partner with a partial
   * PartnerShippingLabel whose only non-null bean field is {@code trackingReference} (mapped to
   * {@code Order.orderNumber} via a rename row). Top-level patch should override only the order
   * number on the stored order. This exercises the rename row in the patch path — same
   * cross-paradigm dispatch corner as the customer test, but on a scalar leaf where there's no
   * nested null-fill complication.
   */
  @Test
  void httpPatchFromPartnerLabelChangesOnlyOrderNumber() {
    final var created = client
      .post()
      .uri("/orders/runtime")
      .contentType(MediaType.APPLICATION_JSON)
      .body(OrderFixtures.sampleOrder())
      .retrieve()
      .body(Order.class);
    assertThat(created).isNotNull();
    final var id = created.id();

    final var partial = PartnerShippingLabel.builder().trackingReference("ORD-2026-PATCHED").build();

    final var patched = client
      .patch()
      .uri("/orders/" + id + "/from-partner")
      .contentType(MediaType.APPLICATION_JSON)
      .body(partial)
      .retrieve()
      .body(Order.class);

    assertThat(patched).isNotNull();
    assertThat(patched.orderNumber()).isEqualTo("ORD-2026-PATCHED");
    // Customer/addresses/items all preserved from the original order.
    assertThat(patched.customer().email()).isEqualTo(created.customer().email());
    assertThat(patched.shippingAddress()).isEqualTo(created.shippingAddress());
    assertThat(patched.lineItems()).hasSize(created.lineItems().size());
  }
}
