package io.github.eschizoid.telescope.spring.web.fixtures;

/** A projection interface, read by its getters, of the kind a Spring Data repository returns. */
public interface CityView {
  String getCity();
}
