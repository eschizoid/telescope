package io.github.eschizoid.telescope.demo.starter.mapping;

import static io.github.eschizoid.telescope.WriteHint.WriteStrategy.CONSTRUCTOR;
import static io.github.eschizoid.telescope.WriteHint.WriteStrategy.SETTERS;
import static io.github.eschizoid.telescope.WriteHint.writeBean;
import static io.github.eschizoid.telescope.WriteHint.writeBeans;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.Mapper;
import io.github.eschizoid.telescope.demo.starter.domain.Product;
import io.github.eschizoid.telescope.demo.starter.partner.ProductDto;
import io.github.eschizoid.telescope.demo.starter.partner.ProductManifest;
import io.github.eschizoid.telescope.demo.starter.persistence.ProductEntity;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Mapper configuration for the product-starter demo. Two {@code @Bean Mapper<A, B>} definitions —
 * one per target shape — and both are picked up by {@code TelescopeAutoConfiguration}
 * automatically: the starter's {@code TelescopeMapperRegistry} bean asks Spring for every {@code
 * Mapper<?, ?>} bean in the context and indexes them by {@code (sourceClass, targetClass)}.
 *
 * <p>No registration code here. No {@code @TelescopeMapper}-style annotation magic. Just standard
 * Spring {@code @Bean} declarations the same way you'd declare any other bean — and the registry
 * picks them up at boot time.
 *
 * <p>Three different target write strategies — picked per-target:
 *
 * <ul>
 *   <li>{@link ProductEntity} via {@code writeBeans(SETTERS)} — Hibernate-managed bean with no-arg
 *       ctor + setters.
 *   <li>{@link ProductDto} via {@code writeBeans(SETTERS)} — Lombok-synthesised setters from
 *       {@code @Data}; same strategy, just emitted by Lombok instead of hand-written.
 *   <li>{@link ProductManifest} via {@code writeBean(ProductManifest.class, CONSTRUCTOR)} —
 *       immutable POJO with all-args constructor only. The per-class hint wins over any global
 *       {@code writeBeans(...)} default, so one mapper can mix strategies when a single target
 *       hierarchy has both mutable and immutable shapes. Here it's the only writer the mapper even
 *       needs, since {@code ProductManifest} is the only bean target.
 * </ul>
 */
@Configuration
public class ProductMappers {

  /**
   * Record ↔ JPA entity. Same-name auto-inference handles every field; only the writer needs
   * hinting.
   */
  @Bean
  public Mapper<Product, ProductEntity> productEntityMapper() {
    return Telescope.mapper(Product.class, ProductEntity.class, writeBeans(SETTERS));
  }

  /**
   * Record ↔ Lombok DTO. Same-name auto-inference handles every field; writer hint targets the
   * Lombok setters.
   */
  @Bean
  public Mapper<Product, ProductDto> productDtoMapper() {
    return Telescope.mapper(Product.class, ProductDto.class, writeBeans(SETTERS));
  }

  /**
   * Record ↔ immutable manifest POJO. {@link ProductManifest} has no setters / no builder / no
   * no-arg constructor, so {@code SETTERS} would fail eagerly at mapper-construction time. The
   * per-class {@code writeBean(...)} hint pins the {@code CONSTRUCTOR} strategy for this target,
   * proving that one mapper can opt out of the global default for a specific bean shape.
   *
   * <p>Discovered by the starter's {@code TelescopeMapperRegistry} the same way every other {@code
   * Mapper<?, ?>} bean is — no extra registration ceremony.
   */
  @Bean
  public Mapper<Product, ProductManifest> productManifestMapper() {
    return Telescope.mapper(Product.class, ProductManifest.class, writeBean(ProductManifest.class, CONSTRUCTOR));
  }
}
