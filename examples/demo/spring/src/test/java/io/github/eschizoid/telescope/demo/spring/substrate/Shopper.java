package io.github.eschizoid.telescope.demo.spring.substrate;

import io.github.eschizoid.telescope.annotations.BeanFocus;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.MapKeyColumn;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * A shopper with a set of tags and a map of preferences, which Hibernate loads as its own {@code
 * PersistentSet} and {@code PersistentMap}. {@code @BeanFocus} generates the {@code
 * ShopperFieldOptics} holder the runtime probes for by class.
 */
@Entity
@BeanFocus
public class Shopper {

  @Id
  @GeneratedValue
  private Long id;

  private String name;

  @ElementCollection(fetch = FetchType.EAGER)
  private Set<String> tags = new LinkedHashSet<>();

  @ElementCollection(fetch = FetchType.EAGER)
  @MapKeyColumn(name = "preference")
  private Map<String, String> preferences = new LinkedHashMap<>();

  public Shopper() {}

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

  public Set<String> getTags() {
    return tags;
  }

  public void setTags(final Set<String> tags) {
    this.tags = tags;
  }

  public Map<String, String> getPreferences() {
    return preferences;
  }

  public void setPreferences(final Map<String, String> preferences) {
    this.preferences = preferences;
  }
}
