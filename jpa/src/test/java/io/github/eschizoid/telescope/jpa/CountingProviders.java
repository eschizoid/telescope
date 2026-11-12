package io.github.eschizoid.telescope.jpa;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceConfiguration;
import jakarta.persistence.spi.LoadState;
import jakarta.persistence.spi.PersistenceProvider;
import jakarta.persistence.spi.PersistenceProviderResolver;
import jakarta.persistence.spi.PersistenceProviderResolverHolder;
import jakarta.persistence.spi.PersistenceUnitInfo;
import jakarta.persistence.spi.ProviderUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A provider resolver that puts one provider in front of the class path's, which counts every
 * load-state question it is asked and knows the answer to none, so the providers behind it still
 * answer. Installed for the length of a try block.
 */
final class CountingProviders implements PersistenceProviderResolver, AutoCloseable {

  private final PersistenceProviderResolver previous =
    PersistenceProviderResolverHolder.getPersistenceProviderResolver();
  private final AtomicInteger asked = new AtomicInteger();
  private final PersistenceProvider counting = new Counting();

  private CountingProviders() {}

  /** Installs a counting resolver in front of the current one. */
  static CountingProviders install() {
    final var providers = new CountingProviders();
    PersistenceProviderResolverHolder.setPersistenceProviderResolver(providers);
    return providers;
  }

  /** How many load-state questions a provider has been asked since the install. */
  int asked() {
    return asked.get();
  }

  @Override
  public List<PersistenceProvider> getPersistenceProviders() {
    final var all = new ArrayList<PersistenceProvider>();
    all.add(counting);
    all.addAll(previous.getPersistenceProviders());
    return all;
  }

  @Override
  public void clearCachedProviders() {
    previous.clearCachedProviders();
  }

  @Override
  public void close() {
    PersistenceProviderResolverHolder.setPersistenceProviderResolver(previous);
  }

  private final class Counting implements PersistenceProvider, ProviderUtil {

    @Override
    public LoadState isLoadedWithoutReference(final Object entity, final String attributeName) {
      asked.incrementAndGet();
      return LoadState.UNKNOWN;
    }

    @Override
    public LoadState isLoadedWithReference(final Object entity, final String attributeName) {
      asked.incrementAndGet();
      return LoadState.UNKNOWN;
    }

    @Override
    public LoadState isLoaded(final Object entity) {
      asked.incrementAndGet();
      return LoadState.UNKNOWN;
    }

    @Override
    public ProviderUtil getProviderUtil() {
      return this;
    }

    @Override
    public EntityManagerFactory createEntityManagerFactory(final String emName, final Map<?, ?> map) {
      return null;
    }

    @Override
    public EntityManagerFactory createEntityManagerFactory(final PersistenceConfiguration configuration) {
      return null;
    }

    @Override
    public EntityManagerFactory createContainerEntityManagerFactory(
      final PersistenceUnitInfo info,
      final Map<?, ?> map
    ) {
      return null;
    }

    @Override
    public void generateSchema(final PersistenceUnitInfo info, final Map<?, ?> map) {}

    @Override
    public boolean generateSchema(final String persistenceUnitName, final Map<?, ?> map) {
      return false;
    }
  }
}
