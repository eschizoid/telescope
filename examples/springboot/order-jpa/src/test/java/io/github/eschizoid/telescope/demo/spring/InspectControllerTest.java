package io.github.eschizoid.telescope.demo.spring;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.eschizoid.telescope.demo.spring.api.InspectController.InspectError;
import io.github.eschizoid.telescope.demo.spring.api.InspectController.InspectRequest;
import io.github.eschizoid.telescope.demo.spring.api.InspectController.InspectResponse;
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
 * Integration test for the {@code POST /orders/{id}/inspect} endpoint. Exercises {@link
 * io.github.eschizoid.telescope.Telescope#fieldByName(String)} as a chained runtime escape hatch
 * over a dotted path. Pins:
 *
 * <ol>
 *   <li>A two-segment path ({@code customer.email}) reads the nested leaf correctly.
 *   <li>Another two-segment path ({@code shippingAddress.city}) reads through a different branch.
 *   <li>An invalid top-level segment ({@code nonexistent}) yields a {@code 400} with a clear
 *       IllegalArgumentException that names the offending field.
 * </ol>
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class InspectControllerTest {

  @LocalServerPort
  private int port;

  private RestClient client;

  @BeforeEach
  void setUp() {
    this.client = RestClient.create("http://localhost:" + port);
  }

  @Test
  void readsCustomerEmailByRuntimePath() {
    final var id = createOrder();
    final var response = client
      .post()
      .uri("/orders/" + id + "/inspect")
      .contentType(MediaType.APPLICATION_JSON)
      .body(new InspectRequest("customer.email"))
      .retrieve()
      .body(InspectResponse.class);

    assertThat(response).isNotNull();
    assertThat(response.path()).isEqualTo("customer.email");
    // The runtime controller lowercased the email at write time.
    assertThat(response.value()).isEqualTo("alice@example.com");
  }

  @Test
  void readsShippingAddressCityByRuntimePath() {
    final var id = createOrder();
    final var response = client
      .post()
      .uri("/orders/" + id + "/inspect")
      .contentType(MediaType.APPLICATION_JSON)
      .body(new InspectRequest("shippingAddress.city"))
      .retrieve()
      .body(InspectResponse.class);

    assertThat(response).isNotNull();
    assertThat(response.value()).isEqualTo("Brooklyn");
  }

  @Test
  void unknownFieldYields400WithFieldNameInMessage() {
    final var id = createOrder();
    try {
      client
        .post()
        .uri("/orders/" + id + "/inspect")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new InspectRequest("nonexistent"))
        .retrieve()
        .body(InspectResponse.class);
      throw new AssertionError("expected 400");
    } catch (final HttpClientErrorException.BadRequest e) {
      final var error = e.getResponseBodyAs(InspectError.class);
      assertThat(error).isNotNull();
      // Bug-hunt pin: the IAE message *does* include the bad field name ('nonexistent') and the
      // declaring class. It does NOT today include the available alternatives — see slice report.
      assertThat(error.message()).contains("nonexistent");
      assertThat(error.message()).contains("Order");
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
