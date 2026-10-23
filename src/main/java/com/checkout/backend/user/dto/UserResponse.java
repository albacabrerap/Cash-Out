package com.checkout.backend.user.dto;

import com.checkout.backend.user.model.Role;
import com.checkout.backend.user.model.UserStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Never exposes passwordHash. That omission is the whole point of the DTO.
 */
/**
 * Built once by the service with the builder and handed straight to Jackson.
 * No setter on purpose: a response object that can still be mutated invites a
 * controller to rewrite a field after the service already decided it, and that
 * change is invisible in review. ModelMapper writes the private fields
 * directly, which is why the mapping still works without them.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserResponse {

    private Long id;

    private String name;

    private String email;

    private Set<Role> roles;

    private UserStatus status;

    private LocalDate birthDate;

    private LocalDateTime createdAt;

    /** null si no tiene foto; si cambia, la foto que tenia el cliente ya no vale. */
    private LocalDateTime avatarUpdatedAt;

}
