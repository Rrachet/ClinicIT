package com.clinicit.checkin.application;

import com.clinicit.checkin.api.VisitTypeRequest;
import com.clinicit.checkin.api.VisitTypeResponse;
import com.clinicit.checkin.domain.VisitType;
import com.clinicit.checkin.domain.VisitTypeRepository;
import com.clinicit.common.domain.BusinessRuleException;
import com.clinicit.common.domain.InvalidRequestException;
import com.clinicit.common.domain.NotFoundException;
import com.clinicit.identity.domain.Actor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** The clinic's visit types and which doctors see each (admin-managed). */
@Service
@Transactional
public class VisitTypeService {

    private final VisitTypeRepository visitTypes;
    private final JdbcTemplate jdbc;

    public VisitTypeService(VisitTypeRepository visitTypes, JdbcTemplate jdbc) {
        this.visitTypes = visitTypes;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<VisitTypeResponse> list(Actor actor) {
        List<VisitType> all = visitTypes.findByClinicIdOrderBySortOrderAscNameAsc(actor.clinicId());
        Map<UUID, List<UUID>> doctors = doctorsByVisitType(actor.clinicId());
        return all.stream().map(type -> respond(type, doctors.getOrDefault(type.getId(), List.of()))).toList();
    }

    public VisitTypeResponse create(Actor admin, VisitTypeRequest request) {
        String name = request.name().strip();
        if (visitTypes.existsByClinicIdAndNameIgnoreCase(admin.clinicId(), name)) {
            throw new BusinessRuleException("VISIT_TYPE_EXISTS", "A visit type with this name already exists");
        }
        VisitType type = new VisitType();
        type.setClinicId(admin.clinicId());
        apply(type, request, name);
        VisitType saved = visitTypes.saveAndFlush(type);
        return respond(saved, replaceDoctors(admin.clinicId(), saved, request.doctorIds()));
    }

    public VisitTypeResponse update(Actor admin, UUID id, VisitTypeRequest request) {
        VisitType type = visitTypes.findByIdAndClinicId(id, admin.clinicId())
                .orElseThrow(() -> new NotFoundException("Visit type not found"));
        String name = request.name().strip();
        if (visitTypes.existsByClinicIdAndNameIgnoreCaseAndIdNot(admin.clinicId(), name, id)) {
            throw new BusinessRuleException("VISIT_TYPE_EXISTS", "A visit type with this name already exists");
        }
        apply(type, request, name);
        if (request.active() != null) {
            type.setActive(request.active());
        }
        visitTypes.flush();
        return respond(type, replaceDoctors(admin.clinicId(), type, request.doctorIds()));
    }

    private static void apply(VisitType type, VisitTypeRequest request, String name) {
        type.setName(name);
        type.setDescription(request.description() == null || request.description().isBlank()
                ? null : request.description().strip());
        type.setAnyDoctor(request.anyDoctor());
        if (request.sortOrder() != null) {
            type.setSortOrder(request.sortOrder());
        }
    }

    /** Doctors not in this clinic are refused rather than silently dropped. */
    private List<UUID> replaceDoctors(UUID clinicId, VisitType type, Collection<UUID> requested) {
        List<UUID> ids = List.copyOf(new LinkedHashSet<>(requested));
        if (!type.isAnyDoctor() && ids.isEmpty() && type.isActive()) {
            throw new InvalidRequestException("doctorIds: choose at least one doctor, or allow any doctor");
        }
        if (!ids.isEmpty()) {
            Integer found = jdbc.queryForObject(
                    "select count(*) from doctor_profiles where clinic_id = ? and id = any(?)",
                    Integer.class, clinicId, ids.toArray(UUID[]::new));
            if (found == null || found != ids.size()) {
                throw new NotFoundException("Doctor not found");
            }
        }
        jdbc.update("delete from visit_type_doctors where visit_type_id = ? and clinic_id = ?", type.getId(), clinicId);
        for (UUID doctorId : ids) {
            jdbc.update("insert into visit_type_doctors (clinic_id, visit_type_id, doctor_id) values (?, ?, ?)",
                    clinicId, type.getId(), doctorId);
        }
        return ids;
    }

    private Map<UUID, List<UUID>> doctorsByVisitType(UUID clinicId) {
        return jdbc.query("select visit_type_id, doctor_id from visit_type_doctors where clinic_id = ?",
                        (rs, i) -> Map.entry(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)), clinicId)
                .stream()
                .collect(Collectors.groupingBy(Map.Entry::getKey,
                        Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
    }

    private static VisitTypeResponse respond(VisitType type, List<UUID> doctorIds) {
        return new VisitTypeResponse(type.getId(), type.getName(), type.getDescription(), type.isAnyDoctor(),
                doctorIds, type.getSortOrder(), type.isActive());
    }
}
