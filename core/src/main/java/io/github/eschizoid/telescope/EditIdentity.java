package io.github.eschizoid.telescope;

/** Holder for the {@link Edit#identity()} singleton — identifiable, stateless, shared. */
final class EditIdentity {

  static final Edit<Object> INSTANCE = s -> s;

  private EditIdentity() {}
}
