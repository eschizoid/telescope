package io.github.eschizoid.telescope.jpa.fixtures;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A basket that owns its lines and its tags: removing one from either collection deletes its row.
 * The lines have no setter, and {@link #addLine} is the only way in, since it sets the line's
 * back-reference. The tags are a set whose elements hash by their label.
 */
@Entity
public class Basket {

  @Id
  @GeneratedValue
  private Long id;

  private String name;

  @OneToMany(mappedBy = "basket", cascade = CascadeType.ALL, orphanRemoval = true)
  @OrderBy("id")
  private final List<Line> lines = new ArrayList<>();

  @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
  @JoinColumn(name = "basket_id")
  private final Set<Tag> tags = new HashSet<>();

  public Basket() {}

  public Basket(final String name) {
    this.name = name;
  }

  public Long getId() {
    return id;
  }

  public void setId(final Long id) {
    this.id = id;
  }

  public String getName() {
    return name;
  }

  public void setName(final String name) {
    this.name = name;
  }

  public List<Line> getLines() {
    return lines;
  }

  public void addLine(final Line line) {
    line.attachTo(this);
    lines.add(line);
  }

  public Set<Tag> getTags() {
    return tags;
  }

  public void addTag(final Tag tag) {
    tags.add(tag);
  }
}
