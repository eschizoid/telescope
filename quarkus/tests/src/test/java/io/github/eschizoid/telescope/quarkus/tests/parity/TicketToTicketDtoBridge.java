package io.github.eschizoid.telescope.quarkus.tests.parity;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.conversion.BridgeFn;

/** The bridge holder beside the source, under the name the forward mapping looks for. */
public final class TicketToTicketDtoBridge {

  /** Writes {@code via = "holder"}. */
  public static final Telescope<Ticket, TicketDto> BRIDGE = Telescope.bridge(
    new BridgeFn<Ticket, TicketDto>() {
      @Override
      public TicketDto forward(final Ticket source) {
        return new TicketDto(source.id(), "holder");
      }

      @Override
      public Ticket backward(final TicketDto target) {
        return new Ticket(target.id());
      }
    }
  );

  private TicketToTicketDtoBridge() {}
}
