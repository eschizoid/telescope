package io.github.eschizoid.telescope.demo.spring.bughunt.sealed;

public record PayPal(String email, String token) implements Payment {}
