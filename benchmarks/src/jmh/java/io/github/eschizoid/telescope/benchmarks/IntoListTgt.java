package io.github.eschizoid.telescope.benchmarks;

import java.util.ArrayList;
import java.util.List;

/** The bean {@link IntoCollectionBenchmark} writes into, holding a modifiable list of its own. */
public class IntoListTgt {

  private String name;
  private List<String> tags = new ArrayList<>();

  public String getName() {
    return name;
  }

  public void setName(final String name) {
    this.name = name;
  }

  public List<String> getTags() {
    return tags;
  }

  public void setTags(final List<String> tags) {
    this.tags = tags;
  }
}
