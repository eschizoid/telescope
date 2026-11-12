package io.github.eschizoid.telescope.demo.api;

/** A bank transfer, shown by the last four characters of the account. */
public record BankTransferDto(String accountEnding) implements PaymentDto {}
