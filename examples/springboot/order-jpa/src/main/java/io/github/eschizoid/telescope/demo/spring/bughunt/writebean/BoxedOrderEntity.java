package io.github.eschizoid.telescope.demo.spring.bughunt.writebean;

import java.util.List;

/**
 * Target-side root bean. Classic JavaBean: no-arg constructor + setters for the top-level fields so
 * the default {@code writeBeans(SETTERS)} hint applies. The {@code List<ShippingNote>} component
 * lifts the per-class {@code writeBean(ShippingNote.class, CONSTRUCTOR)} override at every element
 * — proving the override wins over the default for that one class only.
 */
public class BoxedOrderEntity {

  private Long id;
  private String label;
  private List<ShippingNote> notes;

  public BoxedOrderEntity() {}

  public Long getId() {
    return id;
  }

  public void setId(final Long id) {
    this.id = id;
  }

  public String getLabel() {
    return label;
  }

  public void setLabel(final String label) {
    this.label = label;
  }

  public List<ShippingNote> getNotes() {
    return notes;
  }

  public void setNotes(final List<ShippingNote> notes) {
    this.notes = notes;
  }
}
