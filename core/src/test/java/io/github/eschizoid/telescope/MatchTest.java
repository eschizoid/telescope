package io.github.eschizoid.telescope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link Match} — the sealed-type dispatch helper that uses {@link
 * Class#getPermittedSubclasses()} for compile-checked exhaustiveness verification at the {@code
 * .exhaustive()} terminal. The Java 17 sealed API is the structural advantage MapStruct (Java 11)
 * cannot reach.
 */
class MatchTest {

  sealed interface Payment permits CreditCard, BankTransfer, Crypto {}

  record CreditCard(String pan) implements Payment {}

  record BankTransfer(String iban) implements Payment {}

  record Crypto(String wallet) implements Payment {}

  @Nested
  @DisplayName("happy path — every permit covered")
  class HappyPath {

    @Test
    @DisplayName("exhaustive dispatch handles each permit with the registered handler")
    void exhaustiveDispatch() {
      final Function<Payment, String> dispatch = Match.<Payment, String>of(Payment.class)
        .when(CreditCard.class, c -> "card:" + c.pan())
        .when(BankTransfer.class, b -> "bank:" + b.iban())
        .when(Crypto.class, x -> "crypto:" + x.wallet())
        .exhaustive();

      assertEquals("card:4111", dispatch.apply(new CreditCard("4111")));
      assertEquals("bank:IBAN-1", dispatch.apply(new BankTransfer("IBAN-1")));
      assertEquals("crypto:0xABC", dispatch.apply(new Crypto("0xABC")));
    }
  }

  @Nested
  @DisplayName("exhaustiveness check")
  class Exhaustiveness {

    @Test
    @DisplayName("missing handler for one permit throws at .exhaustive() naming the gap")
    void missingPermitThrows() {
      final var ex = assertThrows(IllegalStateException.class, () ->
        Match.<Payment, String>of(Payment.class)
          .when(CreditCard.class, c -> "card")
          .when(BankTransfer.class, b -> "bank")
          // missing Crypto
          .exhaustive()
      );
      assertTrue(ex.getMessage().contains("Crypto"), () -> "expected message to name Crypto, was: " + ex.getMessage());
    }

    @Test
    @DisplayName("missing handlers for multiple permits names ALL the gaps")
    void multipleMissingPermits() {
      final var ex = assertThrows(IllegalStateException.class, () ->
        Match.<Payment, String>of(Payment.class)
          .when(CreditCard.class, c -> "card")
          .exhaustive()
      );
      assertTrue(ex.getMessage().contains("BankTransfer"));
      assertTrue(ex.getMessage().contains("Crypto"));
    }
  }

  @Nested
  @DisplayName("guards")
  class Guards {

    @Test
    @DisplayName("Match.of(...) rejects a non-sealed class")
    void nonSealedRoot() {
      final var ex = assertThrows(IllegalArgumentException.class, () -> Match.<String, String>of(String.class));
      assertTrue(ex.getMessage().contains("not sealed"));
    }

    @Test
    @DisplayName("when(...) rejects a duplicate handler for the same permit")
    void duplicateWhen() {
      final var ex = assertThrows(IllegalArgumentException.class, () ->
        Match.<Payment, String>of(Payment.class)
          .when(CreditCard.class, c -> "a")
          .when(CreditCard.class, c -> "b")
      );
      assertTrue(ex.getMessage().contains("already registered"));
    }
  }

  @Nested
  @DisplayName("partial() escape hatch — no exhaustiveness check")
  class Partial {

    @Test
    @DisplayName("partial() dispatches registered permits, throws on unhandled")
    void partialDispatchAndThrow() {
      final Function<Payment, String> dispatch = Match.<Payment, String>of(Payment.class)
        .when(CreditCard.class, c -> "card:" + c.pan())
        .partial();

      assertEquals("card:4111", dispatch.apply(new CreditCard("4111")));

      final var ex = assertThrows(IllegalStateException.class, () -> dispatch.apply(new BankTransfer("X")));
      assertTrue(ex.getMessage().contains("BankTransfer"));
    }
  }

  @Nested
  @DisplayName("guards — sharpened from PR-89 review")
  class SharpenedGuards {

    @Test
    @DisplayName("exhaustive() missing-permit error lists names in source-declaration order (deterministic)")
    void missingPermitOrderingDeterministic() {
      // Payment permits CreditCard, BankTransfer, Crypto — register only CreditCard so two are
      // missing. Ordering must follow Payment.getPermittedSubclasses() (BankTransfer, then Crypto)
      // not the hash-bucketed Set.of iteration.
      final var ex = assertThrows(IllegalStateException.class, () ->
        Match.<Payment, String>of(Payment.class).when(CreditCard.class, c -> "c").exhaustive()
      );
      final var msg = ex.getMessage();
      assertTrue(msg.contains("BankTransfer"));
      assertTrue(msg.contains("Crypto"));
      // Source-declaration order: BankTransfer appears before Crypto in the message.
      assertTrue(msg.indexOf("BankTransfer") < msg.indexOf("Crypto"), () -> "expected source order, got: " + msg);
    }
  }
}
