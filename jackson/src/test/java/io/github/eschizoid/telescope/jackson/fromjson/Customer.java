package io.github.eschizoid.telescope.jackson.fromjson;

import io.github.eschizoid.telescope.jackson.FromJson;
import java.util.List;
import java.util.Objects;

/** A bean built through its no-argument constructor and setters. */
@FromJson
public final class Customer {

  private String name;
  private int visits;
  private List<String> tags;

  /** Creates an empty customer. */
  public Customer() {}

  public String getName() {
    return name;
  }

  public void setName(final String name) {
    this.name = name;
  }

  public int getVisits() {
    return visits;
  }

  public void setVisits(final int visits) {
    this.visits = visits;
  }

  public List<String> getTags() {
    return tags;
  }

  public void setTags(final List<String> tags) {
    this.tags = tags;
  }

  /** A derived value with no setter, which the binder never writes. */
  public String getLabel() {
    return name + "#" + visits;
  }

  @Override
  public boolean equals(final Object other) {
    return (
      other instanceof Customer that &&
      visits == that.visits &&
      Objects.equals(name, that.name) &&
      Objects.equals(tags, that.tags)
    );
  }

  @Override
  public int hashCode() {
    return Objects.hash(name, visits, tags);
  }

  @Override
  public String toString() {
    return "Customer[" + name + ", " + visits + ", " + tags + "]";
  }
}
