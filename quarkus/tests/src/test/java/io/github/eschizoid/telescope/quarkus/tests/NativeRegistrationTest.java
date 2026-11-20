package io.github.eschizoid.telescope.quarkus.tests;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.eschizoid.telescope.conversion.BridgeProvider;
import io.github.eschizoid.telescope.quarkus.rest.TelescopeRequestBody;
import io.github.eschizoid.telescope.quarkus.rest.TelescopeResponse;
import io.github.eschizoid.telescope.quarkus.tests.fixtures.Order;
import io.github.eschizoid.telescope.quarkus.tests.fixtures.OrderDto;
import io.github.eschizoid.telescope.quarkus.tests.fixtures.PlaceOrder;
import io.github.eschizoid.telescope.quarkus.tests.fixtures.PlaceOrderRequest;
import io.github.eschizoid.telescope.quarkus.tests.fixtures.RequestMappers;
import io.github.eschizoid.telescope.quarkus.tests.fixtures.ShoutCustomer;
import io.quarkus.builder.BuildContext;
import io.quarkus.builder.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.nativeimage.LambdaCapturingTypeBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveHierarchyBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ServiceProviderBuildItem;
import io.quarkus.test.QuarkusUnitTest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * What the build registers for a native image: each type a body is read or written as, which no
 * resource signature names; the generated bridge holder of each pair, which the forward mapping
 * finds by name; every telescope service provider, which ServiceLoader finds only when registered;
 * and the classes whose method references the providers' rows and the generated beans decode. The
 * registrations are read off the build, so the test runs on the JVM.
 */
class NativeRegistrationTest {

  // The build runs in a class loader of its own, so what it registers reaches the test through
  // system properties, which both loaders share. The names stay outside the telescope prefix, which
  // the extension's configuration mapping validates.
  private static final String HIERARCHIES = "registration-capture.hierarchies";
  private static final String CLASSES = "registration-capture.classes";
  private static final String PROVIDERS = "registration-capture.providers";
  private static final String LAMBDAS = "registration-capture.lambdas";

  @RegisterExtension
  static final QuarkusUnitTest APP = new QuarkusUnitTest()
    .withApplicationRoot(jar ->
      jar
        .addClasses(Orders.class)
        .addPackage(Order.class.getPackage())
        .addAsResource("META-INF/services/" + BridgeProvider.class.getName())
    )
    .addBuildChainCustomizer(chain ->
      chain
        .addBuildStep(
          new BuildStep() {
            @Override
            public void execute(final BuildContext context) {
              final var hierarchies = new ArrayList<String>();
              for (final var item : context.consumeMulti(ReflectiveHierarchyBuildItem.class))
                hierarchies.add(item.getType().name().toString());
              final var classes = new ArrayList<String>();
              for (final var item : context.consumeMulti(ReflectiveClassBuildItem.class))
                classes.addAll(item.getClassNames());
              final var providers = new ArrayList<String>();
              for (final var item : context.consumeMulti(ServiceProviderBuildItem.class))
                providers.addAll(item.providers());
              final var lambdas = new ArrayList<String>();
              for (final var item : context.consumeMulti(LambdaCapturingTypeBuildItem.class))
                lambdas.add(item.getClassName());
              System.setProperty(HIERARCHIES, String.join(",", hierarchies));
              System.setProperty(CLASSES, String.join(",", classes));
              System.setProperty(PROVIDERS, String.join(",", providers));
              System.setProperty(LAMBDAS, String.join(",", lambdas));
              context.produce(new FeatureBuildItem("registration-capture"));
            }
          }
        )
        .consumes(ReflectiveHierarchyBuildItem.class)
        .consumes(ReflectiveClassBuildItem.class)
        .consumes(ServiceProviderBuildItem.class)
        .consumes(LambdaCapturingTypeBuildItem.class)
        .produces(FeatureBuildItem.class)
        .build()
    );

  @Path("/orders")
  @Produces(MediaType.APPLICATION_JSON)
  public static class Orders {

    @GET
    @TelescopeResponse(to = OrderDto.class)
    public List<Order> list() {
      return List.of();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public PlaceOrder place(@TelescopeRequestBody(from = PlaceOrderRequest.class) final PlaceOrder command) {
      return command;
    }
  }

  private static List<String> registered(final String property) {
    return List.of(System.getProperty(property, "").split(","));
  }

  @Test
  @DisplayName("each type a body is read or written as is registered for reflection")
  void wireTypesAreRegistered() {
    assertThat(registered(HIERARCHIES)).contains(OrderDto.class.getName(), PlaceOrderRequest.class.getName());
  }

  @Test
  @DisplayName("the generated bridge holder of a mapped pair is registered, so the forward mapping finds it")
  void bridgeHoldersAreRegistered() {
    assertThat(registered(CLASSES)).contains(PlaceOrderRequest.class.getName() + "ToPlaceOrderBridge");
  }

  @Test
  @DisplayName(
    "each generated bridge provider is registered, so a @Bridge field finds its bridge through ServiceLoader"
  )
  void bridgeProvidersAreRegistered() {
    assertThat(registered(PROVIDERS)).contains(PlaceOrderRequest.class.getName() + "ToPlaceOrderBridgeProvider");
  }

  @Test
  @DisplayName(
    "the lambdas a @Bridge field's rows and a generated transformer's path hold are registered for serialization"
  )
  void methodReferencesAreRegistered() {
    assertThat(registered(LAMBDAS)).contains(RequestMappers.class.getName(), ShoutCustomer.class.getName());
  }
}
