package io.github.eschizoid.telescope.jpa;

import jakarta.persistence.Persistence;
import jakarta.persistence.PersistenceUtil;

/**
 * Whether the persistence provider has loaded a container, through {@link PersistenceUtil#isLoaded}
 * from the Jakarta Persistence API, so the answer comes from whichever provider is on the class
 * path. The API is optional at run time: this class touches it only once {@link #AVAILABLE} says it
 * is there, and every other class of the module names nothing of it.
 */
final class LoadStates {

  /** Whether the Jakarta Persistence API is on the class path. */
  static final boolean AVAILABLE = present("jakarta.persistence.Persistence");

  /**
   * The module of the JDK's own containers. A provider's lazy collection is a class of the
   * provider's, so a container whose class is in {@code java.base} holds its elements by
   * construction and is never asked about.
   */
  private static final Module JDK = Object.class.getModule();

  private LoadStates() {}

  /**
   * Whether {@code container} is loaded: true for a JDK container, and otherwise what the
   * persistence provider reports, a container no provider knows counting as loaded.
   *
   * @param container a non-null container
   * @return whether copying {@code container} reads only what is already loaded
   */
  static boolean loaded(final Object container) {
    if (container.getClass().getModule() == JDK) return true;
    return Api.UTIL.isLoaded(container);
  }

  static boolean present(final String name) {
    try {
      Class.forName(name, false, LoadStates.class.getClassLoader());
      return true;
    } catch (final ClassNotFoundException | LinkageError absent) {
      return false;
    }
  }

  /** Loaded only once {@link #AVAILABLE} holds, so its constant links against the API. */
  private static final class Api {

    // The utility answers from the providers the class path registers, and holds no state of its
    // own, so one instance serves every call.
    static final PersistenceUtil UTIL = Persistence.getPersistenceUtil();

    private Api() {}
  }
}
