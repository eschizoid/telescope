package io.github.eschizoid.telescope.demo.spring.legacy;

/** Bean-side sealed counterpart of {@code Payment}. Mirrors the three permits one-for-one. */
public sealed interface PaymentEntity permits CreditCardEntity, PayPalEntity, BankTransferEntity {}
