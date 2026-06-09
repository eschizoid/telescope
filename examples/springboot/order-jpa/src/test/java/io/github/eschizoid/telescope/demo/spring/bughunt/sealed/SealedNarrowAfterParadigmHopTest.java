package io.github.eschizoid.telescope.demo.spring.bughunt.sealed;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.eschizoid.telescope.Telescope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Slice — sealed-type {@code .as(Subtype.class)} narrow after a {@code Telescope.then(...)}
 * paradigm hop from records to beans.
 *
 * <p>The chain under test:
 *
 * <pre>{@code
 * Telescope.of(PaymentRoot.class)            // record-side entry
 *     .field(PaymentRoot::payment)            // Telescope<PaymentRoot, Payment>
 *     .then(PaymentMappers.paymentBridge())   // Telescope<PaymentRoot, PaymentEntity> — paradigm hop
 *     .as(CreditCardEntity.class)             // narrow on the BEAN side via Prism.downcast
 *     .field(CreditCardEntity::getCardNumber) // bean getter — BeanFieldOptics dispatch
 * }</pre>
 *
 * <p>Each {@code .field(...)} call re-resolves its dispatch from the accessor's declaring class —
 * record accessors route through {@code RecordFieldOptics}, bean accessors through {@code
 * BeanFieldOptics} — so the chain stays sound across paradigm hops and prism narrows.
 */
class SealedNarrowAfterParadigmHopTest {

  private static final PaymentRoot ROOT = new PaymentRoot(
    "ORD-001",
    new CreditCard("4111111111111111", "Alice Doe", 2030)
  );

  @Test
  @DisplayName("paradigm hop then sealed narrow then bean .field() read returns the bean property")
  void readThroughChainReturnsBeanProperty() {
    final Telescope<PaymentRoot, String> chain = Telescope.of(PaymentRoot.class)
      .field(PaymentRoot::payment)
      .then(PaymentMappers.paymentBridge())
      .as(CreditCardEntity.class)
      .field(CreditCardEntity::getCardNumber);

    assertThat(chain.find(ROOT)).contains("4111111111111111");
  }

  @Test
  @DisplayName("paradigm hop then sealed narrow then bean .field() update rewrites through the bridge")
  void updateThroughChainRewritesViaBridge() {
    final Telescope<PaymentRoot, String> chain = Telescope.of(PaymentRoot.class)
      .field(PaymentRoot::payment)
      .then(PaymentMappers.paymentBridge())
      .as(CreditCardEntity.class)
      .field(CreditCardEntity::getCardNumber);

    final var masked = chain.update(ROOT, n -> "**** **** **** " + n.substring(n.length() - 4));

    assertThat(masked.payment()).isInstanceOf(CreditCard.class);
    assertThat(((CreditCard) masked.payment()).cardNumber()).isEqualTo("**** **** **** 1111");
    assertThat(((CreditCard) masked.payment()).holder()).isEqualTo("Alice Doe");
  }

  @Test
  @DisplayName("control: prism narrow to non-matching subtype skips reads/writes (no .field() dispatch)")
  void narrowSkipOnMismatch() {
    // Negative control — when the prism doesn't match, .field() is never invoked on the wrong type
    // because the traversal short-circuits at the prism. So this branch is unaffected by the bug.
    final var chain = Telescope.of(PaymentRoot.class)
      .field(PaymentRoot::payment)
      .then(PaymentMappers.paymentBridge())
      .as(PayPalEntity.class)
      .field(PayPalEntity::getEmail);

    assertThat(chain.find(ROOT)).isEmpty();
    final var unchanged = chain.update(ROOT, e -> "should-not-fire");
    assertThat(unchanged.payment()).isInstanceOf(CreditCard.class);
    assertThat(((CreditCard) unchanged.payment()).cardNumber()).isEqualTo("4111111111111111");
  }
}
