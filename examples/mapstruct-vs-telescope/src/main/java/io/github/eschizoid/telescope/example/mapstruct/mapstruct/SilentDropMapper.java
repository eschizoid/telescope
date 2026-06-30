package io.github.eschizoid.telescope.example.mapstruct.mapstruct;

import io.github.eschizoid.telescope.example.mapstruct.domain.Customer;
import io.github.eschizoid.telescope.example.mapstruct.dto.CustomerContactDto;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

/**
 * Demonstrates MapStruct's default-policy silent drop — permanently, in CI, so the head-to-head
 * doesn't rely on you applying a rename by hand to see it.
 *
 * <p>{@code CustomerContactDto.region} has no source on {@code Customer}. Under MapStruct's default
 * {@code unmappedTargetPolicy} ({@code WARN}), this compiles with only a build warning and leaves
 * {@code region} {@code null} at runtime. That is exactly what a field rename produces when it
 * strands a target without a source: no compile error, a quietly wrong object. The companion test
 * asserts the {@code null}.
 */
@Mapper
public interface SilentDropMapper {
  SilentDropMapper INSTANCE = Mappers.getMapper(SilentDropMapper.class);

  @Mapping(source = "email", target = "contactEmail")
  CustomerContactDto toContactDto(Customer customer);
}
