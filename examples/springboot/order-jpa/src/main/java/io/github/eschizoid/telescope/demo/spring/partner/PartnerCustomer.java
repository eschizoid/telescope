package io.github.eschizoid.telescope.demo.spring.partner;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Partner-side counterpart of {@code domain.Customer}. Same shape, Lombok-driven setters + builder.
 * Top-level because the {@code telescope-lombok} processor only emits navigators for top-level
 * classes.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PartnerCustomer {

  @JsonProperty("id")
  private Long id;

  @JsonProperty("name")
  private String name;

  @JsonProperty("email")
  private String email;
}
