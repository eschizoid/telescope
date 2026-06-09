package io.github.eschizoid.telescope.demo.spring.bughunt.sealed;

import io.github.eschizoid.telescope.Telescope;

/**
 * Hand-rolled paradigm-bridge for the sealed pair {@link Payment} ↔ {@link PaymentEntity}. Built
 * via {@code Telescope.from(...).to(...).using(...)} so the chain crosses the paradigm hop and the
 * subsequent {@code .as(Subtype.class)} narrow exercises the post-hop dispatch logic.
 *
 * <p>Why hand-built rather than the deep-mapping factory: the deep {@code Telescope.mapper(...)}
 * engine walks declared components, but a sealed-interface root has none. Wiring it via the {@code
 * from/to/using} {@link io.github.eschizoid.telescope.internal.optics.Iso} factory is the cheap
 * path and produces a real {@code Telescope<Payment, PaymentEntity>} composable through {@code
 * .then(...)}.
 */
public final class PaymentMappers {

  private PaymentMappers() {}

  /**
   * Forward + backward {@code Telescope<Payment, PaymentEntity>} that drives the bug-hunt chain.
   */
  public static Telescope<Payment, PaymentEntity> paymentBridge() {
    return Telescope.from(Payment.class)
      .to(PaymentEntity.class)
      .using(PaymentMappers::forward, PaymentMappers::backward);
  }

  static PaymentEntity forward(final Payment p) {
    return switch (p) {
      case CreditCard cc -> {
        final var e = new CreditCardEntity();
        e.setCardNumber(cc.cardNumber());
        e.setHolder(cc.holder());
        e.setExpiryYear(cc.expiryYear());
        yield e;
      }
      case PayPal pp -> {
        final var e = new PayPalEntity();
        e.setEmail(pp.email());
        e.setToken(pp.token());
        yield e;
      }
      case BankTransfer bt -> {
        final var e = new BankTransferEntity();
        e.setIban(bt.iban());
        e.setBic(bt.bic());
        yield e;
      }
    };
  }

  static Payment backward(final PaymentEntity e) {
    return switch (e) {
      case CreditCardEntity cc -> new CreditCard(cc.getCardNumber(), cc.getHolder(), cc.getExpiryYear());
      case PayPalEntity pp -> new PayPal(pp.getEmail(), pp.getToken());
      case BankTransferEntity bt -> new BankTransfer(bt.getIban(), bt.getBic());
    };
  }
}
