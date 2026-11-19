package io.github.eschizoid.telescope.jpa.fixtures.edges;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.conversion.Mapper;

public final class GroupMappers {

  private GroupMappers() {}

  @Bridge
  public static final Mapper<GroupForm, Group> GEN = Telescope.mapper(GroupForm.class, Group.class);

  public static final Mapper<GroupEdit, Group> RT = Telescope.mapper(GroupEdit.class, Group.class);
}
