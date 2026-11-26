package io.github.eschizoid.telescope.spring;

import io.github.eschizoid.telescope.internal.pairing.PairingMessages;
import io.github.eschizoid.telescope.mapping.WriteStrategy;
import java.util.Locale;
import org.springframework.core.convert.converter.Converter;

/**
 * Reads {@code telescope.default-write-strategy} as a {@link WriteStrategy}, by constant name in
 * any case and with dashes for underscores. {@code ADDERS}, which is no strategy, is refused with
 * the reason, so an application still naming it fails at startup saying what replaced it.
 */
public final class WriteStrategyConverter implements Converter<String, WriteStrategy> {

  /** The key the converted value is read from. */
  static final String KEY = "telescope.default-write-strategy";

  /** Called by the auto-configuration. */
  public WriteStrategyConverter() {}

  @Override
  public WriteStrategy convert(final String source) {
    final var name = source.trim().replace('-', '_').toUpperCase(Locale.ROOT);
    if (name.equals("ADDERS")) throw new IllegalArgumentException(PairingMessages.addersRemoved(KEY));
    return WriteStrategy.valueOf(name);
  }
}
