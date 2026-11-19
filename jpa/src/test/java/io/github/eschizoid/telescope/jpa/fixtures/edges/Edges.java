package io.github.eschizoid.telescope.jpa.fixtures.edges;

import java.util.function.Function;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.stat.Statistics;

/**
 * A Hibernate session factory over carts, boxes and groups, the owners whose collections the edge
 * cases of a merge by identifier write into, with statistics on.
 *
 * <ul>
 *   <li>A {@link Cart} is versioned, and holds a list mapped through a join column, a list with an
 *       order column, a set of {@link Label}s equal by their text, and {@link Pin}s whose
 *       identifier is a primitive {@code long}.
 *   <li>A {@link Box} holds notes a {@code via} row converts.
 *   <li>A {@link Group} holds rows that each hold cells, two levels of collections.
 *   <li>An {@link Alpha} and a {@link Beta} are each other's friends, a cyclic graph.
 * </ul>
 */
public final class Edges implements AutoCloseable {

  private final SessionFactory sessions;

  private Edges(final SessionFactory sessions) {
    this.sessions = sessions;
  }

  /** A factory over a database of its own, named {@code name}. */
  public static Edges open(final String name) {
    final var registry = new StandardServiceRegistryBuilder()
      .applySetting("hibernate.connection.url", "jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1")
      .applySetting("hibernate.hbm2ddl.auto", "create-drop")
      .applySetting("hibernate.generate_statistics", "true")
      .build();
    return new Edges(
      new MetadataSources(registry)
        .addAnnotatedClass(Cart.class)
        .addAnnotatedClass(Note.class)
        .addAnnotatedClass(Step.class)
        .addAnnotatedClass(Label.class)
        .addAnnotatedClass(Pin.class)
        .addAnnotatedClass(Box.class)
        .addAnnotatedClass(Group.class)
        .addAnnotatedClass(Row.class)
        .addAnnotatedClass(Cell.class)
        .addAnnotatedClass(Alpha.class)
        .addAnnotatedClass(Beta.class)
        .buildMetadata()
        .buildSessionFactory()
    );
  }

  /** Writes a cart of three notes, three steps, two labels and two pins, and answers its id. */
  public Long cart() {
    return inSession(session -> {
      final var cart = new Cart();
      cart.setName("cart");
      for (final var text : new String[] { "n1", "n2", "n3" }) cart.getNotes().add(new Note(text));
      for (final var text : new String[] { "s1", "s2", "s3" }) cart.getSteps().add(new Step(text));
      cart.getTags().add(new Label("red"));
      cart.getTags().add(new Label("green"));
      cart.getPrims().add(new Pin("p1"));
      cart.getPrims().add(new Pin("p2"));
      session.persist(cart);
      return cart.getId();
    });
  }

  /** Writes a box of two notes, and answers its id. */
  public Long box() {
    return inSession(session -> {
      final var box = new Box();
      box.getNotes().add(new Note("b1"));
      box.getNotes().add(new Note("b2"));
      session.persist(box);
      return box.getId();
    });
  }

  /** Writes a group of two rows of one cell each, and answers its id. */
  public Long group() {
    return inSession(session -> {
      final var group = new Group();
      group.setName("g");
      for (final var text : new String[] { "l1", "l2" }) {
        final var row = new Row(text);
        row.getSubs().add(new Cell(text + "a"));
        group.getLines().add(row);
      }
      session.persist(group);
      return group.getId();
    });
  }

  /** Writes an alpha and a beta that are each other's only friend, and answers the alpha's id. */
  public Long friends() {
    return inSession(session -> {
      final var alpha = new Alpha("a");
      final var beta = new Beta("b");
      alpha.getFriends().add(beta);
      beta.getFriends().add(alpha);
      session.persist(beta);
      session.persist(alpha);
      return alpha.getId();
    });
  }

  /**
   * {@code work} on the {@code type} {@code id}, loaded with every collection it holds read, in a
   * session of its own that is flushed after it, the statistics counting only what the work and the
   * flush issue.
   */
  public <O, T> T with(final Class<O> type, final Long id, final Function<O, T> work) {
    return inSession(session -> {
      final var owner = session.find(type, id);
      switch (owner) {
        case Cart cart -> {
          cart.getNotes().size();
          cart.getSteps().size();
          cart.getTags().size();
          cart.getPrims().size();
        }
        case Box box -> box.getNotes().size();
        case Group group -> group.getLines().forEach(row -> row.getSubs().size());
        case Alpha alpha -> alpha.getFriends().forEach(beta -> beta.getFriends().size());
        default -> throw new IllegalArgumentException("no edge owner " + type);
      }
      statistics().clear();
      final var answer = work.apply(owner);
      session.flush();
      return answer;
    });
  }

  /** The work's answer, from a session and transaction of its own. */
  public <T> T inSession(final Function<Session, T> work) {
    return sessions.fromTransaction(work::apply);
  }

  /** The factory's statistics. */
  public Statistics statistics() {
    return sessions.getStatistics();
  }

  @Override
  public void close() {
    sessions.close();
  }
}
