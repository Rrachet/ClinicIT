package com.clinicit.queue.api;

import java.util.UUID;

/** @param doctorId required for front desk; a doctor may omit it to call their own next patient */
public record CallNextRequest(UUID doctorId) {}
