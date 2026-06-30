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
 * the MapStruct way: a string-keyed {@code @Mapping}. The {@code Customer} sub-method is required
 * to carry the rename {@code @Mapping}; the {@code LineItem} one is written out to make the
 * collection recursion explicit (MapStruct would otherwise synthesize element mapping for the
 * same-named record).
 *
 * <p>The load-bearing detail is the {@code "email"} string. Rename {@code Customer.email()} via an
 * IDE refactor and the string is <em>not</em> touched — it's opaque text, not a reference — so it
 * now names a property that no longer exists. MapStruct catches that loudly: a hard compile error
 * ({@code No property named "email" exists in source parameter(s)}), independent of policy. Good —
 * but the fix is manual, every stale {@code @Mapping} hand-edited; telescope's {@code
 * Customer::email} method reference is refactored automatically and checked by the compiler.
 *
 * <p>(The silent-{@code null} hazard is a <em>separate</em> footgun — an unmapped target with no
 * source, not a renamed source — demonstrated by {@link SilentDropMapper}.)
 */
@Mapper
public interface OrderMapStructMapper {
  OrderMapStructMapper INSTANCE = Mappers.getMapper(OrderMapStructMapper.class);

  OrderDto toDto(Order order);

  @Mapping(source = "email", target = "contactEmail")
  CustomerDto toDto(Customer customer);

  LineItemDto toDto(LineItem line);
}
