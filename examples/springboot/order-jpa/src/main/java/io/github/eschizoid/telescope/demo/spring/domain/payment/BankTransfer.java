package io.github.eschizoid.telescope.demo.spring.domain.payment;

public record BankTransfer(String iban, String bic) implements Payment {}
