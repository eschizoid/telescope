package io.github.eschizoid.telescope.jpa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.Sources;
import io.github.eschizoid.telescope.conversion.ContainerCopy;
import io.github.eschizoid.telescope.conversion.ProxyResolver;
import io.github.eschizoid.telescope.internal.Beans;
import io.github.eschizoid.telescope.jpa.fixtures.Author;
import io.github.eschizoid.telescope.jpa.fixtures.AuthorMappers;
import io.github.eschizoid.telescope.jpa.fixtures.Library;
import java.util.ServiceLoader;
import org.hibernate.Hibernate;
import org.hibernate.LazyInitializationException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The same mappings with this module off the class path, run by the {@code testWithoutTheModule}
 * task alone: each author's lazy books are copied, which loads them one query per author inside the
 * session and throws outside it, and a proxy is read as its own class. Names none of the module's
 * classes, which this task does not have.
 */
class WithoutTheModuleTest {

  private static Library library;
  private static Long authorId;

  @BeforeAll
  static void open() {
    library = Library.open("without-the-module");
    authorId = library.seed();
  }

  @AfterAll
  static void close() {
    library.close();
  }

  @Test
  @DisplayName("neither of the module's providers is registered")
  void nothingIsRegistered() {
    assertFalse(ServiceLoader.load(ContainerCopy.Policy.class).iterator().hasNext());
    assertFalse(ServiceLoader.load(ProxyResolver.class).iterator().hasNext());
  }

  @Test
  @DisplayName("a page of authors maps in one query for the page and one per author, on both paths")
  void aPageLoadsEveryAuthorsBooks() {
    final var statistics = library.statistics();
    for (final var path : AuthorPaths.values()) {
      library.inSession(session -> {
        statistics.clear();
        final var authors = Library.page(session);
        for (final var author : authors) {
          final var books = path.booksOf(author);
          assertNotSame(author.getBooks(), books);
          assertTrue(Hibernate.isInitialized(author.getBooks()), "copying the books loaded them");
        }
        assertEquals(
          1L + Library.AUTHORS,
          statistics.getPrepareStatementCount(),
          () -> path + ": the page's query and one per author"
        );
        return null;
      });
    }
  }

  @Test
  @DisplayName("mapping an author after the session has closed throws, on both paths")
  void mappingAfterTheSessionThrows() {
    final var detached = library.inSession(Library::page).getFirst();
    for (final var path : AuthorPaths.values()) {
      assertThrows(LazyInitializationException.class, () -> path.booksOf(detached), path::toString);
    }
  }

  @Test
  @DisplayName("a proxy is read as its own proxy class, so a merge source of it is not filed under its entity")
  void aProxyIsItsOwnClass() {
    library.inSession(session -> {
      final var proxy = session.getReference(Author.class, authorId);
      assertEquals(proxy.getClass(), Beans.persistentClassOf(proxy));
      assertNull(Sources.of(proxy).byClass(Author.class));
      return null;
    });
  }

  /** The two paths an author's books are mapped through. */
  private enum AuthorPaths {
    RUNTIME {
      @Override
      Object booksOf(final Author author) {
        return AuthorMappers.ROW.forward(author).books();
      }
    },
    GENERATED {
      @Override
      Object booksOf(final Author author) {
        return AuthorMappers.VIEW.forward(author).books();
      }
    };

    abstract Object booksOf(Author author);
  }
}
