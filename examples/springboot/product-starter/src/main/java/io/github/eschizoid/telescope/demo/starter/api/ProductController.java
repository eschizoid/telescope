package io.github.eschizoid.telescope.demo.starter.api;

import io.github.eschizoid.telescope.demo.starter.domain.Product;
import io.github.eschizoid.telescope.demo.starter.partner.ProductDto;
import io.github.eschizoid.telescope.demo.starter.persistence.ProductEntity;
import io.github.eschizoid.telescope.demo.starter.persistence.ProductRepository;
import io.github.eschizoid.telescope.spring.TelescopeMapperRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Product CRUD demonstrating the {@link TelescopeMapperRegistry} value-add. The controller doesn't
 * inject specific {@code Mapper<A, B>} beans by name — it asks the registry for the right one at
 * request time. That's not strictly necessary for this two-mapper demo, but it's the pattern that
 * scales when an application has dozens of mappers and the dispatch is dynamic.
 *
 * <p>Endpoints:
 *
 * <ul>
 *   <li>{@code POST /products?view=dto|record} — accepts a {@link Product} JSON body, saves it via
 *       JPA, then returns either a snake_case {@link ProductDto} (default) or the canonical {@link
 *       Product} based on the {@code view} query parameter. The registry resolves both target
 *       shapes from one source class.
 *   <li>{@code GET /products/{id}?view=dto|record} — load by id and return in the requested
 *       view-shape via the registry.
 * </ul>
 */
@RestController
@RequestMapping("/products")
public class ProductController {

  private final TelescopeMapperRegistry registry;
  private final ProductRepository productRepository;

  public ProductController(final TelescopeMapperRegistry registry, final ProductRepository productRepository) {
    this.registry = registry;
    this.productRepository = productRepository;
  }

  @PostMapping
  @Transactional
  public ResponseEntity<?> create(
    @RequestBody final Product request,
    @RequestParam(defaultValue = "dto") final String view
  ) {
    final var entity = registry.get(Product.class, ProductEntity.class).forward(request);
    final var saved = productRepository.save(entity);
    final var roundTripped = registry.get(Product.class, ProductEntity.class).backward(saved);
    return ResponseEntity.ok(renderView(roundTripped, view));
  }

  @GetMapping("/{id}")
  @Transactional(readOnly = true)
  public ResponseEntity<?> get(@PathVariable final Long id, @RequestParam(defaultValue = "dto") final String view) {
    return productRepository
      .findById(id)
      .map(entity -> registry.get(Product.class, ProductEntity.class).backward(entity))
      .map(record -> renderView(record, view))
      .<ResponseEntity<?>>map(ResponseEntity::ok)
      .orElseGet(() -> ResponseEntity.notFound().build());
  }

  /**
   * Dispatch the requested wire-format. Demonstrates the registry's polymorphic lookup — one source
   * class, multiple target shapes, picked by a runtime parameter.
   */
  private Object renderView(final Product record, final String view) {
    return switch (view) {
      case "record" -> record;
      case "dto" -> registry.get(Product.class, ProductDto.class).forward(record);
      default -> throw new IllegalArgumentException("Unknown view: " + view + " (expected one of: dto, record)");
    };
  }
}
