package io.github.eschizoid.telescope.jpa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.Sources;
import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.internal.Beans;
import io.github.eschizoid.telescope.jpa.fixtures.Author;
import io.github.eschizoid.telescope.jpa.fixtures.Library;
import org.hibernate.Hibernate;
import org.hibernate.proxy.HibernateProxy;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * With this module on the class path, core reads a Hibernate proxy as the entity class it stands
 * for, without loading its row.
 */
class HibernateProxiesTest {

  private static Library library;
  private static Long authorId;

  @BeforeAll
  static void open() {
    library = Library.open("hibernate-proxies");
    authorId = library.seed();
  }

  @AfterAll
  static void close() {
    library.close();
  }

  @Test
  @DisplayName("resolves a proxy to its entity class without initializing it")
  void resolvesAProxyWithoutInitializingIt() {
    library.inSession(session -> {
      final var proxy = session.getReference(Author.class, authorId);
      assertInstanceOf(HibernateProxy.class, proxy);
      assertNotEquals(Author.class, proxy.getClass());
      assertEquals(Author.class, new HibernateProxies().classOf(proxy));
      assertFalse(Hibernate.isInitialized(proxy), "resolving does not load the row");
      return null;
    });
  }

  @Test
  @DisplayName("knows nothing of a value that is not a proxy")
  void answersNullForAnEntity() {
    assertNull(new HibernateProxies().classOf(new Author("a")));
    assertNull(new HibernateProxies().classOf("not an entity"));
  }

  @Test
  @DisplayName("is active, since Hibernate is on the class path")
  void isActive() {
    assertTrue(new HibernateProxies().active());
  }

  @Test
  @DisplayName("is what core keys a proxy on: its caches, a merge source and a bean path read it as its entity")
  void coreReadsAProxyAsItsEntity() {
    library.inSession(session -> {
      final var proxy = session.getReference(Author.class, authorId);
      assertEquals(Author.class, Beans.persistentClassOf(proxy));
      assertSame(proxy, Sources.of(proxy).byClass(Author.class));
      assertSame(proxy, Sources.builder().with(proxy).build().byClass(Author.class));
      assertFalse(Hibernate.isInitialized(proxy));
      assertEquals("author 0", Telescope.ofBean(Author.class).field(Author::getName).read(proxy));
      return null;
    });
  }
}
