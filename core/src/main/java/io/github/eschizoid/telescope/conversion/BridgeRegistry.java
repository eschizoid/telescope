package io.github.eschizoid.telescope.conversion;

import java.util.Optional;
import java.util.ServiceLoader;

/**
 * Package-agnostic lookup of a {@code @Bridge}-generated converter by its {@code (source, target)}
 * pair, over the {@link BridgeProvider} {@link ServiceLoader} registry. Used by {@link
 * io.github.eschizoid.telescope.Telescope#mapperForward(Class, Class,
 * io.github.eschizoid.telescope.mapping.MapStep...)} as the discovery path for carrier-form
 * bridges, which the name-derived sibling probe can't reach (they live in the carrier's package,
 * not the source's).
 *
 * <p>Discovery is loud on malformed input, never silent: a provider whose {@code bridge()} is null,
 * or two providers claiming the same pair, throw {@link IllegalStateException} rather than
 * degrading to the lenient same-name fallback that would drop the bridge's renames. A pair with no
 * provider returns {@link Optional#empty()} — that is the legitimate "no bridge declared" case,
 * where the lenient default is intended.
 */
public final class BridgeRegistry {

  private BridgeRegistry() {}

  /**
   * The {@code bridge()} value of the unique {@link BridgeProvider} registered for {@code (source,
   * target)} on {@code loader}, or {@link Optional#empty()} when none is registered.
   *
   * @throws IllegalStateException if the matching provider's bridge constant is null, or if more
   *     than one provider claims the pair
   */
  public static Optional<Object> find(final Class<?> source, final Class<?> target, final ClassLoader loader) {
    Object match = null;
    for (final BridgeProvider provider : ServiceLoader.load(BridgeProvider.class, loader)) {
      if (provider.sourceType() != source || provider.targetType() != target) continue;
      if (match != null) {
        throw new IllegalStateException(
          "Ambiguous @Bridge providers for (" +
            source.getName() +
            ", " +
            target.getName() +
            ") — two carrier @Bridge declarations target the same pair. Remove one, or pass the bridge " +
            "explicitly via from(source).to(target).using(...)."
        );
      }
      final var bridge = provider.bridge();
      if (bridge == null) {
        throw new IllegalStateException(
          "@Bridge provider " +
            provider.getClass().getName() +
            " for (" +
            source.getName() +
            ", " +
            target.getName() +
            ") has a null bridge constant. Re-run the @Bridge processor — the generated class is malformed."
        );
      }
      match = bridge;
    }
    return Optional.ofNullable(match);
  }
}
