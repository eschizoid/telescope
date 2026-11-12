package io.github.eschizoid.telescope.quarkus;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.eschizoid.telescope.conversion.ContainerCopy;
import io.github.eschizoid.telescope.conversion.ProxyResolver;
import java.util.ServiceLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The extension brings telescope-jpa, whose providers register themselves with core and apply only
 * where {@code jakarta.persistence} is on the class path. This module's tests run without it, so
 * both providers are found and both stay inactive.
 */
class TelescopeJpaWiringTest {

  @Test
  @DisplayName("telescope-jpa's providers come with the extension, and are inactive without jakarta.persistence")
  void theJpaProvidersComeWithTheExtensionAndWaitForJpa() {
    assertThat(present("jakarta.persistence.Persistence")).as("no JPA on this module's test path").isFalse();
    final var policies = ServiceLoader.load(ContainerCopy.Policy.class)
      .stream()
      .map(ServiceLoader.Provider::get)
      .toList();
    assertThat(policies)
      .singleElement()
      .satisfies(policy -> {
        assertThat(policy.getClass().getName()).isEqualTo("io.github.eschizoid.telescope.jpa.LoadedContainers");
        assertThat(policy.active()).isFalse();
      });
    final var resolvers = ServiceLoader.load(ProxyResolver.class).stream().map(ServiceLoader.Provider::get).toList();
    assertThat(resolvers)
      .singleElement()
      .satisfies(resolver -> {
        assertThat(resolver.getClass().getName()).isEqualTo("io.github.eschizoid.telescope.jpa.HibernateProxies");
        assertThat(resolver.active()).isFalse();
      });
  }

  private static boolean present(final String name) {
    try {
      Class.forName(name, false, TelescopeJpaWiringTest.class.getClassLoader());
      return true;
    } catch (final ClassNotFoundException absent) {
      return false;
    }
  }
}
