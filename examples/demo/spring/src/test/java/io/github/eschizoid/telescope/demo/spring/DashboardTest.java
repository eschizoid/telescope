package io.github.eschizoid.telescope.demo.spring;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import io.github.eschizoid.telescope.conversion.ContainerCopy;
import io.github.eschizoid.telescope.demo.order.OrderDesk;
import jakarta.persistence.EntityManagerFactory;
import java.util.List;
import java.util.ServiceLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The back office's dashboard over HTTP, with telescope-jpa on the class path as the starter brings
 * it. Hibernate's statistics count the statements each request prepares: a full page of rows maps
 * without loading any order's tags, and asking for the tags loads them for the page in one query. A
 * row carries tags only when the request asks for them. {@code DashboardWithoutTelescopeJpaTest}
 * makes the same request with the module taken off.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = DashboardPage.DATABASE)
class DashboardTest {

  @LocalServerPort
  private int port;

  @Autowired
  private EntityManagerFactory factory;

  @Autowired
  private OrderDesk desk;

  @Autowired
  private TransactionTemplate transaction;

  @BeforeEach
  void seed() {
    DashboardPage.seed(desk, transaction);
  }

  @Test
  @DisplayName("telescope-jpa's container policy is the one registered")
  void theModuleIsOnTheClassPath() {
    assertThat(ServiceLoader.load(ContainerCopy.Policy.class).findFirst()).isPresent();
  }

  @Test
  @DisplayName("a full page of rows costs two statements, the rows with their customers and the total, and no tags")
  void aPageLoadsNoTags() {
    final var served = DashboardPage.get(port, factory, "/orders/dashboard?status=PLACED");
    assertThat(served.statements()).as("the page's rows and its count").isEqualTo(2L);
    final var body = served.body();
    assertThat(JsonPath.<List<String>>read(body, "$.content[*].number")).hasSize(DashboardPage.PLACED);
    assertThat(JsonPath.<String>read(body, "$.content[0].number")).isEqualTo("SO-1022");
    assertThat(JsonPath.<String>read(body, "$.content[0].customerName")).isEqualTo("Grace Hopper");
    assertThat(JsonPath.<String>read(body, "$.content[0].delivery")).isEqualTo("STANDARD");
    assertThat(JsonPath.<Integer>read(body, "$.page.totalElements")).isEqualTo(DashboardPage.PLACED + 1);
    assertThat(body).doesNotContain("tags", "lines", "total\"");
  }

  @Test
  @DisplayName("expanding the tags loads them for the whole page in one more statement")
  void expandingTheTagsCostsOneStatement() {
    final var served = DashboardPage.get(port, factory, "/orders/dashboard?status=PLACED&expand=tags");
    assertThat(served.statements()).as("the page's rows, its count and the page's tags").isEqualTo(3L);
    assertThat(JsonPath.<List<List<String>>>read(served.body(), "$.content[*].tags"))
      .hasSize(DashboardPage.PLACED)
      .allSatisfy(tags -> assertThat(tags).containsExactly("b2b"));
  }

  @Test
  @DisplayName("with the tags asked for, an order that has none gets an empty list; without, the field is absent")
  void anOrderWithNoTagsGetsAnEmptyListOnlyWhenAsked() {
    final var asked = DashboardPage.get(port, factory, "/orders/dashboard?status=SHIPPED&expand=tags").body();
    assertThat(JsonPath.<String>read(asked, "$.content[0].number")).isEqualTo("SO-1002");
    assertThat(JsonPath.<List<String>>read(asked, "$.content[0].tags")).isEmpty();
    final var notAsked = DashboardPage.get(port, factory, "/orders/dashboard?status=SHIPPED").body();
    assertThat(JsonPath.<String>read(notAsked, "$.content[0].number")).isEqualTo("SO-1002");
    assertThat(notAsked).doesNotContain("tags");
  }

  @Test
  @DisplayName("the status is named in any case")
  void theStatusIsNamedInAnyCase() {
    final var served = DashboardPage.get(port, factory, "/orders/dashboard?status=shipped");
    assertThat(served.status()).isEqualTo(200);
    assertThat(JsonPath.<String>read(served.body(), "$.content[0].status")).isEqualTo("SHIPPED");
  }

  @Test
  @DisplayName("a dashboard request without a status is a problem response that lists the statuses")
  void aMissingStatusIsRefused() {
    final var served = DashboardPage.get(port, factory, "/orders/dashboard");
    assertThat(served.status()).isEqualTo(400);
    assertThat(JsonPath.<String>read(served.body(), "$.title")).isEqualTo("Bad Request");
    assertThat(JsonPath.<String>read(served.body(), "$.detail")).isEqualTo(
      "a status is required: one of PLACED, PAID, SHIPPED, CANCELLED"
    );
  }

  @Test
  @DisplayName("an unknown status is a problem response that lists the statuses, with no class name in it")
  void anUnknownStatusIsRefused() {
    final var served = DashboardPage.get(port, factory, "/orders/dashboard?status=sent");
    assertThat(served.status()).isEqualTo(400);
    assertThat(JsonPath.<String>read(served.body(), "$.detail")).isEqualTo(
      "there is no status 'sent': use one of PLACED, PAID, SHIPPED, CANCELLED"
    );
  }

  @Test
  @DisplayName("the second page holds the oldest placed order, newest first")
  void theSecondPageHoldsTheOldestOrder() {
    final var body = DashboardPage.get(port, factory, "/orders/dashboard?status=PLACED&page=1").body();
    assertThat(JsonPath.<List<String>>read(body, "$.content[*].number")).containsExactly("SO-1001");
    assertThat(JsonPath.<String>read(body, "$.content[0].customerName")).isEqualTo("Ada Lovelace");
    assertThat(JsonPath.<Boolean>read(body, "$.content[0].giftWrap")).isTrue();
    assertThat(JsonPath.<String>read(body, "$.content[0].placedOn")).isEqualTo("2026-11-02");
  }
}
