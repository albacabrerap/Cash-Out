package com.checkout.backend.user.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** La foto de perfil de una cuenta, aparte de User para que cargar la cuenta no traiga los bytes. */
@Entity
@Table(name = "user_avatars")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class UserAvatar {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "content_type", nullable = false, length = 20)
    private String contentType;

    // Con un largo, Hibernate pediria BLOB, que H2 en modo PostgreSQL no conoce; bytea vale en los dos.
    @Column(nullable = false, columnDefinition = "bytea")
    private byte[] data;

}
