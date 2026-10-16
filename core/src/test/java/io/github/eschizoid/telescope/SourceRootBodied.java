package io.github.eschizoid.telescope;

/**
 * An enum whose constant declares a body, so the enum is not marked final, that does not implement
 * {@link SourceRootNamed}.
 */
public enum SourceRootBodied {
  A {
    @Override
    public String toString() {
      return "a";
    }
  };

  public String getLabel() {
    return "bodied";
  }
}
