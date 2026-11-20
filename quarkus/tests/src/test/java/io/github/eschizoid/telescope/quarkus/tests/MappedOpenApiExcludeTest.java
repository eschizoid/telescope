package io.github.eschizoid.telescope.quarkus.tests;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.eschizoid.telescope.quarkus.rest.TelescopeResponse;
import io.github.eschizoid.telescope.quarkus.tests.fixtures.Order;
import io.github.eschizoid.telescope.quarkus.tests.fixtures.OrderDto;
import io.quarkus.test.QuarkusUnitTest;
import io.restassured.http.ContentType;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * A class the application keeps out of its document is not scanned again, and its mapped methods
 * get no wire operation: with operation ids taken from method names and a duplicate refused, an
 * excluded class whose mapped method shares a kept one's name does not fail the build.
 */
class MappedOpenApiExcludeTest {

  @RegisterExtension
  static final QuarkusUnitTest APP = new QuarkusUnitTest()
    .withApplicationRoot(jar ->
      jar.addClasses(Kept.class, Hidden.class, HiddenPlain.class).addPackage(Order.class.getPackage())
    )
    .overrideConfigKey("mp.openapi.scan.exclude.classes", Hidden.class.getName() + "," + HiddenPlain.class.getName())
    .overrideConfigKey("mp.openapi.extensions.smallrye.operationIdStrategy", "METHOD")
    .overrideConfigKey("mp.openapi.extensions.smallrye.duplicateOperationIdBehavior", "FAIL")
    .overrideConfigKey("quarkus.log.category.\"io.github.eschizoid.telescope.quarkus.deployment\".level", "DEBUG")
    .setLogRecordPredicate(record ->
      record.getLoggerName().startsWith("io.github.eschizoid.telescope.quarkus.deployment")
    )
    .assertLogRecords(records ->
      assertThat(records)
        .extracting(LogRecord::getMessage)
        .contains("Hidden#get is in a class the OpenAPI scan leaves out, so no wire type is generated for it")
        .noneMatch(message -> message.startsWith("Kept#get"))
    );

  @Path("/kept")
  @Produces(MediaType.APPLICATION_JSON)
  public static class Kept {

    @GET
    @TelescopeResponse(to = OrderDto.class)
    public Order get() {
      return new Order("SO-1", "Ada", 1200);
    }
  }

  @Path("/hidden")
  @Produces(MediaType.APPLICATION_JSON)
  public static class Hidden {

    @GET
    @TelescopeResponse(to = OrderDto.class)
    public Order get() {
      return new Order("SO-2", "Ada", 1200);
    }
  }

  @Path("/hidden-plain")
  @Produces(MediaType.APPLICATION_JSON)
  public static class HiddenPlain {

    @GET
    public Order get() {
      return new Order("SO-3", "Ada", 1200);
    }
  }

  @Test
  @DisplayName("an excluded class's mapped method is neither scanned again nor documented")
  void anExcludedMappedClassIsLeftOut() {
    final var document = given().accept(ContentType.JSON).get("/q/openapi").then().statusCode(200).extract().jsonPath();
    assertThat(
      document.getString("paths.'/kept'.get.responses.'200'.content.'application/json'.schema.'$ref'")
    ).isEqualTo("#/components/schemas/OrderDto");
    assertThat(document.getMap("paths")).containsOnlyKeys("/kept");
  }
}
