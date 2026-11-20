package io.github.eschizoid.telescope.quarkus.tests.parity;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.conversion.BridgeFn;
import io.github.eschizoid.telescope.conversion.BridgeProvider;

/** A provider registering a second bridge for the same pair, as one in another module would. */
public final class ElsewhereBridgeProvider implements BridgeProvider {

  @Override
  public Class<?> sourceType() {
    return Ticket.class;
  }

  @Override
  public Class<?> targetType() {
    return TicketDto.class;
  }

  @Override
  public Object bridge() {
    return Telescope.bridge(
      new BridgeFn<Ticket, TicketDto>() {
        @Override
        public TicketDto forward(final Ticket source) {
          return new TicketDto(source.id(), "provider");
        }

        @Override
        public Ticket backward(final TicketDto target) {
          return new Ticket(target.id());
        }
      }
    );
  }
}
