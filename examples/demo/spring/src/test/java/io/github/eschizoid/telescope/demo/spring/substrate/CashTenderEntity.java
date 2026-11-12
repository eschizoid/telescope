package io.github.eschizoid.telescope.demo.spring.substrate;

/** A cash tender bean. */
public final class CashTenderEntity implements TenderEntity {

  private String currency;

  public CashTenderEntity() {}

  public String getCurrency() {
    return currency;
  }

  public void setCurrency(final String currency) {
    this.currency = currency;
  }
}
