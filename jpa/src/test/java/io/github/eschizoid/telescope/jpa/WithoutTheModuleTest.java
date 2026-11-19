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
import io.github.eschizoid.telescope.jpa.fixtures.Basket;
import io.github.eschizoid.telescope.jpa.fixtures.Library;
import io.github.eschizoid.telescope.jpa.fixtures.Line;
import io.github.eschizoid.telescope.jpa.fixtures.Shop;
import jakarta.persistence.PersistenceException;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;
import org.hibernate.Hibernate;
import org.hibernate.LazyInitializationException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The same mappings with this module off the class path, run by the {@code testWithoutTheModule}
 * task alone: each author's lazy books are copied, which loads them one query per author inside the
 * session and throws outside it, a proxy is read as its own class, and a basket's lines written in
 * place are cleared and refilled rather than merged. Names none of the module's classes, which this
 * task does not have.
 */
class WithoutTheModuleTest {

  private static Library library;
  private static Long authorId;
  private static Shop shop;

  @BeforeAll
  static void open() {
    library = Library.open("without-the-module");
    authorId = library.seed();
    shop = Shop.open("without-the-module-shop");
  }

  @AfterAll
  static void close() {
    library.close();
    shop.close();
  }

  @Test
  @DisplayName(
    "into a basket sent back as it is held puts a new instance in place of every line, which the flush refuses"
  )
  void anUnchangedBasketReplacesEveryLine() {
    for (final var path : MergeByIdTest.Path.values()) {
      final var id = shop.seed();
      final var refused = assertThrows(PersistenceException.class, () ->
        shop.withBasket(id, basket -> {
          final var held = List.copyOf(basket.getLines());
          path.into(basket, basket.getName(), lines(basket), Set.of());
          for (var i = 0; i < held.size(); i++) assertNotSame(held.get(i), basket.getLines().get(i), path::name);
          return null;
        })
      );
      assertTrue(refused.getMessage().contains("Detached entity passed to persist"), refused::getMessage);
    }
  }

  @Test
  @DisplayName("into a basket whose lines carry no identifier deletes every held line and inserts every sent one")
  void linesWithNoIdentifierReplaceEveryLine() {
    for (final var path : MergeByIdTest.Path.values()) {
      final var id = shop.seed();
      shop.withBasket(id, basket -> {
        final var sent = lines(basket)
          .stream()
          .map(line -> new MergeByIdTest.Sent(null, line.sku(), line.quantity()))
          .toList();
        path.into(basket, basket.getName(), sent, Set.of());
        return null;
      });
      final var lineStatistics = shop.statistics().getEntityStatistics(Line.class.getName());
      assertEquals(3L, lineStatistics.getDeleteCount(), path::name);
      assertEquals(3L, lineStatistics.getInsertCount(), path::name);
    }
  }

  private static List<MergeByIdTest.Sent> lines(final Basket basket) {
    return basket
      .getLines()
      .stream()
      .map(line -> new MergeByIdTest.Sent(line.getId(), line.getSku(), line.getQuantity()))
      .toList();
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
