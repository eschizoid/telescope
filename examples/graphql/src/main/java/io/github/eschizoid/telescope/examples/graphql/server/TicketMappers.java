package io.github.eschizoid.telescope.examples.graphql.server;

import static io.github.eschizoid.telescope.mapping.Mapping.to;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.conversion.Mapper;
import io.github.eschizoid.telescope.examples.graphql.model.Ticket;
import io.github.eschizoid.telescope.examples.graphql.model.TicketView;

/**
 * A {@code @Bridge} mapper field. The processor compiles its rows into {@code
 * TicketMappersTicketBridge}, and {@code Telescope.mapper} returns a mapper over that bridge, found
 * by this class and the pair when the field is initialised. The class is initialised at run time,
 * so inside a native image the lookup itself runs in the image.
 */
public final class TicketMappers {

  private TicketMappers() {}

  @Bridge
  public static final Mapper<Ticket, TicketView> TICKET = Telescope.mapper(
    Ticket.class,
    TicketView.class,
    to(Ticket::code, TicketView::reference)
  );
}
