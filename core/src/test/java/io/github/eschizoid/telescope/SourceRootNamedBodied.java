package io.github.eschizoid.telescope;

/** An enum whose constant declares a body and that implements {@link SourceRootNamed}. */
public enum SourceRootNamedBodied implements SourceRootNamed {
  A {
    @Override
    public String toString() {
      return "a";
    }
  };

  @Override
  public String getLabel() {
    return "named";
  }
}
