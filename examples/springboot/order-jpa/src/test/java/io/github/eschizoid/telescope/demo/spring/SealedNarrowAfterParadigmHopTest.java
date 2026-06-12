package io.github.eschizoid.telescope.demo.spring;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.demo.spring.domain.Order;
import io.github.eschizoid.telescope.demo.spring.domain.payment.CreditCard;
import io.github.eschizoid.telescope.demo.spring.domain.payment.PaymentBridge;
import io.github.eschizoid.telescope.demo.spring.legacy.CreditCardEntity;
import io.github.eschizoid.telescope.demo.spring.legacy.PayPalEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Sealed-type {@code .as(Subtype.class)} narrow after a {@code Telescope.then(...)} paradigm hop
 * from records to beans, exercised on the actual {@link Order} domain.
 *
 * <p>The chain under test:
 *
 * <pre>{@code
 * Telescope.of(Order.class)                  // record-side entry
 *     .field(Order::payment)                  // Telescope<Order, Payment>
 *     .then(PaymentBridge.BRIDGE)             // Telescope<Order, PaymentEntity> — paradigm hop
 *     .as(CreditCardEntity.class)             // narrow on the BEAN side via Prism.downcast
 *     .field(CreditCardEntity::getCardNumber) // bean getter — bean-side field-optics dispatch
 * }</pre>
 *
 * <p>Each {@code .field(...)} call re-resolves its dispatch from the accessor's declaring class —
 * record accessors route through record-field optics, bean accessors through bean-field optics — so
 * the chain stays sound across paradigm hops and prism narrows.
 */
class SealedNarrowAfterParadigmHopTest {

  private static final Order SAMPLE = OrderFixtures.sampleOrder();

  @Test
  @DisplayName("paradigm hop then sealed narrow then bean .field() read returns the bean property")
  void readThroughChainReturnsBeanProperty() {
    final Telescope<Order, String> chain = Telescope.of(Order.class)
      .field(Order::payment)
      .then(PaymentBridge.BRIDGE)
      .as(CreditCardEntity.class)
      .field(CreditCardEntity::getCardNumber);

    assertThat(chain.find(SAMPLE)).contains("4111111111111111");
  }

  @Test
  @DisplayName("paradigm hop then sealed narrow then bean .field() update rewrites through the bridge")
  void updateThroughChainRewritesViaBridge() {
    final Telescope<Order, String> chain = Telescope.of(Order.class)
      .field(Order::payment)
      .then(PaymentBridge.BRIDGE)
      .as(CreditCardEntity.class)
      .field(CreditCardEntity::getCardNumber);

    final var masked = chain.update(SAMPLE, n -> "**** **** **** " + n.substring(n.length() - 4));

    assertThat(masked.payment()).isInstanceOf(CreditCard.class);
    assertThat(((CreditCard) masked.payment()).cardNumber()).isEqualTo("**** **** **** 1111");
    assertThat(((CreditCard) masked.payment()).holder()).isEqualTo("Alice Doe");
  }

  @Test
  @DisplayName("control: prism narrow to non-matching subtype skips reads/writes (no .field() dispatch)")
  void narrowSkipOnMismatch() {
    final var chain = Telescope.of(Order.class)
      .field(Order::payment)
      .then(PaymentBridge.BRIDGE)
      .as(PayPalEntity.class)
      .field(PayPalEntity::getEmail);

    assertThat(chain.find(SAMPLE)).isEmpty();
    final var unchanged = chain.update(SAMPLE, e -> "should-not-fire");
    assertThat(unchanged.payment()).isInstanceOf(CreditCard.class);
    assertThat(((CreditCard) unchanged.payment()).cardNumber()).isEqualTo("4111111111111111");
  }
}
