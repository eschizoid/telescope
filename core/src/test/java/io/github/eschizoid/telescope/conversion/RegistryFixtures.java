package io.github.eschizoid.telescope.conversion;

import io.github.eschizoid.telescope.Telescope;

/**
 * Hand-registered {@link BridgeProvider}s for {@link BridgeRegistryTest}, wired through {@code
 * core/src/test/resources/META-INF/services/io.github.eschizoid.telescope.conversion.BridgeProvider}.
 * Each provider has a public no-arg constructor so {@link java.util.ServiceLoader} can instantiate
 * it. The marker types stand in for an adopter's cross-package source/target pair.
 */
public final class RegistryFixtures {

  private RegistryFixtures() {}

  /** Sentinel bridge value the happy-path provider hands back. */
  public static final Object BRIDGE = new Object();

  public record Source() {}

  public record Target() {}

  public record NullSource() {}

  public record NullTarget() {}

  public record DupSource() {}

  public record DupTarget() {}

  /** A renaming pair: {@code alpha} on the source maps to the differently-named {@code beta}. */
  public record BridgeSrc(String alpha) {}

  public record BridgeTgt(String beta) {}

  /**
   * A real {@code Telescope<BridgeSrc, BridgeTgt>} that renames {@code alpha → beta} — what a
   * carrier {@code @Bridge} with a {@code @Rename} produces. Same-name {@code DeepMap} would leave
   * {@code beta} null, so routing through this bridge is observably different.
   */
  public static final Object RENAMING_BRIDGE = Telescope.from(BridgeSrc.class)
    .to(BridgeTgt.class)
    .using(s -> new BridgeTgt(s.alpha()), t -> new BridgeSrc(t.beta()));

  /** Happy path: a well-formed provider for {@code (Source, Target)}. */
  public static final class Provider implements BridgeProvider {

    public Provider() {}

    @Override
    public Class<?> sourceType() {
      return Source.class;
    }

    @Override
    public Class<?> targetType() {
      return Target.class;
    }

    @Override
    public Object bridge() {
      return BRIDGE;
    }
  }

  /** Malformed: a structurally present provider whose bridge constant is null. */
  public static final class NullBridgeProvider implements BridgeProvider {

    public NullBridgeProvider() {}

    @Override
    public Class<?> sourceType() {
      return NullSource.class;
    }

    @Override
    public Class<?> targetType() {
      return NullTarget.class;
    }

    @Override
    public Object bridge() {
      return null;
    }
  }

  /** Ambiguous pair: two providers claiming the same {@code (DupSource, DupTarget)}. */
  public static final class DupProviderA implements BridgeProvider {

    public DupProviderA() {}

    @Override
    public Class<?> sourceType() {
      return DupSource.class;
    }

    @Override
    public Class<?> targetType() {
      return DupTarget.class;
    }

    @Override
    public Object bridge() {
      return new Object();
    }
  }

  public static final class DupProviderB implements BridgeProvider {

    public DupProviderB() {}

    @Override
    public Class<?> sourceType() {
      return DupSource.class;
    }

    @Override
    public Class<?> targetType() {
      return DupTarget.class;
    }

    @Override
    public Object bridge() {
      return new Object();
    }
  }

  /**
   * Registers the renaming bridge for {@code (BridgeSrc, BridgeTgt)} — drives the mapperForward
   * test.
   */
  public static final class RenamingProvider implements BridgeProvider {

    public RenamingProvider() {}

    @Override
    public Class<?> sourceType() {
      return BridgeSrc.class;
    }

    @Override
    public Class<?> targetType() {
      return BridgeTgt.class;
    }

    @Override
    public Object bridge() {
      return RENAMING_BRIDGE;
    }
  }
}
