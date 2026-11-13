package io.github.eschizoid.telescope.demo.quarkus;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The order API over HTTP, against the app Quarkus starts for the tests. */
@QuarkusTest
class OrderApiTest {

  @Test
  @DisplayName("an order's public view hides the cost price, the card token and the notes")
  void thePublicViewHidesWhatOnlyTheShopSees() {
    final var body = given().get("/orders/SO-1001").then().statusCode(200).extract().asString();
    assertThat(body).doesNotContain("costPrice", "tok_visa_4242", "blue paper");
    final var json = JsonPath.from(body);
    assertThat(json.getString("customerName")).isEqualTo("Ada Lovelace");
    assertThat(json.getDouble("total")).isEqualTo(45.0);
    assertThat(json.getString("payment.type")).isEqualTo("card");
    assertThat(json.getString("placedOn")).isEqualTo("2026-11-02");
    assertThat(json.getString("shipping")).isNull();
  }

  @Test
  @DisplayName("a shipped order shows its tracking number and the end of the account it was paid from")
  void aShippedOrderShowsItsTrackingNumber() {
    final var json = given().get("/orders/SO-1002").then().statusCode(200).extract().jsonPath();
    assertThat(json.getString("shipping.trackingNumber")).isEqualTo("TRK-1002");
    assertThat(json.getString("payment.accountEnding")).isEqualTo("3000");
  }

  @Test
  @DisplayName("the order list returns one summary per order")
  void theListReturnsSummaries() {
    final var json = given().get("/orders").then().statusCode(200).extract().jsonPath();
    assertThat(json.getString("[0].number")).isEqualTo("SO-1001");
    assertThat(json.getDouble("[0].total")).isEqualTo(45.0);
    assertThat(json.getString("[1].status")).isEqualTo("SHIPPED");
  }

  @Test
  @DisplayName("version 2 renames fields and sends the date and money as text")
  void versionTwoRenamesAndFormats() {
    final var json = given().get("/v2/orders/SO-1001").then().statusCode(200).extract().jsonPath();
    assertThat(json.getString("buyer")).isEqualTo("Ada Lovelace");
    assertThat(json.getString("placedOn")).isEqualTo("02.11.2026");
    assertThat(json.getString("total")).isEqualTo("45.00");
    assertThat(json.getString("lines[0].product")).isEqualTo("MUG-01");
  }

  @Test
  @DisplayName("a version 2 request is mapped to the service's request")
  void aVersionTwoRequestIsMapped() {
    final var json = given()
      .contentType(ContentType.JSON)
      .body(
        Map.of(
          "buyerEmail",
          "grace@example.com",
          "items",
          List.of(Map.of("product", "TEE-02", "qty", 2)),
          "coupon",
          "VIP25",
          "tags",
          List.of("b2b")
        )
      )
      .post("/v2/orders")
      .then()
      .statusCode(201)
      .extract()
      .jsonPath();
    assertThat(json.getString("buyer")).isEqualTo("Grace Hopper");
    assertThat(json.getString("total")).isEqualTo("37.50");
  }

  @Test
  @DisplayName("a PATCH changes only what it sends, and the lines it sends replace the order's")
  void aPatchChangesOnlyWhatItSends() {
    final var location = given()
      .contentType(ContentType.JSON)
      .body(
        Map.of(
          "customerEmail",
          "ada@example.com",
          "lines",
          List.of(Map.of("sku", "MUG-01", "quantity", 1)),
          "giftWrap",
          true,
          "tags",
          List.of("birthday")
        )
      )
      .post("/orders")
      .then()
      .statusCode(201)
      .extract()
      .header("Location");
    final var path = location.substring(location.indexOf("/orders/"));
    given()
      .contentType(ContentType.JSON)
      .body(Map.of("delivery", "EXPRESS", "lines", List.of(Map.of("sku", "BAG-04", "quantity", 2))))
      .patch(path)
      .then()
      .statusCode(200);
    final var json = given().get(path).then().statusCode(200).extract().jsonPath();
    assertThat(json.getString("delivery")).isEqualTo("EXPRESS");
    assertThat(json.getBoolean("giftWrap")).isTrue();
    assertThat(json.getList("tags")).containsExactly("birthday");
    assertThat(json.getList("lines.sku")).containsExactly("BAG-04");
  }

  @Test
  @DisplayName("a shipped order refuses a PATCH with a conflict")
  void aShippedOrderRefusesAPatch() {
    given()
      .contentType(ContentType.JSON)
      .body(Map.of("discountCode", "VIP25"))
      .patch("/orders/SO-1002")
      .then()
      .statusCode(409);
  }

  @Test
  @DisplayName("a partner's untyped payload imports, its SKUs cleaned by the generated transformer bean")
  void aPartnerPayloadImports() {
    final var json = given()
      .contentType(ContentType.JSON)
      .body(
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
      )
      .post("/orders/import")
      .then()
      .statusCode(201)
      .extract()
      .jsonPath();
    assertThat(json.getString("lines[0].sku")).isEqualTo("CAP-03");
    assertThat(json.getInt("lines[0].quantity")).isEqualTo(2);
    assertThat(json.getBoolean("giftWrap")).isTrue();
  }

  @Test
  @DisplayName("a refused import answers 400 with the trace that shows the quantity sent under another key")
  void aRefusedImportCarriesItsTrace() {
    final var payload = Map.of(
      "customerEmail",
      "grace@example.com",
      "lines",
      List.of(Map.of("sku", "CAP-03", "qty", "2"))
    );
    final var json = given()
      .contentType(ContentType.JSON)
      .body(payload)
      .post("/orders/import")
      .then()
      .statusCode(400)
      .extract()
      .jsonPath();
    assertThat(json.getString("detail")).isEqualTo("line CAP-03 has a quantity of 0");
    assertThat(json.getString("trace")).contains("qty=2").contains("quantity=null");
  }

  @Test
  @DisplayName(
    "a partner who sends a number where the feed promises text is refused, naming the field and its position"
  )
  void aNumberWhereTheFeedPromisesTextIsRefused() {
    final var json = given()
      .contentType(ContentType.JSON)
      .body("{\"customerEmail\":\"grace@example.com\",\"lines\":[{\"sku\":\"CAP-03\",\"quantity\":2}]}")
      .post("/orders/import")
      .then()
      .statusCode(400)
      .extract()
      .jsonPath();
    assertThat(json.getString("detail")).isEqualTo("'lines[0].quantity' got 2, not a String, at line 1, column 74");
  }

  @Test
  @DisplayName("a body that is not JSON is refused with a 400")
  void aBodyThatIsNotJsonIsRefused() {
    given().contentType(ContentType.JSON).body("{\"customerEmail\":").post("/orders/import").then().statusCode(400);
  }

  @Test
  @DisplayName("the invoice merges the order, the customer, the configured seller and the totals")
  void theInvoiceMergesFourSources() {
    final var json = given().get("/orders/SO-1001/invoice").then().statusCode(200).extract().jsonPath();
    assertThat(json.getString("billTo")).isEqualTo("Ada Lovelace");
    assertThat(json.getString("sellerName")).isEqualTo("Telescope Goods Ltd");
    assertThat(json.getDouble("discount")).isEqualTo(5.0);
  }

  @Test
  @DisplayName("an order is paid, shipped and then shows its tracking number; the rules refuse the rest with a 409")
  void anOrderGoesThroughItsLifecycle() {
    final var placed = given()
      .contentType(ContentType.JSON)
      .body(Map.of("customerEmail", "ada@example.com", "lines", List.of(Map.of("sku", "CAP-03", "quantity", 1))))
      .post("/orders")
      .then()
      .statusCode(201)
      .extract();
    final var number = placed.jsonPath().getString("number");
    final var path = "/orders/" + number;

    given().post(path + "/ship").then().statusCode(409);
    final var paid = given().post(path + "/pay").then().statusCode(200).extract().jsonPath();
    assertThat(paid.getString("status")).isEqualTo("PAID");
    assertThat(paid.getString("shipping")).as("reserved, not yet shown").isNull();
    given().contentType(ContentType.JSON).body(Map.of("discountCode", "VIP25")).patch(path).then().statusCode(409);

    final var shipped = given().post(path + "/ship").then().statusCode(200).extract().jsonPath();
    assertThat(shipped.getString("shipping.trackingNumber")).isEqualTo("TRK-" + number.substring(3));
    given().post(path + "/cancel").then().statusCode(409);
  }

  @Test
  @DisplayName("a placed order can be cancelled")
  void aPlacedOrderCanBeCancelled() {
    final var number = given()
      .contentType(ContentType.JSON)
      .body(Map.of("customerEmail", "grace@example.com", "lines", List.of(Map.of("sku", "MUG-01", "quantity", 1))))
      .post("/orders")
      .then()
      .statusCode(201)
      .extract()
      .jsonPath()
      .getString("number");
    assertThat(
      given().post("/orders/" + number + "/cancel").then().statusCode(200).extract().jsonPath().getString("status")
    ).isEqualTo("CANCELLED");
  }

  @Test
  @DisplayName("an unknown order is a 404")
  void anUnknownOrderIsNotFound() {
    given().get("/orders/SO-9999").then().statusCode(404);
  }
}
