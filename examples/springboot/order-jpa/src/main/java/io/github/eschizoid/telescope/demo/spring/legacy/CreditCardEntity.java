package io.github.eschizoid.telescope.demo.spring.legacy;

public final class CreditCardEntity implements PaymentEntity {

  private String cardNumber;
  private String holder;
  private int expiryYear;

  public CreditCardEntity() {}

  public String getCardNumber() {
    return cardNumber;
  }

  public void setCardNumber(final String cardNumber) {
    this.cardNumber = cardNumber;
  }

  public String getHolder() {
    return holder;
  }

  public void setHolder(final String holder) {
    this.holder = holder;
  }

  public int getExpiryYear() {
    return expiryYear;
  }

  public void setExpiryYear(final int expiryYear) {
    this.expiryYear = expiryYear;
  }
}
