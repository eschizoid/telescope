package io.github.eschizoid.telescope.spring.blueprint;

public record Workspace(Profile profile) {
  public record Profile(Contact contact) {}

  public record Contact(String email) {}
}
