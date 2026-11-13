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
 * The dashboard request {@link DashboardTest} makes, run by the {@code testWithoutTelescopeJpa}
 * task alone, with telescope-jpa off the class path. The row mapper then copies each order's lazy
 * tags, and copying a lazy list loads it: one statement per row on top of the page's two. The
 * request did not ask for the tags, so the response leaves them out although they were loaded.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = DashboardPage.DATABASE)
class DashboardWithoutTelescopeJpaTest {

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
  @DisplayName("no container policy is registered")
  void theModuleIsOffTheClassPath() {
    assertThat(ServiceLoader.load(ContainerCopy.Policy.class).findFirst()).isEmpty();
  }

  @Test
  @DisplayName("a full page of rows costs one more statement per row, to load tags the response then leaves out")
  void aPageLoadsEveryOrdersTags() {
    final var served = DashboardPage.get(port, factory, "/orders/dashboard?status=PLACED");
    assertThat(served.statements())
      .as("the page's rows, its count and each row's tags")
      .isEqualTo(2L + DashboardPage.PLACED);
    assertThat(JsonPath.<List<String>>read(served.body(), "$.content[*].number")).hasSize(DashboardPage.PLACED);
    assertThat(served.body()).as("the tags were not asked for").doesNotContain("tags");
  }
}
