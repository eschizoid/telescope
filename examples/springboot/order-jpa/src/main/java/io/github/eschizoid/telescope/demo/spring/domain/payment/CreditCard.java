package io.github.eschizoid.telescope.demo.spring.domain.payment;

public record CreditCard(String cardNumber, String holder, int expiryYear) implements Payment {}
