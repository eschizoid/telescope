package io.github.eschizoid.telescope.demo.spring.domain.payment;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Record-side sealed payment hierarchy. Three permits: {@link CreditCard}, {@link PayPal}, {@link
 * BankTransfer}. Order carries an instance via {@code Order.payment}.
 *
 * <p>{@code @JsonTypeInfo} + {@code @JsonSubTypes} wire Jackson's polymorphic deserialisation — the
 * API exchanges JSON objects with a {@code "type"} discriminator (e.g. {@code "creditCard"}).
 * Telescope's sealed-narrow demo ({@code SealedNarrowAfterParadigmHopTest}) drives a chain that
 * crosses from this record-side sealed graph to the bean-side {@code legacy.PaymentEntity} graph
 * via {@code mapping.PaymentMappers#paymentBridge()}.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
  {
    @JsonSubTypes.Type(value = CreditCard.class, name = "creditCard"),
    @JsonSubTypes.Type(value = PayPal.class, name = "payPal"),
    @JsonSubTypes.Type(value = BankTransfer.class, name = "bankTransfer"),
  }
)
public sealed interface Payment permits CreditCard, PayPal, BankTransfer {}
