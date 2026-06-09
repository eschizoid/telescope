package io.github.eschizoid.telescope.demo.spring.bughunt.jpaconverter;

import static io.github.eschizoid.telescope.mapping.WriteHint.WriteStrategy.SETTERS;
import static io.github.eschizoid.telescope.mapping.WriteHint.writeBeans;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.conversion.Mapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires {@code Mapper<NotedOrder, NotedOrderEntity>}. The recursion walks into the {@code address}
 * slot and infers the same-name {@code NotedAddress.city ↔ NotedAddressEmbeddable.city} pair
 * without any explicit row.
 */
@Configuration
public class NotedOrderMappers {

  @Bean
  public Mapper<NotedOrder, NotedOrderEntity> notedOrderMapper() {
    return Telescope.mapper(NotedOrder.class, NotedOrderEntity.class, writeBeans(SETTERS));
  }
}
