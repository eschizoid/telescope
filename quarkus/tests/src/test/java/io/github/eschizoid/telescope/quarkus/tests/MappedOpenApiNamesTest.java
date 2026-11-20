package io.github.eschizoid.telescope.quarkus.tests;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.eschizoid.telescope.quarkus.tests.fixtures.Order;
import io.github.eschizoid.telescope.quarkus.tests.openapi.Consignment;
import io.github.eschizoid.telescope.quarkus.tests.openapi.a.LineDto;
import io.github.eschizoid.telescope.quarkus.tests.openapi.b.MappedResource;
import io.github.eschizoid.telescope.quarkus.tests.openapi.other.ElsewhereResource;
import io.quarkus.test.QuarkusUnitTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * A wire type whose simple name the document already gives another class keeps its own schema: the
 * document's schema of that name stays the other class's, and every reference the mapped endpoint's
 * documentation makes, at the top and nested, resolves to the wire type's components. The endpoints
 * are {@link MappedResource} and {@link ElsewhereResource}.
 */
class MappedOpenApiNamesTest {

  @RegisterExtension
  static final QuarkusUnitTest APP = new QuarkusUnitTest().withApplicationRoot(jar ->
    jar
      .addPackage(Order.class.getPackage())
      .addPackage(Consignment.class.getPackage())
      .addPackage(LineDto.class.getPackage())
      .addPackage(MappedResource.class.getPackage())
      .addPackage(ElsewhereResource.class.getPackage())
  );

  private static JsonPath document() {
    return given().accept(ContentType.JSON).get("/q/openapi").then().statusCode(200).extract().jsonPath();
  }

  private static Map<String, Object> component(final JsonPath document, final String ref) {
    assertThat(ref).startsWith("#/components/schemas/");
    return document.getMap("components.schemas.'" + ref.substring("#/components/schemas/".length()) + "'.properties");
  }

  private static String responseRef(final JsonPath document, final String path) {
    return document.getString("paths.'" + path + "'.get.responses.'200'.content.'application/json'.schema.'$ref'");
  }

  @Test
  @DisplayName("a wire type named like another class's schema is documented under a name of its own")
  void aTopLevelNameClashKeepsBothSchemas() {
    final var document = document();
    assertThat(component(document, responseRef(document, "/mapped/order"))).containsOnlyKeys("number", "customer");
    assertThat(component(document, responseRef(document, "/elsewhere"))).containsOnlyKeys("other", "unrelated");
  }

  @Test
  @DisplayName("a record a wire type holds, named like another class's schema, is referenced under its own name")
  void aNestedNameClashKeepsBothSchemas() {
    final var document = document();
    final var packet = responseRef(document, "/mapped/packet");
    assertThat(component(document, packet)).containsOnlyKeys("id", "line");
    final var line = document.getString(
      "components.schemas.'" + packet.substring("#/components/schemas/".length()) + "'.properties.line.'$ref'"
    );
    assertThat(component(document, line)).containsOnlyKeys("sku");
    assertThat(component(document, responseRef(document, "/mapped/line"))).containsOnlyKeys("qty");
  }

  @Test
  @DisplayName("a wire type equal to the document's schema of its name, but for a record it names, is named apart")
  void aSchemaNamingARenamedOneIsRenamedToo() {
    final var document = document();
    final var parcel = responseRef(document, "/mapped/parcel");
    assertThat(parcel).isNotEqualTo(responseRef(document, "/elsewhere/parcel"));
    final var item = document.getString(
      "components.schemas.'" + parcel.substring("#/components/schemas/".length()) + "'.properties.item.'$ref'"
    );
    assertThat(component(document, item)).containsOnlyKeys("sku");
    final var elsewhere = responseRef(document, "/elsewhere/parcel");
    final var elsewhereItem = document.getString(
      "components.schemas.'" + elsewhere.substring("#/components/schemas/".length()) + "'.properties.item.'$ref'"
    );
    assertThat(component(document, elsewhereItem)).containsOnlyKeys("qty");
  }
}
