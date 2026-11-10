package io.github.telescopeorganization.telescope.spring.blueprint;

public record Workspace(Profile profile) {
  public record Profile(Contact contact) {}

  public record Contact(String email) {}
}
