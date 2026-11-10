package io.github.telescopeorganization.telescope.examples.graphql.model;

import static io.github.telescopeorganization.telescope.Telescope.mapper;
import static io.github.telescopeorganization.telescope.mapping.Mapping.to;

import io.github.telescopeorganization.telescope.annotations.Bridge;
import io.github.telescopeorganization.telescope.conversion.Mapper;

/**
 * A booking a client holds, mapped to {@link TicketView} by the {@code @Bridge} mapper field it
 * declares. The processor compiles the field's rows into {@code TicketToTicketViewBridge}, and
 * {@code Telescope.mapper} returns a mapper over that bridge, found by this record and the pair
 * when the field is initialised. Neither record carries a reflection registration, so inside a
 * native image the mapper converts only through that bridge, and the record is initialised at run
 * time, so the lookup itself runs in the image.
 */
public record Ticket(String code, String seat) {
  @Bridge
  public static final Mapper<Ticket, TicketView> VIEW = mapper(
    Ticket.class,
    TicketView.class,
    to(Ticket::code, TicketView::reference)
  );
}
