package io.github.eschizoid.telescope.demo.spring.bughunt.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit-level verification of the {@code @Bridge}-emitted code on a JPA-style record↔bean pair.
 *
 * <p>Each test compiles only because the codegen processors fired — if {@code
 * InvoiceLineBridge.BRIDGE}, {@code InvoiceLinePath#asInvoiceLineEntity}, or {@code
 * InvoiceHeaderBridge} weren't generated, this class would fail to compile.
 */
class InvoiceBridgeTest {

  @Test
  @DisplayName("InvoiceLineBridge.BRIDGE round-trips a same-typed-field record↔bean pair")
  void bridgeRoundTrip() {
    final var line = new InvoiceLine("SKU-A", 3, new BigDecimal("19.99"));

    final var entity = InvoiceLineBridge.forward(line);
    assertThat(entity).isNotNull();
    assertThat(entity.getSku()).isEqualTo("SKU-A");
    assertThat(entity.getQty()).isEqualTo(3);
    assertThat(entity.getUnitPrice()).isEqualByComparingTo("19.99");

    final var roundTripped = InvoiceLineBridge.backward(entity);
    assertThat(roundTripped).isEqualTo(line);
  }

  @Test
  @DisplayName("InvoiceLinePath.start().asInvoiceLineEntity() reads through the BRIDGE Iso")
  void bridgeHopFromPathNavigator() {
    final var line = new InvoiceLine("SKU-B", 1, new BigDecimal("49.50"));

    // Compile-time-bound hop: BridgeProcessor wired the navigator's asInvoiceLineEntity() method
    // to return a typed continuation (InvoiceLineEntityPath because the target is @BeanFocus).
    final InvoiceLineEntity entity = InvoiceLinePath.start().asInvoiceLineEntity().read(line);

    assertThat(entity.getSku()).isEqualTo("SKU-B");
    assertThat(entity.getQty()).isEqualTo(1);
    assertThat(entity.getUnitPrice()).isEqualByComparingTo("49.50");
  }

  @Test
  @DisplayName("InvoiceHeaderBridge auto-recurses into the user-declared InvoiceLineBridge for List<InvoiceLine>")
  void deepRecursionUsesUserDeclaredSubBridge() {
    final var header = new InvoiceHeader(
      "INV-001",
      List.of(new InvoiceLine("SKU-A", 2, new BigDecimal("10.00")), new InvoiceLine("SKU-B", 5, new BigDecimal("3.25")))
    );

    final var entity = InvoiceHeaderBridge.forward(header);
    assertThat(entity.getNumber()).isEqualTo("INV-001");
    assertThat(entity.getLines()).hasSize(2);
    assertThat(entity.getLines().get(0).getSku()).isEqualTo("SKU-A");
    assertThat(entity.getLines().get(0).getQty()).isEqualTo(2);
    assertThat(entity.getLines().get(0).getUnitPrice()).isEqualByComparingTo("10.00");
    assertThat(entity.getLines().get(1).getSku()).isEqualTo("SKU-B");
    assertThat(entity.getLines().get(1).getUnitPrice()).isEqualByComparingTo("3.25");

    final var back = InvoiceHeaderBridge.backward(entity);
    assertThat(back).isEqualTo(header);
  }
}
