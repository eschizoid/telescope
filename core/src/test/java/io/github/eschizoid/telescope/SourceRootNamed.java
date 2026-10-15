package io.github.eschizoid.telescope;

/**
 * An interface over {@code label} that {@link SourceRootBase} does not implement, but a subclass
 * does.
 */
public interface SourceRootNamed {
  String getLabel();
}
