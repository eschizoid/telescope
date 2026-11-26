package io.github.eschizoid.telescope.jpa.fixtures;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import java.util.ArrayList;
import java.util.List;

/**
 * A rack that owns its jars through an adder and a remover, which keep each jar's back-reference;
 * the list has no setter, and a jar taken out is deleted as an orphan.
 */
@Entity
public class Rack {

  @Id
  @GeneratedValue
  private Long id;

  private String name;

  @OneToMany(mappedBy = "rack", cascade = CascadeType.ALL, orphanRemoval = true)
  @OrderBy("id")
  private final List<Jar> jars = new ArrayList<>();

  public Rack() {}

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

  public List<Jar> getJars() {
    return jars;
  }

  public void addJar(final Jar jar) {
    jar.setRack(this);
    jars.add(jar);
  }

  public void removeJar(final Jar jar) {
    jars.remove(jar);
    jar.setRack(null);
  }
}
