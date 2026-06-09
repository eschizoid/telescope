package io.github.eschizoid.telescope.demo.starter;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.eschizoid.telescope.conversion.Mapper;
import io.github.eschizoid.telescope.demo.starter.domain.Product;
import io.github.eschizoid.telescope.demo.starter.partner.ProductDto;
import io.github.eschizoid.telescope.demo.starter.partner.ProductManifest;
import io.github.eschizoid.telescope.demo.starter.persistence.ProductEntity;
import io.github.eschizoid.telescope.spring.TelescopeMapperRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * End-to-end test for the product-starter demo. Pins four things:
 *
 * <ol>
 *   <li><b>The starter's autoconfig fired.</b> {@code TelescopeMapperRegistry} is wired into the
 *       context with all three {@code Mapper<Product, ?>} beans indexed — no manual registration
 *       code needed.
 *   <li><b>The registry resolves multiple target shapes from one source class.</b> {@code POST
 *       /products?view=dto} returns snake_case JSON; {@code view=record} returns the canonical
 *       record JSON; {@code view=manifest} returns the constructor-only immutable POJO. Same
 *       controller call, different output shape, dispatched by the registry.
 *   <li><b>Per-target write strategy override.</b> The dedicated {@code GET
 *       /products/{id}/manifest} endpoint proves {@code writeBean(ProductManifest.class,
 *       CONSTRUCTOR)} wins over the global {@code writeBeans(SETTERS)} default — the immutable POJO
 *       can't be built any other way.
 *   <li><b>Lombok + Jackson + Telescope coexist on the same DTO.</b> The {@code ProductDto} bean
 *       has {@code @Data} (Lombok) + {@code @JsonProperty("snake_case")} (Jackson) on every field;
 *       telescope rebuilds via the Lombok-synthesised setters, Jackson serialises via the
 *       annotations. The JSON keys are snake_case and the Lombok DTO round-trips correctly.
 * </ol>
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class ProductFlowTest {

  @LocalServerPort
  private int port;

  @Autowired
  private TelescopeMapperRegistry registry;

  private RestClient client;

  @BeforeEach
  void setUp() {
    this.client = RestClient.create("http://localhost:" + port);
  }

  @Test
  void registryIsAutoConfiguredWithThreeMapperBeans() {
    assertThat(registry).isNotNull();
    assertThat(registry.size()).isEqualTo(3);
    assertThat(registry.contains(Product.class, ProductEntity.class)).isTrue();
    assertThat(registry.contains(Product.class, ProductDto.class)).isTrue();
    assertThat(registry.contains(Product.class, ProductManifest.class)).isTrue();
    final Mapper<Product, ProductDto> dtoMapper = registry.get(Product.class, ProductDto.class);
    assertThat(dtoMapper).isNotNull();
    assertThat(dtoMapper.sourceClass()).isEqualTo(Product.class);
    assertThat(dtoMapper.targetClass()).isEqualTo(ProductDto.class);
  }

  @Test
  void postProductReturnsLombokDtoWithSnakeCaseWireFormatByDefault() {
    final var request = new Product(null, "SKU-001", "Widget", 1999L);
    final var raw = client
      .post()
      .uri("/products")
      .contentType(MediaType.APPLICATION_JSON)
      .body(request)
      .retrieve()
      .body(JsonNode.class);

    assertThat(raw).isNotNull();
    assertThat(raw.has("product_id")).as("snake_case via @JsonProperty").isTrue();
    assertThat(raw.has("stock_keeping_unit")).isTrue();
    assertThat(raw.has("display_name")).isTrue();
    assertThat(raw.has("price_cents")).isTrue();
    assertThat(raw.has("id")).as("camelCase should not leak").isFalse();
    assertThat(raw.has("sku")).isFalse();
    assertThat(raw.get("stock_keeping_unit").asText()).isEqualTo("SKU-001");
    assertThat(raw.get("display_name").asText()).isEqualTo("Widget");
    assertThat(raw.get("price_cents").asLong()).isEqualTo(1999L);
    assertThat(raw.get("product_id").asLong()).isPositive();
  }

  @Test
  void postProductWithViewRecordReturnsCanonicalShape() {
    final var request = new Product(null, "SKU-002", "Gizmo", 4950L);
    final var record = client
      .post()
      .uri("/products?view=record")
      .contentType(MediaType.APPLICATION_JSON)
      .body(request)
      .retrieve()
      .body(Product.class);

    assertThat(record).isNotNull();
    assertThat(record.id()).isNotNull();
    assertThat(record.sku()).isEqualTo("SKU-002");
    assertThat(record.priceCents()).isEqualTo(4950L);
  }

  @Test
  void getByIdRoundTripsThroughTheRegistry() {
    final var created = client
      .post()
      .uri("/products?view=record")
      .contentType(MediaType.APPLICATION_JSON)
      .body(new Product(null, "SKU-003", "Thingamajig", 7799L))
      .retrieve()
      .body(Product.class);
    assertThat(created).isNotNull();

    final var fetched = client.get().uri("/products/" + created.id() + "?view=record").retrieve().body(Product.class);
    assertThat(fetched).isEqualTo(created);
  }

  @Test
  void manifestEndpointReturnsImmutablePojoBuiltViaPerClassConstructorStrategy() {
    // The manifest target POJO has no setters, no no-arg constructor, no builder. The mapper for
    // this target can only succeed if writeBean(ProductManifest.class, CONSTRUCTOR) is honoured
    // per-target — the global default that satisfies ProductEntity and ProductDto cannot apply.
    final var created = client
      .post()
      .uri("/products?view=record")
      .contentType(MediaType.APPLICATION_JSON)
      .body(new Product(null, "SKU-MAN", "Manifested", 1250L))
      .retrieve()
      .body(Product.class);
    assertThat(created).isNotNull();

    final var manifest = client
      .get()
      .uri("/products/" + created.id() + "/manifest")
      .retrieve()
      .body(ProductManifest.class);

    assertThat(manifest).isNotNull();
    assertThat(manifest.getId()).isEqualTo(created.id());
    assertThat(manifest.getSku()).isEqualTo("SKU-MAN");
    assertThat(manifest.getName()).isEqualTo("Manifested");
    assertThat(manifest.getPriceCents()).isEqualTo(1250L);
  }

  @Test
  void postWithViewManifestRoutesThroughThePerClassConstructorMapper() {
    final var manifest = client
      .post()
      .uri("/products?view=manifest")
      .contentType(MediaType.APPLICATION_JSON)
      .body(new Product(null, "SKU-MAN-POST", "PostedManifest", 999L))
      .retrieve()
      .body(ProductManifest.class);

    assertThat(manifest).isNotNull();
    assertThat(manifest.getSku()).isEqualTo("SKU-MAN-POST");
    assertThat(manifest.getPriceCents()).isEqualTo(999L);
  }
}
