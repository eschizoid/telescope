package io.github.eschizoid.telescope.jpa.fixtures;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Version;

/**
 * An entity whose identifier and version have a getter and no setter, as the provider fills both.
 */
@Entity
public class Ledger {

  @Id
  @GeneratedValue
  private Long id;

  @Version
  private int version;

  private String note;

  public Ledger() {}

  /** A ledger as the provider would have loaded it. */
  public Ledger(final Long id, final int version) {
    this.id = id;
    this.version = version;
  }

  public Long getId() {
    return id;
  }

  public int getVersion() {
    return version;
  }

  public String getNote() {
    return note;
  }

  public void setNote(final String note) {
    this.note = note;
  }
}
