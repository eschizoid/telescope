package io.github.eschizoid.telescope.demo.spring.bughunt.sealed;

/** Bean-side sealed counterpart of {@link Payment}. Mirrors the three permits one-for-one. */
public sealed interface PaymentEntity permits CreditCardEntity, PayPalEntity, BankTransferEntity {}
