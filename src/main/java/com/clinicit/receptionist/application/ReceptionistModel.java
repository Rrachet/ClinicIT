package com.clinicit.receptionist.application;

import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.MessageCreateParams;

/** One call to Claude. An interface so the conversation logic does not depend on HTTP details. */
public interface ReceptionistModel {
    BetaMessage create(MessageCreateParams params);
}
