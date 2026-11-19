package io.github.eschizoid.telescope.jpa.fixtures.edges;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import java.util.ArrayList;
import java.util.List;

/** One side of a cyclic graph: an alpha's friends are betas, and a beta's friends are alphas. */
@Entity
public class Alpha {

  @Id
  @GeneratedValue
  private Long id;

  private String name;

  @ManyToMany
  @JoinTable(name = "alpha_friends")
  private List<Beta> friends = new ArrayList<>();

  public Alpha() {}

  public Alpha(final String name) {
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

  public List<Beta> getFriends() {
    return friends;
  }

  public void setFriends(final List<Beta> friends) {
    this.friends = friends;
  }
}
