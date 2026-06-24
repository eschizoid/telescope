package io.github.eschizoid.telescope.codegen.lombok.fixtures;

import io.github.eschizoid.telescope.annotations.FromMap;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Fixture: a Lombok {@code @Data} bean also marked {@code @FromMap}. FromMapProcessor must defer to
 * processingOver() so Lombok's synthesized getters/setters are visible — otherwise beanProperties()
 * sees "no readable properties".
 */
@FromMap
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FromMapDataUser {

  private String name;
  private int age;
}
