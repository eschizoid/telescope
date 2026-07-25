package io.github.eschizoid.telescope.example.mapstruct.mapstruct;

import io.github.eschizoid.telescope.example.mapstruct.domain.Customer;
import io.github.eschizoid.telescope.example.mapstruct.dto.CustomerContactDto;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;

/**
 * The fair fight: MapStruct hardened with its own one-line opt-in, {@code unmappedTargetPolicy =
 * ReportingPolicy.ERROR} — the configuration serious MapStruct setups run.
 *
 * <p>Under {@code ERROR}, the {@link SilentDropMapper} shape does not compile: the unmapped {@code
 * region} target is a build failure, not a warning. The remedy is an explicit decision in source —
 * here {@code @Mapping(target = "region", ignore = true)} — so the drop is visible and reviewed
 * rather than silent. That is the honest comparison: telescope's strict {@code mapper(...)} refuses
 * unmapped fields at construction <em>by default</em> (an explicit {@code drop(...)} row is the
 * remedy); MapStruct reaches the same safety with this one line of configuration. The difference is
 * the default, not the ceiling.
 */
@Mapper(unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface StrictPolicyMapper {
  StrictPolicyMapper INSTANCE = Mappers.getMapper(StrictPolicyMapper.class);

  @Mapping(source = "email", target = "contactEmail")
  @Mapping(target = "region", ignore = true) // ERROR policy forces this drop to be explicit
  CustomerContactDto toContactDto(Customer customer);
}
