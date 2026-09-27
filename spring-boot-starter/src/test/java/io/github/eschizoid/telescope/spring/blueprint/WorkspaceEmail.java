package io.github.eschizoid.telescope.spring.blueprint;

import io.github.eschizoid.telescope.spring.TelescopePath;
import io.github.eschizoid.telescope.spring.TelescopeTransform;

@TelescopeTransform(from = Workspace.class, to = String.class, path = "profile.contact.email")
public interface WorkspaceEmail extends TelescopePath<Workspace, String> {}
