package io.github.eschizoid.telescope.codegen.lombok.fixtures;

import static io.github.eschizoid.telescope.mapping.Mapping.to;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.conversion.Mapper;

/**
 * Fixture: a {@code @Bridge} field over a Lombok-annotated source, with a rename onto a getter
 * Lombok writes and a conversion the processor compiles into a class of its own.
 */
public final class LombokFieldMappers {

  private LombokFieldMappers() {}

  public static String lower(final String s) {
    return s == null ? null : s.toLowerCase();
  }

  public static String upper(final String s) {
    return s == null ? null : s.toUpperCase();
  }

  @Bridge
  public static final Mapper<FieldBridgedLombokUser, FieldBridgedLombokView> USER = Telescope.mapper(
    FieldBridgedLombokUser.class,
    FieldBridgedLombokView.class,
    to(FieldBridgedLombokUser::getLogin, FieldBridgedLombokView::username),
    to(
      FieldBridgedLombokUser::getEmail,
      FieldBridgedLombokView::email,
      LombokFieldMappers::lower,
      LombokFieldMappers::upper
    )
  );
}
