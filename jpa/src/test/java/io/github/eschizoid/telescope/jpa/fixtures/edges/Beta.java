package io.github.eschizoid.telescope.jpa.fixtures.edges;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import java.util.ArrayList;
import java.util.List;

/** The other side of {@link Alpha}'s cyclic graph. */
@Entity
public class Beta {

  @Id
  @GeneratedValue
  private Long id;

  private String name;

  @ManyToMany
  @JoinTable(name = "beta_friends")
  private List<Alpha> friends = new ArrayList<>();

  public Beta() {}

  public Beta(final String name) {
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

  public List<Alpha> getFriends() {
    return friends;
  }

  public void setFriends(final List<Alpha> friends) {
    this.friends = friends;
  }
}
