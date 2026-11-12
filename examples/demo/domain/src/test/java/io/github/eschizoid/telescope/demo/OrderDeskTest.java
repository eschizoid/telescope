package io.github.eschizoid.telescope.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.eschizoid.telescope.demo.api.BankTransferDto;
import io.github.eschizoid.telescope.demo.api.CardDto;
import io.github.eschizoid.telescope.demo.api.ImportTrace;
import io.github.eschizoid.telescope.demo.api.ImportedOrderFromMap;
import io.github.eschizoid.telescope.demo.api.NewLine;
import io.github.eschizoid.telescope.demo.api.NewLineV2;
import io.github.eschizoid.telescope.demo.api.OrderChanges;
import io.github.eschizoid.telescope.demo.api.OrderLineDto;
import io.github.eschizoid.telescope.demo.api.OrderLineV2Dto;
import io.github.eschizoid.telescope.demo.api.PlaceOrderV2;
import io.github.eschizoid.telescope.demo.api.RequestMappers;
import io.github.eschizoid.telescope.demo.order.Card;
import io.github.eschizoid.telescope.demo.order.Delivery;
import io.github.eschizoid.telescope.demo.order.InMemoryOrders;
import io.github.eschizoid.telescope.demo.order.OrderDesk;
import io.github.eschizoid.telescope.demo.order.OrderMappers;
import io.github.eschizoid.telescope.demo.order.OrderStatus;
import io.github.eschizoid.telescope.demo.order.SampleData;
import io.github.eschizoid.telescope.demo.order.Seller;
import io.github.eschizoid.telescope.introspection.OpticNode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The order service's rules and mappers, run on the JVM with no framework around them. */
class OrderDeskTest {

  private static final Clock TODAY = Clock.fixed(Instant.parse("2026-11-12T10:00:00Z"), ZoneOffset.UTC);

  private InMemoryOrders orders;
  private OrderDesk desk;

  @BeforeEach
  void load() {
    orders = new InMemoryOrders();
    desk = new OrderDesk(orders, new Seller("Telescope Goods", "GB123456789"), TODAY);
    SampleData.load(desk, orders);
  }

  @Test
  @DisplayName("the public DTO hides the cost price, the card token and the notes, and totals the lines")
  void thePublicDtoHidesWhatOnlyTheShopSees() {
    final var dto = desk.get("SO-1001").toOrderDto();
    assertThat(dto.customerName()).isEqualTo("Ada Lovelace");
    assertThat(dto.lines()).containsExactly(
      new OrderLineDto("MUG-01", "Enamel mug", 2, new BigDecimal("12.50")),
      new OrderLineDto("TEE-02", "Logo T-shirt", 1, new BigDecimal("25.00"))
    );
    assertThat(dto.payment()).isEqualTo(new CardDto("VISA", "4242"));
    // 2 x 12.50 + 25.00 = 50.00, less 10%
    assertThat(dto.total()).isEqualByComparingTo("45.00");
    assertThat(dto.placedOn()).isEqualTo(LocalDate.of(2026, 11, 2));
    assertThat(dto.tags()).containsExactly("gift");
  }

  @Test
  @DisplayName("paying reserves a tracking number, and the public view shows it only once the order ships")
  void theTrackingNumberShowsOnlyOnceShipped() {
    desk.pay("SO-1001");
    assertThat(desk.get("SO-1001").getTrackingNumber()).isEqualTo("TRK-1001");
    assertThat(desk.get("SO-1001").toOrderDto().shipping()).isNull();
    desk.ship("SO-1001");
    final var shipped = desk.get("SO-1001").toOrderDto();
    assertThat(shipped.status()).isEqualTo(OrderStatus.SHIPPED);
    assertThat(shipped.shipping().trackingNumber()).isEqualTo("TRK-1001");
    assertThat(desk.get("SO-1002").toOrderDto().payment()).isEqualTo(new BankTransferDto("3000"));
  }

  @Test
  @DisplayName("an unpaid order does not ship, a shipped order is not cancelled, and a paid order is not changed")
  void theLifecycleRefusesWhatARealShopRefuses() {
    assertThatThrownBy(() -> desk.ship("SO-1001"))
      .isInstanceOf(IllegalStateException.class)
      .hasMessage("order SO-1001 is PLACED, and only a PAID order can ship");
    assertThatThrownBy(() -> desk.cancel("SO-1002"))
      .isInstanceOf(IllegalStateException.class)
      .hasMessage("order SO-1002 is SHIPPED and cannot be cancelled");
    desk.pay("SO-1001");
    assertThatThrownBy(() -> desk.pay("SO-1001")).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> desk.change("SO-1001", new OrderChanges("VIP25", null, null, null, null)))
      .isInstanceOf(IllegalStateException.class)
      .hasMessage("order SO-1001 is PAID, and only a PLACED order can change");
    assertThat(desk.cancel("SO-1001").getStatus()).isEqualTo(OrderStatus.CANCELLED);
  }

  @Test
  @DisplayName("version 2 renames fields and writes the date, the money and the status as text")
  void versionTwoRenamesAndFormats() {
    final var v2 = desk.get("SO-1001").toOrderV2Dto();
    assertThat(v2.buyer()).isEqualTo("Ada Lovelace");
    assertThat(v2.status()).isEqualTo("PLACED");
    assertThat(v2.placedOn()).isEqualTo("02.11.2026");
    assertThat(v2.total()).isEqualTo("45.00");
    assertThat(v2.coupon()).isEqualTo("WELCOME10");
    assertThat(v2.tags()).isEqualTo(Set.of("gift"));
    assertThat(v2.lines()).first().isEqualTo(new OrderLineV2Dto("MUG-01", "Enamel mug", 2L, "12.50"));
    assertThat(desk.get("SO-1002").toOrderV2Dto().coupon()).isEqualTo("NONE");
  }

  @Test
  @DisplayName("a PATCH changes only what was sent, and refills the managed lines through addLine")
  void aPatchChangesOnlyWhatWasSent() {
    final var order = desk.get("SO-1001");
    final var heldLines = order.getLines();
    final var heldTags = order.getTags();
    desk.change("SO-1001", new OrderChanges(null, Delivery.EXPRESS, null, null, List.of(new NewLine("BAG-04", 3))));
    assertThat(order.getDelivery()).isEqualTo(Delivery.EXPRESS);
    assertThat(order.getDiscountCode()).isEqualTo("WELCOME10");
    assertThat(order.isGiftWrap()).isTrue();
    assertThat(order.getInternalNotes()).isEqualTo("Wrap in the blue paper.");
    assertThat(order.getTrackingNumber()).isNull();
    assertThat(order.getLines()).isSameAs(heldLines);
    assertThat(order.getTags()).isSameAs(heldTags).containsExactly("gift");
    assertThat(order.getLines())
      .singleElement()
      .satisfies(line -> {
        assertThat(line.getSku()).isEqualTo("BAG-04");
        assertThat(line.getQuantity()).isEqualTo(3);
        assertThat(line.getUnitPrice()).isEqualByComparingTo("15.00");
        assertThat(line.getOrder()).isSameAs(order);
      });
  }

  @Test
  @DisplayName("a version 2 request maps to the service's request, items converted by hand-written methods")
  void aVersionTwoRequestMapsToPlaceOrder() {
    final var placed = desk.place(
      RequestMappers.V2.forward(
        new PlaceOrderV2(
          SampleData.GRACE,
          List.of(new NewLineV2("TEE-02", 2)),
          null,
          false,
          new Card("MC", "5454", "tok_mc"),
          "VIP25",
          Set.of("b2b")
        )
      )
    );
    assertThat(placed.getNumber()).isEqualTo("SO-1003");
    assertThat(placed.getPlacedOn()).isEqualTo(LocalDate.of(2026, 11, 12));
    assertThat(placed.getStatus()).isEqualTo(OrderStatus.PLACED);
    assertThat(placed.getDelivery()).isEqualTo(Delivery.STANDARD);
    assertThat(placed.getTags()).containsExactly("b2b");
    assertThat(placed.getLines())
      .singleElement()
      .satisfies(line -> assertThat(line.getOrder()).isSameAs(placed));
    assertThat(placed.toOrderDto().total()).isEqualByComparingTo("37.50");
  }

  @Test
  @DisplayName("an untyped partner payload binds to a record and converts its text to the request's types")
  void anUntypedPayloadImports() {
    final Map<String, Object> payload = Map.of(
      "customerEmail",
      SampleData.ADA,
      "delivery",
      "EXPRESS",
      "giftWrap",
      "true",
      "lines",
      List.of(Map.of("sku", "CAP-03", "quantity", "4"))
    );
    final var command = RequestMappers.IMPORT.forward(ImportedOrderFromMap.fromMap(payload));
    assertThat(command.delivery()).isEqualTo(Delivery.EXPRESS);
    assertThat(command.giftWrap()).isTrue();
    assertThat(command.lines()).containsExactly(new NewLine("CAP-03", 4));
    assertThat(command.tags()).isNull();
    assertThat(desk.place(command).toOrderDto().total()).isEqualByComparingTo("72.00");
  }

  @Test
  @DisplayName("the invoice merges the order, the customer, the seller and the totals")
  void theInvoiceMergesFourSources() {
    final var invoice = desk.invoice("SO-1001");
    assertThat(invoice.number()).isEqualTo("SO-1001");
    assertThat(invoice.issuedOn()).isEqualTo(LocalDate.of(2026, 11, 2));
    assertThat(invoice.billTo()).isEqualTo("Ada Lovelace");
    assertThat(invoice.email()).isEqualTo(SampleData.ADA);
    assertThat(invoice.sellerName()).isEqualTo("Telescope Goods");
    assertThat(invoice.sellerVatId()).isEqualTo("GB123456789");
    assertThat(invoice.subtotal()).isEqualByComparingTo("50.00");
    assertThat(invoice.discount()).isEqualByComparingTo("5.00");
    assertThat(invoice.total()).isEqualByComparingTo("45.00");
  }

  @Test
  @DisplayName("a line whose quantity a partner sent under another key is refused, and the trace shows why")
  void theImportTraceShowsAMisspelledKey() {
    final Map<String, Object> payload = Map.of(
      "customerEmail",
      SampleData.ADA,
      "lines",
      List.of(Map.of("sku", "CAP-03", "qty", "4"))
    );
    final var command = RequestMappers.IMPORT.forward(ImportedOrderFromMap.fromMap(payload));
    assertThatThrownBy(() -> desk.place(command))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("CAP-03");
    assertThat(ImportTrace.of(payload)).contains("qty=4").contains("ImportedLine[sku=CAP-03, quantity=null]");
  }

  @Test
  @DisplayName("explain says the public mapper runs the bridge generated from its rows")
  void explainSaysTheGeneratedBridgeRuns() {
    assertThat(OrderMappers.DTO.explain().declared()).hasValueSatisfying(declared ->
      assertThat(declared.runs()).isEqualTo(OpticNode.Runs.GENERATED)
    );
    assertThat(OrderMappers.DTO.explain().toString()).contains("OrderToOrderDtoBridge");
  }
}
