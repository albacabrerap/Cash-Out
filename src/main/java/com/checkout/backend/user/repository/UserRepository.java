package com.checkout.backend.user.repository;

import com.checkout.backend.user.model.User;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Acceso a la tabla users.
 */
public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * Busca por correo, que es la credencial con la que el usuario se
     * identifica y tiene constraint UNIQUE en la tabla.
     */
    Optional<User> findByEmail(String email);

    Optional<User> findByEmailIgnoreCase(String email);

    Optional<User> findByGoogleSubject(String googleSubject);

    boolean existsByEmail(String email);

}
