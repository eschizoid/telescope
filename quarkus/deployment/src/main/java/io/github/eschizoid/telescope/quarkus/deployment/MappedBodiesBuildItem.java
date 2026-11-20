package io.github.eschizoid.telescope.quarkus.deployment;

import io.quarkus.builder.item.SimpleBuildItem;
import java.util.List;

/** Every mapped body the build found in the application's index. */
public final class MappedBodiesBuildItem extends SimpleBuildItem {

  private final List<MappedBody> bodies;

  public MappedBodiesBuildItem(final List<MappedBody> bodies) {
    this.bodies = List.copyOf(bodies);
  }

  public List<MappedBody> bodies() {
    return bodies;
  }

  boolean hasResponses() {
    return bodies.stream().anyMatch(body -> !body.request());
  }

  boolean hasRequests() {
    return bodies.stream().anyMatch(MappedBody::request);
  }
}
