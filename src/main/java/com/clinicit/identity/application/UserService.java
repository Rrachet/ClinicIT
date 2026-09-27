package com.clinicit.identity.application;

import com.clinicit.clinic.domain.DoctorProfileRepository;
import com.clinicit.common.domain.BusinessRuleException;
import com.clinicit.common.domain.InvalidRequestException;
import com.clinicit.common.domain.NotFoundException;
import com.clinicit.identity.api.CreateUserRequest;
import com.clinicit.identity.api.UserResponse;
import com.clinicit.identity.domain.Actor;
import com.clinicit.identity.domain.AuthSessionRepository;
import com.clinicit.identity.domain.Role;
import com.clinicit.identity.domain.UserAccount;
import com.clinicit.identity.domain.UserAccountRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/** Staff accounts, always within the acting admin's clinic. */
@Service
@Transactional
public class UserService {

    private final UserAccountRepository users;
    private final AuthSessionRepository sessions;
    private final DoctorProfileRepository doctors;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public UserService(
            UserAccountRepository users,
            AuthSessionRepository sessions,
            DoctorProfileRepository doctors,
            PasswordEncoder passwordEncoder,
            Clock clock
    ) {
        this.users = users;
        this.sessions = sessions;
        this.doctors = doctors;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    public UserResponse create(Actor admin, CreateUserRequest request) {
        if ((request.role() == Role.DOCTOR) != (request.doctorProfileId() != null)) {
            throw new InvalidRequestException("doctorProfileId is required for DOCTOR accounts and only for them");
        }
        if (request.doctorProfileId() != null) {
            doctors.findByIdAndClinicId(request.doctorProfileId(), admin.clinicId())
                    .orElseThrow(() -> new NotFoundException("Doctor not found"));
            if (users.existsByDoctorProfileId(request.doctorProfileId())) {
                throw new BusinessRuleException("DOCTOR_ALREADY_LINKED", "This doctor already has an account");
            }
        }
        String email = UserAccount.normalizeEmail(request.email());
        if (users.existsByEmail(email)) {
            throw new BusinessRuleException("EMAIL_TAKEN", "An account with this email already exists");
        }

        UserAccount user = new UserAccount(
                admin.clinicId(),
                email,
                passwordEncoder.encode(request.password()),
                request.fullName().trim(),
                request.role(),
                request.doctorProfileId()
        );
        return UserResponse.from(users.save(user));
    }

    @Transactional(readOnly = true)
    public UserResponse me(Actor actor) {
        return users.findByIdAndClinicId(actor.userId(), actor.clinicId())
                .map(UserResponse::from)
                .orElseThrow(() -> new NotFoundException("User not found"));
    }

    @Transactional(readOnly = true)
    public List<UserResponse> list(Actor admin) {
        return users.findByClinicIdOrderByFullNameAsc(admin.clinicId()).stream()
                .map(UserResponse::from)
                .toList();
    }

    /** Disables the account and revokes its sessions, so existing tokens stop working immediately. */
    public UserResponse disable(Actor admin, UUID userId) {
        if (admin.userId().equals(userId)) {
            throw new BusinessRuleException("CANNOT_DISABLE_SELF", "Admins cannot disable their own account");
        }
        UserAccount user = users.findByIdAndClinicId(userId, admin.clinicId())
                .orElseThrow(() -> new NotFoundException("User not found"));

        user.disable();
        sessions.revokeAllForUser(user.getId(), clock.instant());
        return UserResponse.from(user);
    }
}
