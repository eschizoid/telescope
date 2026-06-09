package io.github.eschizoid.telescope.demo.spring.domain.payment;

public record PayPal(String email, String token) implements Payment {}
