package io.github.eschizoid.telescope.quarkus.tests;

import static io.github.eschizoid.telescope.quarkus.tests.Refusals.refusedWith;
import static org.assertj.core.api.Assertions.fail;

import io.github.eschizoid.telescope.conversion.BridgeProvider;
import io.github.eschizoid.telescope.quarkus.tests.services.Catalog;
import io.github.eschizoid.telescope.quarkus.tests.services.ShopMappers;
import io.quarkus.test.QuarkusUnitTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Two beans of a service a field's rows call fail startup naming both. */
class ServiceBeansAmbiguousRefusedTest {

  @RegisterExtension
  static final QuarkusUnitTest APP = new QuarkusUnitTest()
    .withApplicationRoot(jar ->
      jar
        .addClasses(TwoCatalogs.class)
        .addPackage(ShopMappers.class.getPackage())
        .addAsServiceProvider(BridgeProvider.class.getName(), ShopMappers.PROVIDERS)
    )
    .assertException(
      refusedWith(
        "The bean of the @Bridge field ShopMappers.LINE needs one bean of" +
          " io.github.eschizoid.telescope.quarkus.tests.services.Catalog for compute(Line::getPrice," +
          " service(Catalog::price)), and the application declares 2, retail and wholesale, with none primary." +
          " Mark one primary, or keep one bean of that type."
      )
    );

  @ApplicationScoped
  public static class TwoCatalogs {

    @Produces
    @Singleton
    @Named("retail")
    Catalog retail() {
      return new Catalog(10);
    }

    @Produces
    @Singleton
    @Named("wholesale")
    Catalog wholesale() {
      return new Catalog(5);
    }
  }

  @Test
  @DisplayName("two beans of a service with none primary fail startup")
  void twoServicesFailStartup() {
    fail("the application booted");
  }
}
