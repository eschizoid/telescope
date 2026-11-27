package io.github.eschizoid.telescope.jpa.fixtures;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Version;
import java.util.ArrayList;
import java.util.List;

/** A barrel with a generated identifier, a version and flasks that point back at it. */
@Entity
public class Barrel {

  @Id
  @GeneratedValue
  private Long id;

  @Version
  private int version;

  private String label;

  @OneToMany(mappedBy = "barrel", cascade = CascadeType.ALL, orphanRemoval = true)
  private List<Flask> flasks = new ArrayList<>();

  public Barrel() {}

  public Long getId() {
    return id;
  }

  public void setId(final Long id) {
    this.id = id;
  }

  public int getVersion() {
    return version;
  }

  public void setVersion(final int version) {
    this.version = version;
  }

  public String getLabel() {
    return label;
  }

  public void setLabel(final String label) {
    this.label = label;
  }

  public List<Flask> getFlasks() {
    return flasks;
  }

  public void setFlasks(final List<Flask> flasks) {
    this.flasks = flasks;
  }
}
