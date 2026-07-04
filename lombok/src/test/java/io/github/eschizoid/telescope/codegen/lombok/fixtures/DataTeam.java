package io.github.eschizoid.telescope.codegen.lombok.fixtures;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Fixture: {@code @Data} POJO with a container component whose element type ({@link DataUser}) is
 * itself {@code @Data}-annotated. Drives the cross-fixture "container step returns sub-navigator"
 * emit path.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DataTeam {

  private String name;
  private List<DataUser> members;
}
