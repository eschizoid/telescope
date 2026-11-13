package io.github.eschizoid.telescope.demo.spring;

import io.github.eschizoid.telescope.demo.api.NewLine;
import io.github.eschizoid.telescope.demo.api.PlaceOrder;
import io.github.eschizoid.telescope.demo.order.Delivery;
import io.github.eschizoid.telescope.demo.order.OrderDesk;
import io.github.eschizoid.telescope.demo.order.SampleData;
import jakarta.persistence.EntityManagerFactory;
import java.util.List;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

/**
 * A full dashboard page and the statements it costs. The dashboard tests run against a database of
 * their own, which holds the sample orders and {@link #PLACED} more placed orders, each with a tag,
 * so the first page of placed orders is full.
 */
final class DashboardPage {

  /** The placed orders seeded beside the sample order {@code SO-1001}: one full page. */
  static final int PLACED = 20;

  /** The properties that give a dashboard test its own database. */
  static final String DATABASE = "spring.datasource.url=jdbc:h2:mem:dashboard;DB_CLOSE_DELAY=-1";

  private DashboardPage() {}

  /** Places {@link #PLACED} orders, once per database. */
  static void seed(final OrderDesk desk, final TransactionTemplate transaction) {
    transaction.executeWithoutResult(status -> {
      if (desk.list().size() > 2) return;
      for (var i = 0; i < PLACED; i++) {
        desk.place(
          new PlaceOrder(
            i % 2 == 0 ? SampleData.ADA : SampleData.GRACE,
            List.of(new NewLine("MUG-01", 1)),
            Delivery.STANDARD,
            false,
            null,
            null,
            List.of("b2b")
          )
        );
      }
    });
  }

  /** The body of {@code path}, and the statements Hibernate prepared while serving it. */
  static Served get(final int port, final EntityManagerFactory factory, final String path) {
    final var statistics = statistics(factory);
    final var client = RestClient.builder()
      .requestFactory(new JdkClientHttpRequestFactory())
      .baseUrl("http://localhost:" + port)
      .build();
    statistics.clear();
    final var body = client.get().uri(path).retrieve().body(String.class);
    return new Served(body, statistics.getPrepareStatementCount());
  }

  private static Statistics statistics(final EntityManagerFactory factory) {
    final var statistics = factory.unwrap(SessionFactory.class).getStatistics();
    statistics.setStatisticsEnabled(true);
    return statistics;
  }

  record Served(String body, long statements) {}
}
