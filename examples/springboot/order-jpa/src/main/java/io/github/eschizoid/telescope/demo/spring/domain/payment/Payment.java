package io.github.eschizoid.telescope.demo.spring.domain.payment;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.demo.spring.legacy.PaymentEntity;

/**
 * Record-side sealed payment hierarchy. Three permits: {@link CreditCard}, {@link PayPal}, {@link
 * BankTransfer}. Order carries an instance via {@code Order.payment}.
 *
 * <p>{@code @JsonTypeInfo} + {@code @JsonSubTypes} wire Jackson's polymorphic deserialisation — the
 * API exchanges JSON objects with a {@code "type"} discriminator (e.g. {@code "creditCard"}).
 *
 * <p>{@code @Bridge(PaymentEntity.class)} drives codegen of the sealed-aware {@code PaymentBridge}:
 * the processor walks the permits clause, looks up each subtype's own {@code @Bridge}, and emits a
 * pattern-match switch that dispatches to each per-case bridge. The result is a real {@code
 * Telescope<Payment, PaymentEntity>} that {@code SealedNarrowAfterParadigmHopTest} chains via
 * {@code .then(PaymentBridge.BRIDGE)} to cross from this record-side sealed graph to the bean-side
 * {@code legacy.PaymentEntity} graph.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
  {
    @JsonSubTypes.Type(value = CreditCard.class, name = "creditCard"),
    @JsonSubTypes.Type(value = PayPal.class, name = "payPal"),
    @JsonSubTypes.Type(value = BankTransfer.class, name = "bankTransfer"),
  }
)
@Bridge(PaymentEntity.class)
public sealed interface Payment permits CreditCard, PayPal, BankTransfer {}
