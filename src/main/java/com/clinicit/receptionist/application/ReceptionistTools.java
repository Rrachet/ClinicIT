package com.clinicit.receptionist.application;

import com.anthropic.core.JsonValue;
import com.anthropic.models.beta.messages.BetaTool;
import com.clinicit.checkin.api.PublicCheckInResponse;
import com.clinicit.checkin.api.SelfCheckInRequest;
import com.clinicit.checkin.api.SelfCheckInResponse;
import com.clinicit.checkin.application.SelfCheckInService;
import com.clinicit.common.domain.BusinessRuleException;
import com.clinicit.common.domain.InvalidRequestException;
import com.clinicit.common.domain.NotFoundException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The four things the AI receptionist can do. Every call is checked here and by the services
 * behind it; the model only proposes. Results go back to the model as JSON, and the ones the
 * patient needs to act on (their queue page, their booking) also go to the chat page.
 */
@Component
public class ReceptionistTools {

    public static final String LIST_VISIT_TYPES = "list_visit_types";
    public static final String FIND_SLOTS = "find_slots";
    public static final String BOOK_APPOINTMENT = "book_appointment";
    public static final String CHECK_IN_NOW = "check_in_now";

    /** What the chat page shows besides the reply: a queue place or a booking. */
    public record Action(String type, String statusCode, Integer tokenNumber, String doctorName, String room,
                         LocalDateTime startsAt) {
        static Action queued(SelfCheckInResponse r) {
            return new Action("QUEUED", r.statusCode(), r.tokenNumber(), r.doctorName(), r.room(), null);
        }

        static Action booked(ReceptionistBookings.Booked b) {
            return new Action("BOOKED", null, null, b.doctorName(), b.room(), b.start());
        }
    }

    public record Outcome(String content, boolean error, Action action) {}

    private final SelfCheckInService checkIn;
    private final ReceptionistBookings bookings;
    private final ObjectMapper json;

    public ReceptionistTools(SelfCheckInService checkIn, ReceptionistBookings bookings, ObjectMapper json) {
        this.checkIn = checkIn;
        this.bookings = bookings;
        this.json = json;
    }

    public List<BetaTool> definitions() {
        return List.of(
                tool(LIST_VISIT_TYPES,
                        "List what patients can come in for at this clinic (visit types), with whether a doctor "
                                + "for each is seeing patients right now. Call this before offering choices.",
                        Map.of(), List.of()),
                tool(FIND_SLOTS,
                        "Find free appointment times for a visit type on one date (today up to 7 days ahead). "
                                + "Only offer times this returns.",
                        Map.of("visit_type_id", string("Visit type id from list_visit_types"),
                                "date", string("Date as YYYY-MM-DD")),
                        List.of("visit_type_id", "date")),
                tool(BOOK_APPOINTMENT,
                        "Book a free slot returned by find_slots. Only after the patient confirmed the summary "
                                + "(patient name, phone, visit type, doctor, date and time).",
                        Map.of("visit_type_id", string("Visit type id"),
                                "doctor_id", string("Doctor id from find_slots"),
                                "date", string("Date as YYYY-MM-DD"),
                                "time", string("Start time as HH:mm, exactly as find_slots returned it"),
                                "full_name", string("The patient's full name"),
                                "phone", string("The patient's phone number"),
                                "note", string("What the patient said the visit is about, in their words; empty if nothing")),
                        List.of("visit_type_id", "doctor_id", "date", "time", "full_name", "phone", "note")),
                tool(CHECK_IN_NOW,
                        "Check in a patient who is at the clinic now and put them in the queue of a doctor who is "
                                + "seeing patients for that visit type. If they booked earlier today, this checks in "
                                + "that booking. Only after the patient confirmed name, phone and visit type.",
                        Map.of("visit_type_id", string("Visit type id"),
                                "full_name", string("The patient's full name"),
                                "phone", string("The patient's phone number"),
                                "note", string("What the patient said the visit is about, in their words; empty if nothing")),
                        List.of("visit_type_id", "full_name", "phone", "note")));
    }

    public Outcome execute(String checkInCode, UUID clinicId, String name, Map<?, ?> input) {
        try {
            return switch (name) {
                case LIST_VISIT_TYPES -> ok(listVisitTypes(checkInCode), null);
                case FIND_SLOTS -> ok(findSlots(clinicId, input), null);
                case BOOK_APPOINTMENT -> {
                    ReceptionistBookings.Booked booked = bookings.book(clinicId, uuid(input, "visit_type_id"),
                            uuid(input, "doctor_id"), date(input).atTime(time(input)), text(input, "full_name"),
                            text(input, "phone"), optional(input, "note"));
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("booked", true);
                    result.put("date", booked.start().toLocalDate().toString());
                    result.put("time", booked.start().toLocalTime().toString());
                    result.put("doctor_name", booked.doctorName());
                    result.put("room", booked.room());
                    yield ok(result, Action.booked(booked));
                }
                case CHECK_IN_NOW -> {
                    SelfCheckInResponse r = checkIn.checkIn(checkInCode, new SelfCheckInRequest(text(input, "full_name"),
                            text(input, "phone"), uuid(input, "visit_type_id"), optional(input, "note")));
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("checked_in", true);
                    result.put("token", r.tokenNumber());
                    result.put("doctor_name", r.doctorName());
                    result.put("room", r.room());
                    result.put("already_checked_in", r.alreadyCheckedIn());
                    result.put("note", "The patient's live queue page is shown below this chat.");
                    yield ok(result, Action.queued(r));
                }
                default -> error("Unknown tool " + name);
            };
        } catch (BusinessRuleException | InvalidRequestException | NotFoundException e) {
            return error(e.getMessage());
        }
    }

    private List<Map<String, Object>> listVisitTypes(String checkInCode) {
        PublicCheckInResponse page = checkIn.page(checkInCode);
        return page.visitTypes().stream().map(t -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", t.id().toString());
            row.put("name", t.name());
            row.put("description", t.description());
            row.put("doctor_seeing_patients_now", t.available());
            return row;
        }).toList();
    }

    private Map<String, Object> findSlots(UUID clinicId, Map<?, ?> input) {
        LocalDate date = date(input);
        List<Map<String, Object>> slots = bookings.freeSlots(clinicId, uuid(input, "visit_type_id"), date).stream()
                .map(s -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("doctor_id", s.doctorId().toString());
                    row.put("doctor_name", s.doctorName());
                    row.put("specialization", s.specialization());
                    row.put("time", s.start().toLocalTime().toString());
                    return row;
                }).toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("date", date.toString());
        result.put("day_of_week", date.getDayOfWeek().toString());
        result.put("slots", slots);
        if (slots.isEmpty()) {
            result.put("note", "No free times that day. Try another date.");
        }
        return result;
    }

    private Outcome ok(Object result, Action action) {
        try {
            return new Outcome(json.writeValueAsString(result), false, action);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Outcome error(String message) {
        return new Outcome(message == null ? "That did not work." : message, true, null);
    }

    private static String text(Map<?, ?> input, String key) {
        Object value = input.get(key);
        if (!(value instanceof String s) || s.isBlank()) {
            throw new InvalidRequestException(key + ": required");
        }
        if (s.length() > 150) {
            throw new InvalidRequestException(key + ": too long");
        }
        return s.strip();
    }

    private static String optional(Map<?, ?> input, String key) {
        Object value = input.get(key);
        if (!(value instanceof String s) || s.isBlank()) return null;
        return s.length() > 300 ? s.substring(0, 300).strip() : s.strip();
    }

    private static UUID uuid(Map<?, ?> input, String key) {
        try {
            return UUID.fromString(text(input, key));
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException(key + ": not a valid id");
        }
    }

    private static LocalDate date(Map<?, ?> input) {
        try {
            return LocalDate.parse(text(input, "date"));
        } catch (DateTimeParseException e) {
            throw new InvalidRequestException("date: use YYYY-MM-DD");
        }
    }

    private static LocalTime time(Map<?, ?> input) {
        try {
            return LocalTime.parse(text(input, "time"));
        } catch (DateTimeParseException e) {
            throw new InvalidRequestException("time: use HH:mm");
        }
    }

    private static Map<String, Object> string(String description) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "string");
        schema.put("description", description);
        return schema;
    }

    private static BetaTool tool(String name, String description, Map<String, Map<String, Object>> properties,
                                 List<String> required) {
        BetaTool.InputSchema.Properties.Builder props = BetaTool.InputSchema.Properties.builder();
        // Deterministic order keeps the tool list byte-identical between requests (prompt caching).
        properties.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(e -> props.putAdditionalProperty(e.getKey(), JsonValue.from(e.getValue())));
        return BetaTool.builder()
                .name(name)
                .description(description)
                .strict(true)
                .inputSchema(BetaTool.InputSchema.builder()
                        .properties(props.build())
                        .required(required)
                        .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                        .build())
                .build();
    }
}
