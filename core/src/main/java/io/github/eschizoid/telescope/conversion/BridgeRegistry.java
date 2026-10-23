package io.github.eschizoid.telescope.conversion;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/**
 * Package-agnostic lookup of a {@code @Bridge}-generated converter by its {@code (source, target)}
 * pair, over the {@link BridgeProvider} {@link ServiceLoader} registry. Used by {@link
 * io.github.eschizoid.telescope.Telescope#mapperForward(Class, Class,
 * io.github.eschizoid.telescope.mapping.MapStep...)} as the discovery path for carrier-form
 * bridges, which the name-derived sibling probe can't reach (they live in the carrier's package,
 * not the source's), and by every runtime mapper to find the bridge a nested pair's carrier
 * declared.
 *
 * <p>Discovery is loud on malformed input, never silent: a provider whose {@code bridge()} is null,
 * or two providers claiming the same pair, throw {@link IllegalStateException} rather than
 * degrading to the lenient same-name fallback that would drop the bridge's renames. A pair with no
 * provider returns {@link Optional#empty()} — that is the legitimate "no bridge declared" case,
 * where the lenient default is intended.
 */
public final class BridgeRegistry {

  private BridgeRegistry() {}

  private static final Logger LOGGER = System.getLogger(BridgeRegistry.class.getName());

  /**
   * The providers registered for bridges that {@code @Bridge} fields of a class declare, by that
   * class, read once per class: a class's mapper fields are initialised together, so its first
   * factory call reads the registry for the rest.
   */
  private static final ClassValue<List<BridgeProvider>> DECLARED = new ClassValue<>() {
    @Override
    protected List<BridgeProvider> computeValue(final Class<?> declaring) {
      final var loader =
        declaring.getClassLoader() != null ? declaring.getClassLoader() : BridgeRegistry.class.getClassLoader();
      final var found = new ArrayList<BridgeProvider>();
      final var providers = ServiceLoader.load(BridgeProvider.class, loader).iterator();
      while (true) {
        final BridgeProvider provider;
        try {
          if (!providers.hasNext()) break;
          provider = providers.next();
        } catch (final ServiceConfigurationError broken) {
          // An entry naming a class that is missing or cannot be built says nothing of this class's
          // bridges, so it is reported and passed over, and every other provider is still read.
          LOGGER.log(
            Level.WARNING,
            () ->
              "A BridgeProvider registration on the class path cannot be loaded, so it is passed over: " +
              broken.getMessage()
          );
          continue;
        }
        if (provider.declaringType() == declaring) found.add(provider);
      }
      return List.copyOf(found);
    }
  };

  /**
   * The provider of the bridge a {@code @Bridge} field of {@code declaring} declares for {@code
   * (source, target)}, or {@link Optional#empty()} when there is none, as when the processor did
   * not run on that class.
   */
  public static Optional<BridgeProvider> declaredBy(
    final Class<?> declaring,
    final Class<?> source,
    final Class<?> target
  ) {
    for (final var provider : DECLARED.get(declaring)) {
      if (provider.sourceType() == source && provider.targetType() == target) return Optional.of(provider);
    }
    return Optional.empty();
  }

  /**
   * The {@code bridge()} value of the unique {@link BridgeProvider} registered for {@code (source,
   * target)} on {@code loader}, or {@link Optional#empty()} when none is registered.
   *
   * @throws IllegalStateException if the matching provider's bridge constant is null, or if more
   *     than one provider claims the pair
   */
  public static Optional<Object> find(final Class<?> source, final Class<?> target, final ClassLoader loader) {
    // A bootstrap/JDK source type reports a null loader; ServiceLoader.load(svc, null) would search
    // only the bootstrap loader and miss the app-registered provider. Fall back to a loader that
    // can
    // see it. (Unreachable for a real carrier source — always an app-loaded record/POJO — but
    // cheap.)
    final var effective = loader != null ? loader : BridgeRegistry.class.getClassLoader();
    Object match = null;
    for (final BridgeProvider provider : ServiceLoader.load(BridgeProvider.class, effective)) {
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
