package io.github.eschizoid.telescope.demo.quarkus;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every call {@link OrderApiTest} makes, against the native executable. The mapped endpoints write
 * and read types no resource signature names. The shipping label maps through the bridge a
 * {@code @Bridge} field in the warehouse's own package generated, which the image finds at run time
 * only through the service provider the telescope extension registers; without it the label falls
 * back to the same-name mapping and carries no recipient. At startup each mapped body logs whether
 * a provider is registered for its pair.
 */
@QuarkusIntegrationTest
@TestProfile(OrderApiIT.MappedBodyLog.class)
class OrderApiIT extends OrderApiTest {

  /** Logs how each mapped body maps, to the log file the test launcher sets. */
  public static class MappedBodyLog implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("quarkus.log.category.\"io.github.eschizoid.telescope.quarkus.rest\".level", "DEBUG");
    }
  }

  @Test
  @DisplayName("the label's pair, declared by a @Bridge field initialized at run time, has its provider registered")
  void theWarehouseBridgeProviderIsRegistered() throws IOException {
    assertThat(Files.readString(Path.of("build", "quarkus.log"))).contains(
      "OrderResource#label maps Order -> ShippingLabel through Telescope.mapperForward, with a generated bridge's" +
        " provider registered for the pair"
    );
  }
}
