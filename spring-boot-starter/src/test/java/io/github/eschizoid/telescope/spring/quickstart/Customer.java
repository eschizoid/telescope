package io.github.eschizoid.telescope.spring.quickstart;

import io.github.eschizoid.telescope.annotations.Bridge;

@Bridge(CustomerDto.class)
public record Customer(String name, Contact contact) {}
