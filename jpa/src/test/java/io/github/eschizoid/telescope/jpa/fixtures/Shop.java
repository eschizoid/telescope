package io.github.eschizoid.telescope.jpa.fixtures;

import java.util.function.Function;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.stat.Statistics;

/**
 * A Hibernate session factory over an in-memory database of baskets, with statistics on, so a test
 * can count the statements a write into a managed basket issues at flush.
 */
public final class Shop implements AutoCloseable {

  private final SessionFactory sessions;

  private Shop(final SessionFactory sessions) {
    this.sessions = sessions;
  }

  /** A shop over a database of its own, named {@code name}. */
  public static Shop open(final String name) {
    final var registry = new StandardServiceRegistryBuilder()
      .applySetting("hibernate.connection.url", "jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1")
      .applySetting("hibernate.hbm2ddl.auto", "create-drop")
      .applySetting("hibernate.generate_statistics", "true")
      .build();
    final var sessions = new MetadataSources(registry)
      .addAnnotatedClass(Basket.class)
      .addAnnotatedClass(Line.class)
      .addAnnotatedClass(Tag.class)
      .addAnnotatedClass(Crate.class)
      .addAnnotatedClass(Bottle.class)
      .addAnnotatedClass(Rack.class)
      .addAnnotatedClass(Jar.class)
      .addAnnotatedClass(Pallet.class)
      .addAnnotatedClass(Sack.class)
      .addAnnotatedClass(UOrder.class)
      .addAnnotatedClass(ULine.class)
      .buildMetadata()
      .buildSessionFactory();
    return new Shop(sessions);
  }

  /** Writes a basket of three lines and two tags, and answers its id. */
  public Long seed() {
    return inSession(session -> {
      final var basket = new Basket("weekly");
      basket.addLine(new Line("apple", 1, "from the orchard"));
      basket.addLine(new Line("bread", 2, "sliced"));
      basket.addLine(new Line("milk", 3, "skimmed"));
      basket.getTags().add(new Tag("red"));
      basket.getTags().add(new Tag("green"));
      session.persist(basket);
      return basket.getId();
    });
  }

  /**
   * The basket {@code id} with both collections loaded, from a session of its own in which {@code
   * work} runs and is flushed, the statistics counting only what the work and the flush issue.
   * Answers what {@code work} answers.
   */
  public <T> T withBasket(final Long id, final Function<Basket, T> work) {
    return inSession(session -> {
      final var basket = session.find(Basket.class, id);
      basket.getLines().size();
      basket.getTags().size();
      statistics().clear();
      final var answer = work.apply(basket);
      session.flush();
      return answer;
    });
  }

  /**
   * The entity {@code id} of {@code type}, with what {@code load} reads loaded, from a session of
   * its own in which {@code work} runs and is flushed, the statistics counting only what the work
   * and the flush issue. Answers what {@code work} answers.
   */
  public <E, T> T withEntity(final Class<E> type, final Long id, final Function<E, ?> load, final Function<E, T> work) {
    return inSession(session -> {
      final var entity = session.find(type, id);
      load.apply(entity);
      statistics().clear();
      final var answer = work.apply(entity);
      session.flush();
      return answer;
    });
  }

  /** The work's answer, from a session and transaction of its own. */
  public <T> T inSession(final Function<Session, T> work) {
    return sessions.fromTransaction(work::apply);
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
