package io.github.eschizoid.telescope.collections;

import java.util.ArrayList;
import java.util.List;

/** A bean whose adder keeps each tag once. */
public class ATagged {

  private String name;
  private List<String> tags = new ArrayList<>();

  public ATagged() {}

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

  public void addTag(final String tag) {
    if (!tags.contains(tag)) tags.add(tag);
  }
}
