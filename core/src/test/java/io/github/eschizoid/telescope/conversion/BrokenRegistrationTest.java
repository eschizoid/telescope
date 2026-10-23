package io.github.eschizoid.telescope.conversion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A {@code META-INF/services} entry naming a class that is not there says nothing of a class's
 * {@code @Bridge} fields, so looking up the bridges a class declares passes over it and still reads
 * every provider that loads.
 */
class BrokenRegistrationTest {

  /**
   * A class defined by the loader that carries the broken entry, so the lookup reads that loader.
   */
  public static final class Probe {}

  @Test
  @DisplayName("a broken provider entry is passed over, and the providers that load are still found")
  void aBrokenEntryIsPassedOver() throws Exception {
    final var dir = Files.createTempDirectory("broken-registration");
    final var services = dir.resolve("META-INF/services/" + BridgeProvider.class.getName());
    Files.createDirectories(services.getParent());
    Files.writeString(services, "no.such.BridgeProviderThatIsNotThere\n");
    final var name = Probe.class.getName();
    final byte[] bytes;
    try (final var in = Probe.class.getResourceAsStream("BrokenRegistrationTest$Probe.class")) {
      bytes = in.readAllBytes();
    }
    try (
      final var loader = new URLClassLoader(new URL[] { dir.toUri().toURL() }, getClass().getClassLoader()) {
        @Override
        protected Class<?> loadClass(final String className, final boolean resolve) throws ClassNotFoundException {
          if (!className.equals(name)) return super.loadClass(className, resolve);
          synchronized (getClassLoadingLock(className)) {
            final var loaded = findLoadedClass(className);
            return loaded != null ? loaded : defineClass(className, bytes, 0, bytes.length);
          }
        }
      }
    ) {
      final var probe = loader.loadClass(name);
      assertTrue(probe.getClassLoader() == loader, "the probe is defined by the loader with the broken entry");
      assertTrue(BridgeRegistry.declaredBy(probe, String.class, String.class).isEmpty());
      assertEquals(
        RegistryFixtures.BRIDGE,
        BridgeRegistry.find(
          RegistryFixtures.Source.class,
          RegistryFixtures.Target.class,
          getClass().getClassLoader()
        ).orElseThrow()
      );
    } catch (final IOException e) {
      throw new AssertionError(e);
    }
  }
}
