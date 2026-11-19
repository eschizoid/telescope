package io.github.eschizoid.telescope.jpa.fixtures.edges;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Version;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Entity
public class Cart {

  @Id
  @GeneratedValue
  private Long id;

  @Version
  private Integer version;

  private String name;

  @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
  @JoinColumn(name = "cart_n")
  @OrderBy("id")
  private List<Note> notes = new ArrayList<>();

  @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
  @JoinColumn(name = "cart_s")
  @OrderColumn(name = "pos")
  private List<Step> steps = new ArrayList<>();

  @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
  @JoinColumn(name = "cart_t")
  private Set<Label> tags = new HashSet<>();

  @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
  @JoinColumn(name = "cart_p")
  @OrderBy("id")
  private List<Pin> prims = new ArrayList<>();

  public Cart() {}

  public Long getId() {
    return id;
  }

  public void setId(final Long id) {
    this.id = id;
  }

  public Integer getVersion() {
    return version;
  }

  public void setVersion(final Integer version) {
    this.version = version;
  }

  public String getName() {
    return name;
  }

  public void setName(final String name) {
    this.name = name;
  }

  public List<Note> getNotes() {
    return notes;
  }

  public List<Step> getSteps() {
    return steps;
  }

  public Set<Label> getTags() {
    return tags;
  }

  public List<Pin> getPrims() {
    return prims;
  }

  public void setNotes(final List<Note> notes) {
    this.notes = notes;
  }

  public void setSteps(final List<Step> steps) {
    this.steps = steps;
  }

  public void setTags(final Set<Label> tags) {
    this.tags = tags;
  }

  public void setPrims(final List<Pin> prims) {
    this.prims = prims;
  }
}
