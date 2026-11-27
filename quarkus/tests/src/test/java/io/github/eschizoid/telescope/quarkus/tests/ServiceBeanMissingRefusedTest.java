package io.github.eschizoid.telescope.quarkus.tests;

import static io.github.eschizoid.telescope.quarkus.tests.Refusals.refusedWith;
import static org.assertj.core.api.Assertions.fail;

import io.github.eschizoid.telescope.conversion.BridgeProvider;
import io.github.eschizoid.telescope.quarkus.tests.services.ShopMappers;
import io.quarkus.test.QuarkusUnitTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * A field whose rows call a service the application declares no bean of fails startup naming the
 * field, the row and the service.
 */
class ServiceBeanMissingRefusedTest {

  @RegisterExtension
  static final QuarkusUnitTest APP = new QuarkusUnitTest()
    .withApplicationRoot(jar ->
      jar
        .addPackage(ShopMappers.class.getPackage())
        .addAsServiceProvider(BridgeProvider.class.getName(), ShopMappers.PROVIDERS)
    )
    .assertException(
      refusedWith(
        "The bean of the @Bridge field ShopMappers.LINE needs a bean of" +
          " io.github.eschizoid.telescope.quarkus.tests.services.Catalog for compute(Line::getPrice," +
          " service(Catalog::price)), and the application declares none. Declare one, or, if only a mapper built" +
          " at run time looks it up, mark it @Unremovable."
      )
    );

  @Test
  @DisplayName("a service with no bean fails startup")
  void missingServiceFailsStartup() {
    fail("the application booted");
  }
}
