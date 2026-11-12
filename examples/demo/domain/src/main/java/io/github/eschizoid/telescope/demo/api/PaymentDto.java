package io.github.eschizoid.telescope.demo.api;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/** How an order is paid, as the public API shows it. Each case of {@code Payment} has one here. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
  {
    @JsonSubTypes.Type(value = CardDto.class, name = "card"),
    @JsonSubTypes.Type(value = BankTransferDto.class, name = "bank"),
  }
)
public sealed interface PaymentDto permits CardDto, BankTransferDto {}
