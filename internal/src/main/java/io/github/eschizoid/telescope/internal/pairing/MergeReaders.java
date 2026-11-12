package io.github.eschizoid.telescope.internal.pairing;

/**
 * Which readers a {@code Telescope.merge} {@code from(...)} row takes, decided once for the runtime
 * merge, the bridge processor and the compile-time verifier.
 *
 * <p>A merge finds the source a {@code from} row reads by the class its reader names, so the reader
 * is an accessor of that class: an instance method called on the source it is handed, taking no
 * argument. A static method taking the source, or a method of a receiver the reference holds, names
 * a class that is not a source, and forward could never find it in the bag, so the row is refused
 * when the mapper is built.
 */
public final class MergeReaders {

  private MergeReaders() {}

  /**
   * The refusal of the merge row at {@code index}, whose reader is the method {@code method} of
   * {@code declaring}, or null when the reader {@code readsTheSource}: it is an instance method
   * called on the value it is handed.
   */
  public static String refusal(
    final int index,
    final String declaring,
    final String method,
    final boolean readsTheSource
  ) {
    return readsTheSource ? null : PairingMessages.mergeReaderNotAnAccessor(index, declaring + "::" + method);
  }
}
