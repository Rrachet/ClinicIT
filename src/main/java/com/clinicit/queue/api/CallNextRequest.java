package com.clinicit.queue.api;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CallNextRequest(@NotNull UUID doctorId) {}
