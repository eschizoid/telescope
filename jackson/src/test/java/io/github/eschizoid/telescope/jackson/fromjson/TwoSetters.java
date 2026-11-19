package io.github.eschizoid.telescope.jackson.fromjson;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.github.eschizoid.telescope.jackson.FromJson;
import java.util.Objects;

/**
 * A bean whose property has two setters, one of them ignored, and a primitive setter behind a box.
 */
@FromJson
public final class TwoSetters {

  private String label;
  private int size;

  /** Creates an empty bean. */
  public TwoSetters() {}

  public String getLabel() {
    return label;
  }

  public void setLabel(final String label) {
    this.label = label;
  }

  @JsonIgnore
  public void setLabel(final Object label) {
    this.label = "object:" + label;
  }

  public Integer getSize() {
    return size;
  }

  public void setSize(final int size) {
    this.size = size;
  }

  @Override
  public boolean equals(final Object other) {
    return other instanceof TwoSetters that && size == that.size && Objects.equals(label, that.label);
  }

  @Override
  public int hashCode() {
    return Objects.hash(label, size);
  }

  @Override
  public String toString() {
    return "TwoSetters[" + label + ", " + size + "]";
  }
}
