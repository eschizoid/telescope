package io.github.eschizoid.telescope.quarkus.deployment;

import java.util.ArrayList;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigValue;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.eclipse.microprofile.config.spi.Converter;

/**
 * The application's configuration with the keys that narrow what SmallRye OpenAPI scans left out:
 * the classes and packages it includes or excludes, and the profiles an operation must carry, so a
 * scan through it reads the generated wire resources wherever the application limits its own scan.
 * Every other key, such as a property naming strategy or a schema mapping, reads as the application
 * sets it, so a wire schema is built as the document builds the others.
 */
final class EveryClassConfig implements Config {

  private static final String SCAN = "mp.openapi.scan.";
  private static final String PROFILES = "mp.openapi.extensions.smallrye.scan.profiles";
  private static final String EXCLUDED_PROFILES = "mp.openapi.extensions.smallrye.scan.exclude.profiles";

  private final Config delegate;

  EveryClassConfig(final Config delegate) {
    this.delegate = delegate;
  }

  private static boolean hidden(final String name) {
    return (
      (name.startsWith(SCAN) && !name.equals("mp.openapi.scan.disable")) ||
      name.equals(PROFILES) ||
      name.equals(EXCLUDED_PROFILES)
    );
  }

  @Override
  public <T> T getValue(final String name, final Class<T> type) {
    if (hidden(name)) throw new NoSuchElementException(name);
    return delegate.getValue(name, type);
  }

  @Override
  public ConfigValue getConfigValue(final String name) {
    return hidden(name) ? new Absent(name) : delegate.getConfigValue(name);
  }

  @Override
  public <T> Optional<T> getOptionalValue(final String name, final Class<T> type) {
    return hidden(name) ? Optional.empty() : delegate.getOptionalValue(name, type);
  }

  @Override
  public Iterable<String> getPropertyNames() {
    final var names = new ArrayList<String>();
    for (final var name : delegate.getPropertyNames()) if (!hidden(name)) names.add(name);
    return names;
  }

  @Override
  public Iterable<ConfigSource> getConfigSources() {
    return delegate.getConfigSources();
  }

  @Override
  public <T> Optional<Converter<T>> getConverter(final Class<T> type) {
    return delegate.getConverter(type);
  }

  @Override
  public <T> T unwrap(final Class<T> type) {
    return delegate.unwrap(type);
  }

  private record Absent(String getName) implements ConfigValue {
    @Override
    public String getValue() {
      return null;
    }

    @Override
    public String getRawValue() {
      return null;
    }

    @Override
    public String getSourceName() {
      return null;
    }

    @Override
    public int getSourceOrdinal() {
      return 0;
    }
  }
}
