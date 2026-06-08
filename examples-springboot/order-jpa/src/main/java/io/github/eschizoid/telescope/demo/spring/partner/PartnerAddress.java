package io.github.eschizoid.telescope.demo.spring.partner;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Partner-side counterpart of {@code domain.Address}. Same field shape; Lombok @Data covers the
 * bean surface, Jackson @JsonProperty annotations keep the snake_case wire format consistent with
 * the rest of the partner DTO graph.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PartnerAddress {

  @JsonProperty("street")
  private String street;

  @JsonProperty("city")
  private String city;

  @JsonProperty("state")
  private String state;

  @JsonProperty("zip")
  private String zip;
}
