package io.github.eschizoid.telescope.conversion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The package-agnostic {@code @Bridge} discovery registry. A carrier-form bridge lives in the
 * carrier's package — a name-derived probe keyed off the source class can't find it — so the
 * registry locates it by {@code (source, target)} through a {@link java.util.ServiceLoader} SPI any
 * package can register with. These tests hand-register {@link RegistryFixtures} providers via a
 * {@code META-INF/services} entry to exercise the lookup independently of codegen.
 */
class BridgeRegistryTest {

  @Test
  @DisplayName("finds a registered provider by its (source, target) pair, returning its bridge")
  void findsRegisteredProviderByPair() {
    final var found = BridgeRegistry.find(
      RegistryFixtures.Source.class,
      RegistryFixtures.Target.class,
      getClass().getClassLoader()
    );
    assertTrue(found.isPresent());
    assertSame(RegistryFixtures.BRIDGE, found.get());
  }

  @Test
  @DisplayName("returns empty when no registered provider matches the pair (lenient default preserved)")
  void emptyWhenNoProviderMatches() {
    final var found = BridgeRegistry.find(RegistryFixtures.Source.class, String.class, getClass().getClassLoader());
    assertTrue(found.isEmpty());
  }

  @Test
  @DisplayName("a provider whose bridge constant is null fails loudly rather than degrading to lenient")
  void nullBridgeProviderFailsLoudly() {
    final var ex = assertThrows(IllegalStateException.class, () ->
      BridgeRegistry.find(
        RegistryFixtures.NullSource.class,
        RegistryFixtures.NullTarget.class,
        getClass().getClassLoader()
      )
    );
    assertTrue(ex.getMessage().contains("null"));
  }

  @Test
  @DisplayName("two providers for the same pair fail loudly rather than silently picking one")
  void ambiguousProvidersFailLoudly() {
    final var ex = assertThrows(IllegalStateException.class, () ->
      BridgeRegistry.find(
        RegistryFixtures.DupSource.class,
        RegistryFixtures.DupTarget.class,
        getClass().getClassLoader()
      )
    );
    assertEquals(true, ex.getMessage().toLowerCase().contains("ambiguous"));
  }
}
