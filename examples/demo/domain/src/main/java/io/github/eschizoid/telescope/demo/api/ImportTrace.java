package io.github.eschizoid.telescope.demo.api;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.conversion.ForwardMapper;
import java.util.Map;

/**
 * Replays a partner's payload through the import and says, field by field, which key was read and
 * what came of it. The import's 400 response carries it, so a partner whose order is refused sees
 * why: a key spelled differently from the record's field shows up as a field left at its default.
 *
 * <p>The import binds through the binder {@code @FromMap} generates. This is the same binding built
 * at run time, which gives the same record for the same map and can also narrate what it did.
 */
public final class ImportTrace {

  private static final ForwardMapper<Map<String, Object>, ImportedOrder> BINDER = Telescope.fromMap(
    ImportedOrder.class
  );

  private ImportTrace() {}

  /**
   * Each field of the import: the key it read, the value found and the value it took. A payload the
   * map binding cannot replay, such as a number where the record holds text, is answered with why,
   * so the response that carries the trace is still the partner's 400.
   */
  public static String of(final Map<String, Object> payload) {
    try {
      return BINDER.trace(payload).toString();
    } catch (final RuntimeException unreplayable) {
      return "the payload could not be replayed: " + unreplayable.getMessage();
    }
  }
}
