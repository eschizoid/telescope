package io.github.eschizoid.telescope.codegen;

/**
 * Where a carrier {@code @Bridge} leaves a marker for the pair it converts, so a compilation that
 * has the carrier only on its class path can tell that a bridge of the pair's own exists.
 *
 * <p>The runtime finds such a bridge through its {@code META-INF/services} registration, which an
 * annotation processor cannot enumerate: {@code Filer.getResource} returns one resource for one
 * name, and every jar names its service file the same. A marker has a name of its own per pair, so
 * a read of that exact name answers for the pair alone.
 *
 * <p>Two readers constrain the directory. javac reads a jar on the class path only through
 * directories whose every name is an identifier or a keyword, so nothing under {@code META-INF} is
 * found. The module system makes the directory of every file in a named module's jar a package of
 * that module when the directory's name is a legal package name, so two modules each carrying a
 * marker in one such directory would split that package, which the module system refuses. A
 * directory named by a keyword satisfies both: javac walks into {@code default}, and no package can
 * be named with it. Nothing reads the marker at run time.
 */
final class CarrierMarkers {

  /** The directory every marker sits in, relative to a class path root. */
  static final String DIRECTORY = "telescope/carriers/default/";

  private CarrierMarkers() {}

  /**
   * The marker's path relative to a class path root, for the pair of {@code sourceBinaryName} and
   * {@code targetBinaryName}.
   */
  static String relativeName(final String sourceBinaryName, final String targetBinaryName) {
    return DIRECTORY + sourceBinaryName + "__" + targetBinaryName;
  }
}
