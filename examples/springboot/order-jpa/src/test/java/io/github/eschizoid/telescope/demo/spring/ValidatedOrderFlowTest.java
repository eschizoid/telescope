package io.github.eschizoid.telescope.demo.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.eschizoid.telescope.demo.spring.api.LineItemValidationError;
import io.github.eschizoid.telescope.demo.spring.api.ValidatedExceptionAdvice;
import io.github.eschizoid.telescope.demo.spring.domain.Address;
import io.github.eschizoid.telescope.demo.spring.domain.LineItem;
import io.github.eschizoid.telescope.demo.spring.domain.Order;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * Verifies that Telescope's {@link io.github.eschizoid.telescope.Telescope#updateValidated}
 * accumulates errors through a nested traversal and that a Spring {@code @RestControllerAdvice} can
 * recover the full error list — not just the first failure.
 *
 * <p>Three pins:
 *
 * <ol>
 *   <li><b>Happy path</b> — valid quantities round-trip to 200 with the unmodified order body.
 *   <li><b>Accumulating failure</b> — TWO negative-quantity line items produce a 400 with BOTH
 *       errors in the payload, not just the first.
 *   <li><b>Mixed validity</b> — one valid + one invalid still surfaces only the invalid one.
 * </ol>
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class ValidatedOrderFlowTest {

  @LocalServerPort
  private int port;

  private RestClient client;

  @BeforeEach
  void setUp() {
    this.client = RestClient.create("http://localhost:" + port);
  }

  @Test
  void allValidQuantitiesReturns200WithOrderBody() {
    final var body = client
      .post()
      .uri("/orders/validated")
      .contentType(MediaType.APPLICATION_JSON)
      .body(OrderFixtures.sampleOrder())
      .retrieve()
      .body(Order.class);

    assertThat(body).isNotNull();
    assertThat(body.orderNumber()).isEqualTo("ORD-2026-0001");
    assertThat(body.lineItems()).hasSize(2);
  }

  @Test
  void twoNegativeQuantitiesAccumulateBothErrors() {
    final var bad = new Order(
      null,
      "ORD-BAD",
      OrderFixtures.sampleOrder().customer(),
      new Address("1 A St", "Brooklyn", "NY", "11201"),
      new Address("1 A St", "Brooklyn", "NY", "11201"),
      List.of(
        new LineItem(null, "SKU-NEG-1", -3, new BigDecimal("10.00")),
        new LineItem(null, "SKU-NEG-2", -7, new BigDecimal("12.00"))
      ),
      Optional.empty()
    );

    final var response = client
      .post()
      .uri("/orders/validated")
      .contentType(MediaType.APPLICATION_JSON)
      .body(bad)
      .retrieve()
      .onStatus(status -> status.value() == 400, (req, res) -> {})
      .toEntity(ValidatedExceptionAdvice.ErrorResponse.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).isNotNull();
    // The point of updateValidated: BOTH errors land in the payload, not just the first one.
    assertThat(response.getBody().errors())
      .hasSize(2)
      .extracting(LineItemValidationError::sku, LineItemValidationError::quantity)
      .containsExactlyInAnyOrder(tuple("SKU-NEG-1", -3), tuple("SKU-NEG-2", -7));
  }

  @Test
  void oneValidOneInvalidReturnsOnlyTheInvalid() {
    final var mixed = new Order(
      null,
      "ORD-MIX",
      OrderFixtures.sampleOrder().customer(),
      new Address("1 A St", "Brooklyn", "NY", "11201"),
      new Address("1 A St", "Brooklyn", "NY", "11201"),
      List.of(
        new LineItem(null, "SKU-GOOD", 5, new BigDecimal("10.00")),
        new LineItem(null, "SKU-NEG", -1, new BigDecimal("12.00"))
      ),
      Optional.empty()
    );

    final var response = client
      .post()
      .uri("/orders/validated")
      .contentType(MediaType.APPLICATION_JSON)
      .body(mixed)
      .retrieve()
      .onStatus(status -> status.value() == 400, (req, res) -> {})
      .toEntity(ValidatedExceptionAdvice.ErrorResponse.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().errors())
      .singleElement()
      .extracting(LineItemValidationError::sku)
      .isEqualTo("SKU-NEG");
  }
}
