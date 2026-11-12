package io.github.eschizoid.telescope.jpa.fixtures;

import java.util.List;
import java.util.function.Function;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.stat.Statistics;

/**
 * A Hibernate session factory over an in-memory database of authors and their books, with
 * statistics on, so a test can count the statements a mapping issues.
 */
public final class Library implements AutoCloseable {

  /** How many authors {@link #seed} writes, each with two books. */
  public static final int AUTHORS = 5;

  private final SessionFactory sessions;

  private Library(final SessionFactory sessions) {
    this.sessions = sessions;
  }

  /** A library over a database of its own, named {@code name}. */
  public static Library open(final String name) {
    final var registry = new StandardServiceRegistryBuilder()
      .applySetting("hibernate.connection.url", "jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1")
      .applySetting("hibernate.hbm2ddl.auto", "create-drop")
      .applySetting("hibernate.generate_statistics", "true")
      .build();
    final var sessions = new MetadataSources(registry)
      .addAnnotatedClass(Author.class)
      .addAnnotatedClass(Book.class)
      .buildMetadata()
      .buildSessionFactory();
    return new Library(sessions);
  }

  /** Writes {@link #AUTHORS} authors, each with two books, and answers the first author's id. */
  public Long seed() {
    return inSession(session -> {
      Long first = null;
      for (var i = 0; i < AUTHORS; i++) {
        final var author = new Author("author " + i);
        session.persist(author);
        session.persist(new Book("first by " + i, author));
        session.persist(new Book("second by " + i, author));
        if (first == null) {
          first = author.getId();
        }
      }
      return first;
    });
  }

  /** The work's answer, from a session and transaction of its own. */
  public <T> T inSession(final Function<Session, T> work) {
    return sessions.fromTransaction(work::apply);
  }

  /** Every author, in id order, as a page of a list endpoint reads them. */
  public static List<Author> page(final Session session) {
    return session.createSelectionQuery("from Author order by id", Author.class).getResultList();
  }

  /** The factory's statistics, which count every statement prepared. */
  public Statistics statistics() {
    return sessions.getStatistics();
  }

  @Override
  public void close() {
    sessions.close();
  }
}
