package com.checkout.backend.user.service;

import com.checkout.backend.exceptions.UnauthenticatedException;
import com.checkout.backend.security.JwtPrincipal;
import com.checkout.backend.security.UserPrincipal;
import com.checkout.backend.user.model.User;
import com.checkout.backend.user.model.UserStatus;
import com.checkout.backend.user.repository.UserRepository;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementacion sobre el SecurityContext de Spring Security.
 *
 * Toma el nombre del Authentication, que en esta aplicacion es el correo, y
 * busca la fila del usuario. Cuando el issue #7 traiga un UserDetails propio que
 * ya cargue la entidad, esta clase se simplifica a leer el principal; el resto
 * del codigo no se entera porque depende de la interfaz.
 */
@Service
public class SecurityContextCurrentUserProvider implements CurrentUserProvider {

    private final UserRepository userRepository;

    public SecurityContextCurrentUserProvider(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public User requireCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        // Un AnonymousAuthenticationToken no es una identidad: su
        // isAuthenticated() devuelve true aunque nadie se haya autenticado.
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            throw new UnauthenticatedException("Debes autenticarte para acceder a este recurso.");
        }

        // Cuando la autenticacion viene del login, el principal ya es el
        // UserPrincipal y trae la entidad: usarla ahorra una consulta. Cuando
        // viene del filtro JWT el principal es solo el correo, porque el filtro
        // no toca la base a proposito, y entonces si hay que buscarla.
        if (authentication.getPrincipal() instanceof UserPrincipal principal) {
            return principal.getUser();
        }

        // Peticion autenticada por token: el claim uid evita buscar por correo.
        // Es la misma fila, pero por clave primaria en vez de por un indice
        // secundario, y es lo que hace que ese claim no sea decorativo.
        if (authentication.getPrincipal() instanceof JwtPrincipal jwtPrincipal
                && jwtPrincipal.id() != null) {
            return requireActive(userRepository.findById(jwtPrincipal.id())
                    .orElseThrow(() -> new UnauthenticatedException("La sesion ya no es valida.")));
        }

        return requireActive(userRepository.findByEmail(authentication.getName())
                // El token es valido pero el usuario ya no existe. Es 401 y no
                // 404: el recurso que falta es la identidad, no lo que se pidio.
                .orElseThrow(() -> new UnauthenticatedException("La sesion ya no es valida.")));
    }

    /**
     * El token sigue firmado aunque la cuenta se haya bloqueado o dado de baja
     * despues de emitirlo. Como aqui ya se carga la fila, comprobar el estado no
     * cuesta otra consulta y corta el acceso en la siguiente peticion, en vez de
     * esperar a que el token expire.
     */
    private static User requireActive(User user) {
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new UnauthenticatedException("Tu cuenta no esta activa.");
        }
        return user;
    }

}
