package io.github.eschizoid.telescope.example.mapstruct.mapstruct;

import io.github.eschizoid.telescope.example.mapstruct.domain.Customer;
import io.github.eschizoid.telescope.example.mapstruct.domain.LineItem;
import io.github.eschizoid.telescope.example.mapstruct.domain.Order;
import io.github.eschizoid.telescope.example.mapstruct.dto.CustomerDto;
import io.github.eschizoid.telescope.example.mapstruct.dto.LineItemDto;
import io.github.eschizoid.telescope.example.mapstruct.dto.OrderDto;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

/**
 * The MapStruct side of the head-to-head. Forward Order to OrderDto, with the one rename spelled
 * the MapStruct way: a string-keyed {@code @Mapping}. The two sub-methods ({@code Customer}, {@code
 * LineItem}) are required so MapStruct generates the nested + collection recursion.
 *
 * <p>The load-bearing detail is the {@code "email"} string. Rename {@code Customer.email()} via an
 * IDE refactor and this string is <em>not</em> touched — it's opaque text, not a reference. What
 * happens next depends on MapStruct's policy (see the slice README):
 *
 * <ul>
 *   <li>Default {@code unmappedTargetPolicy = WARN}: a now-unmapped target compiles and goes
 *       silently {@code null} at runtime.
 *   <li>Strictest {@code ERROR}: the stale string fails compilation, and you hand-fix every
 *       {@code @Mapping} across the codebase by hand.
 * </ul>
 *
 * Either way the string is the liability. telescope's {@code Customer::email} method reference is
 * refactored automatically and checked by the compiler.
 */
@Mapper
public interface OrderMapStructMapper {
  OrderMapStructMapper INSTANCE = Mappers.getMapper(OrderMapStructMapper.class);

  OrderDto toDto(Order order);

  @Mapping(source = "email", target = "contactEmail")
  CustomerDto toDto(Customer customer);

  LineItemDto toDto(LineItem line);
}
