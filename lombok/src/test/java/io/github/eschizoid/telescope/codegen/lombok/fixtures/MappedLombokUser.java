package io.github.eschizoid.telescope.codegen.lombok.fixtures;

import static io.github.eschizoid.telescope.mapping.Mapping.to;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.conversion.Mapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Fixture: a {@code @Data} source implementing the {@code MappedLombokUserMappings} interface its
 * own {@code @Bridge} field generates, in the compilation that generates it. Its rows name getters
 * only Lombok writes.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MappedLombokUser implements MappedLombokUserMappings {

  @Bridge
  public static final Mapper<MappedLombokUser, MappedLombokView> VIEW = Telescope.mapper(
    MappedLombokUser.class,
    MappedLombokView.class,
    to(MappedLombokUser::getLogin, MappedLombokView::username)
  );

  private String login;
  private String email;
}
