package io.github.eschizoid.telescope.jpa.fixtures;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.conversion.ForwardMapper;

/** The mappers the tests run: one generated bridge and one runtime mapper, over the same shape. */
public final class AuthorMappers {

  private AuthorMappers() {}

  /** Compiled by {@code telescope-codegen} into {@code AuthorToAuthorViewBridge}. */
  @Bridge
  public static final ForwardMapper<Author, AuthorView> VIEW = Telescope.mapperForward(Author.class, AuthorView.class);

  /** Built at run time from the same rows, with no bridge generated for its pair. */
  public static final ForwardMapper<Author, AuthorRow> ROW = Telescope.mapperForward(Author.class, AuthorRow.class);
}
