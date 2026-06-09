package io.github.eschizoid.telescope.demo.spring.bughunt.bridge;

import io.github.eschizoid.telescope.annotations.BeanFocus;
import java.util.ArrayList;
import java.util.List;

/**
 * Entity-side mirror of {@link InvoiceHeader}. Same bean property names ({@code number, lines})
 * with the list element type swapped to {@link InvoiceLineEntity} — the codegen plan-fields branch
 * picks {@code LIST} with a sub-bridge reference to the user-declared {@code InvoiceLineBridge}.
 */
@BeanFocus
public class InvoiceHeaderEntity {

  private String number;
  private List<InvoiceLineEntity> lines = new ArrayList<>();

  public InvoiceHeaderEntity() {}

  public String getNumber() {
    return number;
  }

  public void setNumber(final String number) {
    this.number = number;
  }

  public List<InvoiceLineEntity> getLines() {
    return lines;
  }

  public void setLines(final List<InvoiceLineEntity> lines) {
    this.lines = lines;
  }
}
