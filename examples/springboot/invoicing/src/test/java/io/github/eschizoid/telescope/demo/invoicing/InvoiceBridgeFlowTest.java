package io.github.eschizoid.telescope.demo.invoicing;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.eschizoid.telescope.demo.invoicing.domain.InvoiceHeader;
import io.github.eschizoid.telescope.demo.invoicing.domain.InvoiceLine;
import io.github.eschizoid.telescope.demo.invoicing.persistence.InvoiceHeaderEntity;
import io.github.eschizoid.telescope.demo.invoicing.persistence.InvoiceLineEntity;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * End-to-end test of the bridge endpoints. Drives the generated {@code InvoiceLineBridge} and
 * {@code InvoiceHeaderBridge} through the controller surface — confirms Jackson can serialise both
 * record and bean sides of the bridge and the deep-recursion list-lift works under HTTP.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class InvoiceBridgeFlowTest {

  @LocalServerPort
  private int port;

  private RestClient client;

  @BeforeEach
  void setUp() {
    this.client = RestClient.create("http://localhost:" + port);
  }

  @Test
  void postLineForwardReturnsEntityWithSameValues() {
    final var line = new InvoiceLine("SKU-A", 3, new BigDecimal("19.99"));

    final var entity = client
      .post()
      .uri("/invoices/lines/forward")
      .contentType(MediaType.APPLICATION_JSON)
      .body(line)
      .retrieve()
      .body(InvoiceLineEntity.class);

    assertThat(entity).isNotNull();
    assertThat(entity.getSku()).isEqualTo("SKU-A");
    assertThat(entity.getQty()).isEqualTo(3);
    assertThat(entity.getUnitPrice()).isEqualByComparingTo("19.99");
  }

  @Test
  void postHeaderForwardRecursesIntoLineBridgeForEveryListElement() {
    final var header = new InvoiceHeader(
      "INV-200",
      List.of(new InvoiceLine("SKU-X", 2, new BigDecimal("11.50")), new InvoiceLine("SKU-Y", 7, new BigDecimal("0.99")))
    );

    final var entity = client
      .post()
      .uri("/invoices/headers/forward")
      .contentType(MediaType.APPLICATION_JSON)
      .body(header)
      .retrieve()
      .body(InvoiceHeaderEntity.class);

    assertThat(entity).isNotNull();
    assertThat(entity.getNumber()).isEqualTo("INV-200");
    assertThat(entity.getLines()).hasSize(2);
    assertThat(entity.getLines().getFirst().getSku()).isEqualTo("SKU-X");
    assertThat(entity.getLines().get(0).getQty()).isEqualTo(2);
    assertThat(entity.getLines().get(0).getUnitPrice()).isEqualByComparingTo("11.50");
    assertThat(entity.getLines().get(1).getSku()).isEqualTo("SKU-Y");
    assertThat(entity.getLines().get(1).getQty()).isEqualTo(7);
    assertThat(entity.getLines().get(1).getUnitPrice()).isEqualByComparingTo("0.99");
  }
}
