package io.github.eschizoid.telescope.demo.order;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * How the customer pays. A client sends one of the cases with a {@code type} of {@code card} or
 * {@code bank}; the public API answers with the matching case of {@code PaymentDto}, which leaves
 * out what the shop must not echo back.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
  {
    @JsonSubTypes.Type(value = Card.class, name = "card"),
    @JsonSubTypes.Type(value = BankTransfer.class, name = "bank"),
  }
)
public sealed interface Payment permits Card, BankTransfer {}
