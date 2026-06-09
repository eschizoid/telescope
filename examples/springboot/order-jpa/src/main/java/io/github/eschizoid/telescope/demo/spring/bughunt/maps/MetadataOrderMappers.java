package io.github.eschizoid.telescope.demo.spring.bughunt.maps;

import static io.github.eschizoid.telescope.mapping.WriteHint.WriteStrategy.SETTERS;
import static io.github.eschizoid.telescope.mapping.WriteHint.writeBeans;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.conversion.Mapper;

/**
 * Factories for the bug-hunt slice. Pure static — no Spring wiring needed because the slice's tests
 * exercise the conversion surface directly, not over HTTP. Lattice-first: both mappers build their
 * {@code Iso<X, Y>} through {@link Telescope#mapper}, which routes the {@code Map<K, V>} component
 * through {@code DeepMap#autoIso} → {@code Iso.liftMapValues} (see {@code MAP_VALUES} branch).
 */
final class MetadataOrderMappers {

  private MetadataOrderMappers() {}

  static Mapper<MetadataOrder, MetadataOrderEntity> mapper() {
    return Telescope.mapper(MetadataOrder.class, MetadataOrderEntity.class, writeBeans(SETTERS));
  }
}
