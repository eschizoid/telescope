package io.github.eschizoid.telescope.demo.order;

/** A bank transfer. The public API shows only the last four characters of the account. */
public record BankTransfer(String iban) implements Payment {}
