package io.github.eschizoid.telescope.quarkus.tests;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.eschizoid.telescope.quarkus.tests.openapi.cycle.a.Firsts;
import io.github.eschizoid.telescope.quarkus.tests.openapi.cycle.x.Knots;
import io.quarkus.test.QuarkusUnitTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * A wire type the document already describes, under a numbered name because another class took its
 * simple name first, and whose record refers back to it, is documented with the document's own
 * components: the mapped and the unmapped endpoint name the same one, and nothing is added again.
 */
class MappedOpenApiCycleTest {

  @RegisterExtension
  static final QuarkusUnitTest APP = new QuarkusUnitTest().withApplicationRoot(jar ->
    jar.addPackage(Knots.class.getPackage()).addPackage(Firsts.class.getPackage())
  );

  @Test
  @DisplayName("a cycle of wire components the document describes under other names reuses those names")
  void aDescribedCycleIsReused() {
    final var document = given().accept(ContentType.JSON).get("/q/openapi").then().statusCode(200).extract().jsonPath();
    final var schema = ".get.responses.'200'.content.'application/json'.schema.'$ref'";
    final var mapped = document.getString("paths.'/knots/mapped'" + schema);
    assertThat(mapped).isEqualTo(document.getString("paths.'/knots/plain'" + schema));
    assertThat(document.getMap("components.schemas")).containsOnlyKeys("Knot", "Loop", "Node", "Node1", "Link");
    assertThat(document.getMap("components.schemas.Node.properties")).containsOnlyKeys("zv");
  }
}
