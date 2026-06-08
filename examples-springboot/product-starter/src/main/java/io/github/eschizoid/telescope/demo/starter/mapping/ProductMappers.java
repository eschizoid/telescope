package io.github.eschizoid.telescope.demo.starter.mapping;

import static io.github.eschizoid.telescope.mapping.WriteHint.WriteStrategy.SETTERS;
import static io.github.eschizoid.telescope.mapping.WriteHint.writeBeans;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.conversion.Mapper;
import io.github.eschizoid.telescope.demo.starter.domain.Product;
import io.github.eschizoid.telescope.demo.starter.partner.ProductDto;
import io.github.eschizoid.telescope.demo.starter.persistence.ProductEntity;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Mapper configuration for the product-starter demo. Two {@code @Bean Mapper<A, B>} definitions —
 * one per target shape — and both are picked up by {@code TelescopeAutoConfiguration} automatically:
 * the starter's {@code TelescopeMapperRegistry} bean asks Spring for every {@code Mapper<?, ?>}
 * bean in the context and indexes them by {@code (sourceClass, targetClass)}.
 *
 * <p>No registration code here. No {@code @TelescopeMapper}-style annotation magic. Just standard
 * Spring {@code @Bean} declarations the same way you'd declare any other bean — and the registry
 * picks them up at boot time.
 *
 * <p>{@code writeBeans(SETTERS)} on both mappers:
 *
 * <ul>
 *   <li>{@link ProductEntity} uses plain Java setters — needed for Hibernate's identity assignment.
 *   <li>{@link ProductDto} uses Lombok-synthesised setters from {@code @Data} — same write
 *       strategy, just emitted by Lombok instead of hand-written.
 * </ul>
 */
@Configuration
public class ProductMappers {

  /** Record ↔ JPA entity. Same-name auto-inference handles every field; only the writer needs hinting. */
  @Bean
  public Mapper<Product, ProductEntity> productEntityMapper() {
    return Telescope.mapper(Product.class, ProductEntity.class, writeBeans(SETTERS));
  }

  /** Record ↔ Lombok DTO. Same-name auto-inference handles every field; writer hint targets the Lombok setters. */
  @Bean
  public Mapper<Product, ProductDto> productDtoMapper() {
    return Telescope.mapper(Product.class, ProductDto.class, writeBeans(SETTERS));
  }
}
