package io.github.eschizoid.telescope.demo.api;

/**
 * A partner's order, read from the JSON text it arrives as. The binder {@code @FromJson} generates
 * for {@link ImportedOrder} reads the text straight into the record over jackson-core's streaming
 * parser, with no map in between and no reflection, and holds the document to the keys
 * {@code @FromMap} marks required.
 *
 * <p>Each value is read only from the JSON kind the record declares. The feed promises every value
 * as text, so a partner who sends a quantity as the number {@code 2} is refused with a message that
 * names the field, {@code 'lines[0].quantity' got 2, not a String}, followed by the line and column
 * it is at, rather than having the number guessed into text.
 */
public final class PartnerFeed {

  private PartnerFeed() {}

  /**
   * The order the JSON text holds.
   *
   * @throws IllegalArgumentException for a value of the wrong kind or a missing required key
   */
  public static ImportedOrder read(final String json) {
    return ImportedOrderFromJson.BINDER.read(json);
  }
}
