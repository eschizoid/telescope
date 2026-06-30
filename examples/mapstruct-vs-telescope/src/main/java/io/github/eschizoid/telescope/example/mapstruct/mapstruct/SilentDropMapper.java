package io.github.eschizoid.telescope.example.mapstruct.mapstruct;

import io.github.eschizoid.telescope.example.mapstruct.domain.Customer;
import io.github.eschizoid.telescope.example.mapstruct.dto.CustomerContactDto;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

/**
 * Demonstrates MapStruct's default-policy silent drop of an unmapped target — permanently, in CI,
 * so the head-to-head doesn't rely on you triggering it by hand to see it.
 *
 * <p>{@code CustomerContactDto.region} has no source on {@code Customer} at all. Under MapStruct's
 * default {@code unmappedTargetPolicy} ({@code WARN}), this compiles with only a build warning and
 * leaves {@code region} {@code null} at runtime — a quietly wrong object, no error. This is the
 * hazard that bites newly added or drifted target fields; it is distinct from a source rename,
 * which MapStruct catches as a hard compile error (see {@link OrderMapStructMapper}). The companion
 * test asserts the {@code null}.
 */
@Mapper
public interface SilentDropMapper {
  SilentDropMapper INSTANCE = Mappers.getMapper(SilentDropMapper.class);

  @Mapping(source = "email", target = "contactEmail")
  CustomerContactDto toContactDto(Customer customer);
}
