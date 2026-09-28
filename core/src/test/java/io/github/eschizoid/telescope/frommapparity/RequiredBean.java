package io.github.eschizoid.telescope.frommapparity;

import io.github.eschizoid.telescope.annotations.FromMap;

/** The bean shape of {@link RequiredRow}, written through its setters. */
@FromMap(required = { "count", "id" })
public class RequiredBean {

  private String id;
  private int count;
  private String note;

  public String getId() {
    return id;
  }

  public void setId(final String id) {
    this.id = id;
  }

  public int getCount() {
    return count;
  }

  public void setCount(final int count) {
    this.count = count;
  }

  public String getNote() {
    return note;
  }

  public void setNote(final String note) {
    this.note = note;
  }
}
