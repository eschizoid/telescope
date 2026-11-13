package io.github.eschizoid.telescope.demo.spring;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** The order API over HTTP, against a running server and its H2 database. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class OrderApiTest {

  @LocalServerPort
  private int port;

  private RestClient client;

  @BeforeEach
  void connect() {
    client = RestClient.builder()
      .requestFactory(new JdkClientHttpRequestFactory())
      .baseUrl("http://localhost:" + port)
      .build();
  }

  @Test
  @DisplayName("an order's public view hides the cost price, the card token and the notes")
  void thePublicViewHidesWhatOnlyTheShopSees() {
    final var response = send(HttpMethod.GET, "/orders/SO-1001", null);
    assertThat(response.getStatusCode().value()).isEqualTo(200);
    final var body = response.getBody();
    assertThat(body).doesNotContain("costPrice", "tok_visa_4242", "blue paper");
    assertThat(JsonPath.<String>read(body, "$.customerName")).isEqualTo("Ada Lovelace");
    assertThat(JsonPath.<Double>read(body, "$.total")).isEqualTo(45.0);
    assertThat(JsonPath.<String>read(body, "$.payment.type")).isEqualTo("card");
    assertThat(JsonPath.<String>read(body, "$.payment.last4")).isEqualTo("4242");
    assertThat(JsonPath.<String>read(body, "$.placedOn")).isEqualTo("2026-11-02");
    assertThat(JsonPath.<Object>read(body, "$.shipping")).isNull();
  }

  @Test
  @DisplayName("a shipped order shows its tracking number and the end of the account it was paid from")
  void aShippedOrderShowsItsTrackingNumber() {
    final var body = send(HttpMethod.GET, "/orders/SO-1002", null).getBody();
    assertThat(JsonPath.<String>read(body, "$.shipping.trackingNumber")).isEqualTo("TRK-1002");
    assertThat(JsonPath.<String>read(body, "$.payment.type")).isEqualTo("bank");
    assertThat(JsonPath.<String>read(body, "$.payment.accountEnding")).isEqualTo("3000");
    assertThat(body).doesNotContain("DE89370400440532013000");
  }

  @Test
  @DisplayName("the order list returns one summary per order, mapped where Spring MVC writes the body")
  void theListReturnsSummaries() {
    final var body = send(HttpMethod.GET, "/orders", null).getBody();
    assertThat(JsonPath.<String>read(body, "$[0].number")).isEqualTo("SO-1001");
    assertThat(JsonPath.<String>read(body, "$[0].customerName")).isEqualTo("Ada Lovelace");
    assertThat(JsonPath.<Double>read(body, "$[0].total")).isEqualTo(45.0);
    assertThat(JsonPath.<String>read(body, "$[1].status")).isEqualTo("SHIPPED");
    assertThat(body).doesNotContain("lines");
  }

  @Test
  @DisplayName("version 2 renames fields and sends the date and money as text")
  void versionTwoRenamesAndFormats() {
    final var body = send(HttpMethod.GET, "/v2/orders/SO-1001", null).getBody();
    assertThat(JsonPath.<String>read(body, "$.buyer")).isEqualTo("Ada Lovelace");
    assertThat(JsonPath.<String>read(body, "$.placedOn")).isEqualTo("02.11.2026");
    assertThat(JsonPath.<String>read(body, "$.total")).isEqualTo("45.00");
    assertThat(JsonPath.<String>read(body, "$.coupon")).isEqualTo("WELCOME10");
    assertThat(JsonPath.<String>read(body, "$.lines[0].product")).isEqualTo("MUG-01");
    assertThat(JsonPath.<String>read(body, "$.lines[0].price")).isEqualTo("12.50");
    assertThat(body).doesNotContain("customerName", "discountCode");
  }

  @Test
  @DisplayName("a version 2 request body is read in its own shape and mapped before the handler runs")
  void aVersionTwoRequestIsMapped() {
    final var response = send(
      HttpMethod.POST,
      "/v2/orders",
      Map.of(
        "buyerEmail",
        "grace@example.com",
        "items",
        List.of(Map.of("product", "TEE-02", "qty", 2)),
        "coupon",
        "VIP25",
        "tags",
        List.of("b2b"),
        "payment",
        Map.of("type", "card", "brand", "MC", "last4", "5454", "token", "tok_mc")
      )
    );
    assertThat(response.getStatusCode().value()).isEqualTo(201);
    assertThat(response.getHeaders().getLocation().getPath()).startsWith("/v2/orders/SO-");
    final var body = response.getBody();
    assertThat(JsonPath.<String>read(body, "$.buyer")).isEqualTo("Grace Hopper");
    assertThat(JsonPath.<String>read(body, "$.total")).isEqualTo("37.50");
    assertThat(JsonPath.<String>read(body, "$.coupon")).isEqualTo("VIP25");
  }

  @Test
  @DisplayName("a PATCH changes only what it sends, and the lines it sends replace the order's in the database")
  void aPatchChangesOnlyWhatItSends() {
    final var placed = send(
      HttpMethod.POST,
      "/orders",
      Map.of(
        "customerEmail",
        "ada@example.com",
        "lines",
        List.of(Map.of("sku", "MUG-01", "quantity", 1), Map.of("sku", "CAP-03", "quantity", 1)),
        "giftWrap",
        true,
        "tags",
        List.of("birthday")
      )
    );
    assertThat(placed.getStatusCode().value()).isEqualTo(201);
    final var path = placed.getHeaders().getLocation().getPath();

    final var patched = send(
      HttpMethod.PATCH,
      path,
      Map.of("delivery", "EXPRESS", "lines", List.of(Map.of("sku", "BAG-04", "quantity", 2)))
    );
    assertThat(patched.getStatusCode().value()).isEqualTo(200);

    final var body = send(HttpMethod.GET, path, null).getBody();
    assertThat(JsonPath.<String>read(body, "$.delivery")).isEqualTo("EXPRESS");
    assertThat(JsonPath.<Boolean>read(body, "$.giftWrap")).isTrue();
    assertThat(JsonPath.<List<String>>read(body, "$.tags")).containsExactly("birthday");
    assertThat(JsonPath.<List<String>>read(body, "$.lines[*].sku")).containsExactly("BAG-04");
    assertThat(JsonPath.<Double>read(body, "$.total")).isEqualTo(30.0);
  }

  @Test
  @DisplayName("a shipped order refuses a PATCH with a conflict")
  void aShippedOrderRefusesAPatch() {
    final var response = send(HttpMethod.PATCH, "/orders/SO-1002", Map.of("discountCode", "VIP25"));
    assertThat(response.getStatusCode().value()).isEqualTo(409);
  }

  @Test
  @DisplayName("a partner's untyped payload imports, its SKUs cleaned by the generated transformer bean")
  void aPartnerPayloadImports() {
    final var response = send(
      HttpMethod.POST,
      "/orders/import",
      Map.of(
        "customerEmail",
        "grace@example.com",
        "delivery",
        "EXPRESS",
        "giftWrap",
        "true",
        "lines",
        List.of(Map.of("sku", " cap-03 ", "quantity", "2"))
      )
    );
    assertThat(response.getStatusCode().value()).isEqualTo(201);
    final var body = response.getBody();
    assertThat(JsonPath.<String>read(body, "$.lines[0].sku")).isEqualTo("CAP-03");
    assertThat(JsonPath.<Integer>read(body, "$.lines[0].quantity")).isEqualTo(2);
    assertThat(JsonPath.<String>read(body, "$.delivery")).isEqualTo("EXPRESS");
    assertThat(JsonPath.<Boolean>read(body, "$.giftWrap")).isTrue();
  }

  @Test
  @DisplayName("a refused import answers 400 with the trace that shows the quantity sent under another key")
  void theImportTraceShowsAMisspelledKey() {
    final var payload = Map.of(
      "customerEmail",
      "grace@example.com",
      "lines",
      List.of(Map.of("sku", "CAP-03", "qty", "2"))
    );
    final var refused = send(HttpMethod.POST, "/orders/import", payload);
    assertThat(refused.getStatusCode().value()).isEqualTo(400);
    assertThat(JsonPath.<String>read(refused.getBody(), "$.detail")).isEqualTo("line CAP-03 has a quantity of 0");
    assertThat(JsonPath.<String>read(refused.getBody(), "$.trace")).contains("qty=2").contains("quantity=null");
  }

  @Test
  @DisplayName(
    "a partner who sends a number where the feed promises text is refused, naming the field and its position"
  )
  void aNumberWhereTheFeedPromisesTextIsRefused() {
    final var refused = send(
      HttpMethod.POST,
      "/orders/import",
      "{\"customerEmail\":\"grace@example.com\",\"lines\":[{\"sku\":\"CAP-03\",\"quantity\":2}]}"
    );
    assertThat(refused.getStatusCode().value()).isEqualTo(400);
    assertThat(JsonPath.<String>read(refused.getBody(), "$.detail")).isEqualTo(
      "'lines[0].quantity' got 2, not a String, at line 1, column 74"
    );
  }

  @Test
  @DisplayName("a body that is not JSON is refused with a 400")
  void aBodyThatIsNotJsonIsRefused() {
    assertThat(send(HttpMethod.POST, "/orders/import", "{\"customerEmail\":").getStatusCode().value()).isEqualTo(400);
  }

  @Test
  @DisplayName("the invoice merges the order, the customer, the configured seller and the totals")
  void theInvoiceMergesFourSources() {
    final var body = send(HttpMethod.GET, "/orders/SO-1001/invoice", null).getBody();
    assertThat(JsonPath.<String>read(body, "$.billTo")).isEqualTo("Ada Lovelace");
    assertThat(JsonPath.<String>read(body, "$.sellerName")).isEqualTo("Telescope Goods Ltd");
    assertThat(JsonPath.<Double>read(body, "$.discount")).isEqualTo(5.0);
    assertThat(JsonPath.<String>read(body, "$.issuedOn")).isEqualTo("2026-11-02");
  }

  @Test
  @DisplayName("an order is paid, shipped and then shows its tracking number; the rules refuse the rest with a 409")
  void anOrderGoesThroughItsLifecycle() {
    final var placed = send(
      HttpMethod.POST,
      "/orders",
      Map.of("customerEmail", "ada@example.com", "lines", List.of(Map.of("sku", "CAP-03", "quantity", 1)))
    );
    final var path = placed.getHeaders().getLocation().getPath();
    final var number = JsonPath.<String>read(placed.getBody(), "$.number");

    final var unpaidShip = send(HttpMethod.POST, path + "/ship", null);
    assertThat(unpaidShip.getStatusCode().value()).isEqualTo(409);
    assertThat(JsonPath.<String>read(unpaidShip.getBody(), "$.detail")).isEqualTo(
      "order " + number + " is PLACED, and only a PAID order can ship"
    );

    final var paid = send(HttpMethod.POST, path + "/pay", null);
    assertThat(JsonPath.<String>read(paid.getBody(), "$.status")).isEqualTo("PAID");
    assertThat(JsonPath.<Object>read(paid.getBody(), "$.shipping")).as("reserved, not yet shown").isNull();
    assertThat(send(HttpMethod.PATCH, path, Map.of("discountCode", "VIP25")).getStatusCode().value()).isEqualTo(409);

    final var shipped = send(HttpMethod.POST, path + "/ship", null);
    assertThat(JsonPath.<String>read(shipped.getBody(), "$.status")).isEqualTo("SHIPPED");
    assertThat(JsonPath.<String>read(shipped.getBody(), "$.shipping.trackingNumber")).isEqualTo(
      "TRK-" + number.substring(3)
    );
    assertThat(send(HttpMethod.POST, path + "/cancel", null).getStatusCode().value()).isEqualTo(409);
  }

  @Test
  @DisplayName("a placed order can be cancelled")
  void aPlacedOrderCanBeCancelled() {
    final var placed = send(
      HttpMethod.POST,
      "/orders",
      Map.of("customerEmail", "grace@example.com", "lines", List.of(Map.of("sku", "MUG-01", "quantity", 1)))
    );
    final var cancelled = send(HttpMethod.POST, placed.getHeaders().getLocation().getPath() + "/cancel", null);
    assertThat(JsonPath.<String>read(cancelled.getBody(), "$.status")).isEqualTo("CANCELLED");
  }

  @Test
  @DisplayName("Spring's JSON mapper reads a sign-up as a customer, and writes an order's lazy customer as a card")
  void customersAreReadAndWrittenAsCards() {
    final var created = send(
      HttpMethod.POST,
      "/customers",
      Map.of("name", "Edsger Dijkstra", "email", "edsger@example.com")
    );
    assertThat(created.getStatusCode().value()).isEqualTo(201);
    assertThat(JsonPath.<String>read(created.getBody(), "$.email")).isEqualTo("edsger@example.com");
    assertThat(JsonPath.<Integer>read(created.getBody(), "$.id")).isNotNull();
    assertThat(
      send(HttpMethod.POST, "/customers", Map.of("name", "E", "email", "edsger@example.com")).getStatusCode().value()
    ).isEqualTo(409);
    final var card = send(HttpMethod.GET, "/orders/SO-1002/customer", null).getBody();
    assertThat(JsonPath.<String>read(card, "$.name")).isEqualTo("Grace Hopper");
    assertThat(JsonPath.<String>read(card, "$.email")).isEqualTo("grace@example.com");
    assertThat(JsonPath.<Integer>read(card, "$.id")).isNotNull();
    assertThat(card).as("the proxy is written as its card").doesNotContain("hibernateLazyInitializer");
  }

  @Test
  @DisplayName("a sign-up that sends an id creates a new customer and leaves the one with that id as it was")
  void aSignUpDoesNotChooseItsId() {
    final var created = send(
      HttpMethod.POST,
      "/customers",
      Map.of("id", 1, "name", "Barbara Liskov", "email", "barbara@example.com")
    );
    assertThat(created.getStatusCode().value()).isEqualTo(201);
    assertThat(JsonPath.<Integer>read(created.getBody(), "$.id")).isNotEqualTo(1);
    final var ada = send(HttpMethod.GET, "/orders/SO-1001/customer", null).getBody();
    assertThat(JsonPath.<Integer>read(ada, "$.id")).isEqualTo(1);
    assertThat(JsonPath.<String>read(ada, "$.name")).isEqualTo("Ada Lovelace");
  }

  @Test
  @DisplayName("an unknown order is a 404")
  void anUnknownOrderIsNotFound() {
    assertThat(send(HttpMethod.GET, "/orders/SO-9999", null).getStatusCode().value()).isEqualTo(404);
  }

  private ResponseEntity<String> send(final HttpMethod method, final String path, final Object body) {
    final var request = client.method(method).uri(path);
    if (body != null) request.contentType(MediaType.APPLICATION_JSON).body(body);
    return request
      .retrieve()
      .onStatus(HttpStatusCode::isError, (req, res) -> {})
      .toEntity(String.class);
  }
}
