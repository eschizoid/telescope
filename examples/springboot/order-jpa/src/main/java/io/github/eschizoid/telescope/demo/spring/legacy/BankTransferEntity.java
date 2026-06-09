package io.github.eschizoid.telescope.demo.spring.legacy;

public final class BankTransferEntity implements PaymentEntity {

  private String iban;
  private String bic;

  public BankTransferEntity() {}

  public String getIban() {
    return iban;
  }

  public void setIban(final String iban) {
    this.iban = iban;
  }

  public String getBic() {
    return bic;
  }

  public void setBic(final String bic) {
    this.bic = bic;
  }
}
