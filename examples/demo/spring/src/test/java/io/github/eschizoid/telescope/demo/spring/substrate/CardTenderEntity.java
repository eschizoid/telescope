package io.github.eschizoid.telescope.demo.spring.substrate;

/** A card tender bean. */
public final class CardTenderEntity implements TenderEntity {

  private String number;
  private String holder;

  public CardTenderEntity() {}

  public String getNumber() {
    return number;
  }

  public void setNumber(final String number) {
    this.number = number;
  }

  public String getHolder() {
    return holder;
  }

  public void setHolder(final String holder) {
    this.holder = holder;
  }
}
