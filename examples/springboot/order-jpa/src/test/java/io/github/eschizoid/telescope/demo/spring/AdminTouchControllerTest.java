package io.github.eschizoid.telescope.demo.spring;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.eschizoid.telescope.demo.spring.api.AdminTouchController.AdminTouchError;
import io.github.eschizoid.telescope.demo.spring.api.AdminTouchController.AdminTouchRequest;
import io.github.eschizoid.telescope.demo.spring.domain.Order;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Integration test for the {@code POST /orders/{id}/admin-touch} endpoint. Exercises {@link
 * io.github.eschizoid.telescope.Telescope#fieldByName(String)} as a chained runtime <b>write</b>
 * escape hatch — companion to {@code InspectControllerTest}, which pins the read side.
 *
 * <p>Pins three things the runtime-checked surface owes its caller:
 *
 * <ol>
 *   <li>A two-segment write path ({@code shippingAddress.city}) sets the nested leaf and persists
 *       it — a follow-up GET reads the new value back. {@code shippingAddress} is {@code @Embedded}
 *       on {@code OrderEntity}, so the update lands in the same row as the parent and the cascade
 *       config is irrelevant.
 *   <li>A wrong leaf segment surfaces a {@code 400} carrying the offending field name, the
 *       declaring class (the nested record, not the top-level one), AND the list of known
 *       components on that class.
 *   <li>A wrong top-level segment surfaces the same shape against {@link Order}'s components.
 * </ol>
 *
 * <p>Together with the read companion, these pin the "config-driven path with a useful error on a
 * typo" story end-to-end — the load-bearing reason for {@code fieldByName} to exist alongside the
 * compile-checked {@code field(Accessor)} default.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class AdminTouchControllerTest {

  @LocalServerPort
  private int port;

  private RestClient client;

  @BeforeEach
  void setUp() {
    this.client = RestClient.create("http://localhost:" + port);
  }

  @Test
  void touchesShippingAddressCityViaRuntimePathAndPersists() {
    final var id = createOrder();

    final var touched = client
      .post()
      .uri("/orders/" + id + "/admin-touch")
      .contentType(MediaType.APPLICATION_JSON)
      .body(new AdminTouchRequest("shippingAddress.city", "Manhattan"))
      .retrieve()
      .body(Order.class);

    assertThat(touched).isNotNull();
    assertThat(touched.shippingAddress().city()).isEqualTo("Manhattan");

    // Re-read via GET to prove the change persisted, not just that the response round-tripped.
    // shippingAddress is @Embedded on OrderEntity, so the column lives in the orders row and the
    // update is a plain column write — no cascade subtleties to manage.
    final var fetched = client.get().uri("/orders/" + id).retrieve().body(Order.class);
    assertThat(fetched).isNotNull();
    assertThat(fetched.shippingAddress().city()).isEqualTo("Manhattan");
  }

  @Test
  void unknownLeafSegmentYields400WithDeclaringClassAndKnownFieldsListed() {
    final var id = createOrder();
    try {
      client
        .post()
        .uri("/orders/" + id + "/admin-touch")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new AdminTouchRequest("customer.bogus", "x"))
        .retrieve()
        .body(Order.class);
      throw new AssertionError("expected 400");
    } catch (final HttpClientErrorException.BadRequest e) {
      final var error = e.getResponseBodyAs(AdminTouchError.class);
      assertThat(error).isNotNull();
      // The IAE carries: the bad field name ('bogus'), the declaring class (Customer — the nested
      // record, not the top-level Order), and the known-fields list on that class.
      assertThat(error.message()).contains("bogus");
      assertThat(error.message()).contains("Customer");
      assertThat(error.message()).contains("known fields:");
      assertThat(error.message()).contains("email");
    }
  }

  @Test
  void unknownTopLevelSegmentYields400WithOrderComponentsListed() {
    final var id = createOrder();
    try {
      client
        .post()
        .uri("/orders/" + id + "/admin-touch")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new AdminTouchRequest("nonexistent", "x"))
        .retrieve()
        .body(Order.class);
      throw new AssertionError("expected 400");
    } catch (final HttpClientErrorException.BadRequest e) {
      final var error = e.getResponseBodyAs(AdminTouchError.class);
      assertThat(error).isNotNull();
      assertThat(error.message()).contains("nonexistent");
      assertThat(error.message()).contains("Order");
      assertThat(error.message()).contains("known fields:");
      // A handful of real Order components to prove the actual list is rendered, not just the
      // literal "known fields:" string.
      assertThat(error.message()).contains("customer");
      assertThat(error.message()).contains("shippingAddress");
    }
  }

  private Long createOrder() {
    final var created = client
      .post()
      .uri("/orders")
      .contentType(MediaType.APPLICATION_JSON)
      .body(OrderFixtures.sampleOrder())
      .retrieve()
      .body(Order.class);
    assertThat(created).isNotNull();
    return created.id();
  }
}
