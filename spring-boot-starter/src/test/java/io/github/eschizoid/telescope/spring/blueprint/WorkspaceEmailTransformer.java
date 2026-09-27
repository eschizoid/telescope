package io.github.eschizoid.telescope.spring.blueprint;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.spring.TelescopeTransform;
import io.github.eschizoid.telescope.spring.TelescopeTransformation;
import io.github.eschizoid.telescope.spring.Transformation;
import java.util.Locale;

@TelescopeTransform
public interface WorkspaceEmailTransformer extends TelescopeTransformation<Workspace, String> {
  @Override
  default Telescope<Workspace, String> path() {
    return Telescope.of(Workspace.class)
      .field(Workspace::profile)
      .field(Workspace.Profile::contact)
      .field(Workspace.Contact::email);
  }

  @Override
  default Transformation<String> transform() {
    return new Transformation<>(null, value -> value.toLowerCase(Locale.ROOT));
  }
}
