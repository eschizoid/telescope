package io.github.eschizoid.telescope.quarkus.tests.unpaired.elsewhere;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.conversion.BridgeFn;
import io.github.eschizoid.telescope.conversion.BridgeProvider;
import io.github.eschizoid.telescope.quarkus.tests.unpaired.Badge;
import io.github.eschizoid.telescope.quarkus.tests.unpaired.Unrelated;

/**
 * A provider registering a bridge for a pair whose components share no name, from a package other
 * than the source's, so the forward mapping finds it only through the registration.
 */
public final class BadgeBridgeProvider implements BridgeProvider {

  @Override
  public Class<?> sourceType() {
    return Badge.class;
  }

  @Override
  public Class<?> targetType() {
    return Unrelated.class;
  }

  @Override
  public Object bridge() {
    return Telescope.bridge(
      new BridgeFn<Badge, Unrelated>() {
        @Override
        public Unrelated forward(final Badge source) {
          return new Unrelated(source.label(), 0);
        }

        @Override
        public Badge backward(final Unrelated target) {
          return new Badge(target.zzz());
        }
      }
    );
  }
}
