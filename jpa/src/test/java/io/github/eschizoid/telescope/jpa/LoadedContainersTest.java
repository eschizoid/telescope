package io.github.eschizoid.telescope.jpa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.conversion.ContainerCopy;
import io.github.eschizoid.telescope.jpa.fixtures.Author;
import io.github.eschizoid.telescope.jpa.fixtures.AuthorMappers;
import io.github.eschizoid.telescope.jpa.fixtures.AuthorRow;
import io.github.eschizoid.telescope.jpa.fixtures.AuthorView;
import io.github.eschizoid.telescope.jpa.fixtures.Book;
import io.github.eschizoid.telescope.jpa.fixtures.Library;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.hibernate.Hibernate;
import org.hibernate.LazyInitializationException;
import org.hibernate.collection.spi.PersistentBag;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * With this module on the class path, a mapper and a generated bridge share a lazy collection the
 * provider has not loaded instead of copying it, and copy one it has. Hibernate's statistics count
 * the statements: mapping a page of authors whose books the mapping never reads issues the page's
 * one query and nothing else.
 */
class LoadedContainersTest {

  private static Library library;

  @BeforeAll
  static void open() {
    library = Library.open("loaded-containers");
    library.seed();
  }

  @AfterAll
  static void close() {
    library.close();
  }

  @Test
  @DisplayName("the module's container policy is the one core registered")
  void thePolicyIsActive() {
    assertTrue(new LoadedContainers().active());
  }

  @Nested
  @DisplayName("a page of authors whose books are not loaded")
  class APageOfUnloadedCollections {

    @Test
    @DisplayName("maps through the runtime mapper in the page's one query, each row sharing the author's lazy books")
    void theRuntimeMapperIssuesOneQuery() {
      final var statistics = library.statistics();
      final var rows = library.inSession(session -> {
        statistics.clear();
        final var authors = Library.page(session);
        final var mapped = authors.stream().map(AuthorMappers.ROW::forward).toList();
        assertEquals(1L, statistics.getPrepareStatementCount(), "the page's query and nothing else");
        for (var i = 0; i < authors.size(); i++) {
          assertSame(authors.get(i).getBooks(), mapped.get(i).books(), "the lazy books are shared");
          assertFalse(Hibernate.isInitialized(mapped.get(i).books()));
        }
        return mapped;
      });
      assertEquals(Library.AUTHORS, rows.size());
      assertEquals("author 0", rows.getFirst().name());
    }

    @Test
    @DisplayName("maps through the generated bridge in the page's one query, each view sharing the author's lazy books")
    void theGeneratedBridgeIssuesOneQuery() {
      final var statistics = library.statistics();
      library.inSession(session -> {
        statistics.clear();
        final var authors = Library.page(session);
        final List<AuthorView> mapped = authors.stream().map(AuthorMappers.VIEW::forward).toList();
        assertEquals(1L, statistics.getPrepareStatementCount(), "the page's query and nothing else");
        for (var i = 0; i < authors.size(); i++) {
          assertSame(authors.get(i).getBooks(), mapped.get(i).books(), "the lazy books are shared");
        }
        return null;
      });
      assertTrue(
        AuthorMappers.VIEW.explain().toString().contains("AuthorToAuthorViewBridge"),
        () -> "the field runs its generated bridge: " + AuthorMappers.VIEW.explain()
      );
    }

    @Test
    @DisplayName("maps after the session has closed without throwing, on both paths")
    void mapsAfterTheSessionHasClosed() {
      final List<Author> detached = library.inSession(Library::page);
      final List<AuthorRow> rows = detached.stream().map(AuthorMappers.ROW::forward).toList();
      final List<AuthorView> views = detached.stream().map(AuthorMappers.VIEW::forward).toList();
      assertEquals(Library.AUTHORS, rows.size());
      assertEquals(Library.AUTHORS, views.size());
      // The row holds the provider's collection, so reading it now fails as reading the entity's
      // own property would.
      assertThrows(LazyInitializationException.class, () -> rows.getFirst().books().size());
      assertThrows(LazyInitializationException.class, () -> views.getFirst().books().size());
    }
  }

  @Nested
  @DisplayName("an author whose books are loaded")
  class ALoadedCollection {

    @Test
    @DisplayName("is copied into a list of the JDK's own, on both paths, with the books it holds")
    void isCopied() {
      library.inSession(session -> {
        final var author = Library.page(session).getFirst();
        Hibernate.initialize(author.getBooks());
        final var row = AuthorMappers.ROW.forward(author);
        final var view = AuthorMappers.VIEW.forward(author);
        for (final List<Book> books : List.of(row.books(), view.books())) {
          assertNotSame(author.getBooks(), books);
          assertInstanceOf(ArrayList.class, books);
          assertEquals(List.of("first by 0", "second by 0"), books.stream().map(Book::getTitle).toList());
        }
        return null;
      });
    }
  }

  @Nested
  @DisplayName("the load check")
  class TheLoadCheck {

    @Test
    @DisplayName("counts a JDK container as loaded without asking a provider")
    void aJdkContainerIsLoaded() {
      try (final var providers = CountingProviders.install()) {
        assertTrue(LoadStates.loaded(new ArrayList<>()));
        assertTrue(LoadStates.loaded(new HashSet<>()));
        assertEquals(0, providers.asked());
      }
    }

    @Test
    @DisplayName("asks the providers about one of their own collections, loaded or not")
    void aProviderCollectionIsAsked() {
      library.inSession(session -> {
        final var books = Library.page(session).getFirst().getBooks();
        assertInstanceOf(PersistentBag.class, books);
        try (final var providers = CountingProviders.install()) {
          assertFalse(LoadStates.loaded(books));
          Hibernate.initialize(books);
          assertTrue(LoadStates.loaded(books));
          assertEquals(2, providers.asked());
        }
        return null;
      });
    }

    @Test
    @DisplayName("hands a JDK container to the standard copy")
    void aJdkContainerIsCopied() {
      final var source = new HashSet<>(Set.of("a"));
      final var copied = new LoadedContainers().copy(source, Set.class);
      assertNotSame(source, copied);
      assertEquals(source, copied);
      assertEquals(source, ContainerCopy.of(source, Set.class));
    }
  }
}
