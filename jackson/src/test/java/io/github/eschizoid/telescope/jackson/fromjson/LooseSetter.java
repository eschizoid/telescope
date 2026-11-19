package io.github.eschizoid.telescope.jackson.fromjson;

import io.github.eschizoid.telescope.jackson.FromJson;
import java.util.Objects;

/** A bean whose setter takes a wider type than its getter returns. */
@FromJson
public final class LooseSetter {

  private String name;

  /** Creates an empty bean. */
  public LooseSetter() {}

  public String getName() {
    return name;
  }

  public void setName(final CharSequence name) {
    this.name = name == null ? null : name.toString();
  }

  @Override
  public boolean equals(final Object other) {
    return other instanceof LooseSetter that && Objects.equals(name, that.name);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(name);
  }

  @Override
  public String toString() {
    return "LooseSetter[" + name + "]";
  }
}
