package io.github.eschizoid.telescope.demo.order;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Stores a {@link Payment} in one text column: {@code card:VISA:4242:tok_1} or {@code
 * bank:DE89370400440532013000}.
 */
@Converter
public class PaymentColumn implements AttributeConverter<Payment, String> {

  @Override
  public String convertToDatabaseColumn(final Payment payment) {
    if (payment == null) {
      return null;
    }
    return switch (payment) {
      case Card card -> String.join(":", "card", card.brand(), card.last4(), card.token());
      case BankTransfer bank -> "bank:" + bank.iban();
    };
  }

  @Override
  public Payment convertToEntityAttribute(final String column) {
    if (column == null) {
      return null;
    }
    final var parts = column.split(":", -1);
    return switch (parts[0]) {
      case "card" -> new Card(parts[1], parts[2], parts[3]);
      case "bank" -> new BankTransfer(parts[1]);
      default -> throw new IllegalArgumentException("not a payment column: " + column);
    };
  }
}
