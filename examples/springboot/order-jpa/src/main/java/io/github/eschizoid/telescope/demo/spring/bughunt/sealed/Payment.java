package io.github.eschizoid.telescope.demo.spring.bughunt.sealed;

/**
 * Bug-hunt slice: sealed-record domain with three permits. Pairs with the bean-side {@link
 * PaymentEntity} sealed graph through {@link PaymentMappers} so a single chain can navigate record
 * → entity → sealed-bean narrow → bean field.
 */
public sealed interface Payment permits CreditCard, PayPal, BankTransfer {}
