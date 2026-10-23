package com.checkout.backend.user.service;

import com.checkout.backend.exceptions.InvalidRequestException;
import com.checkout.backend.exceptions.ResourceNotFoundException;
import com.checkout.backend.exceptions.UnauthenticatedException;
import com.checkout.backend.token_wallet.refresh_token.service.RefreshTokenService;
import com.checkout.backend.user.dto.ChangePasswordRequest;
import com.checkout.backend.user.dto.UpdateUserRequest;
import com.checkout.backend.user.dto.UserResponse;
import com.checkout.backend.user.model.Role;
import com.checkout.backend.user.model.User;
import com.checkout.backend.user.model.UserStatus;
import com.checkout.backend.user.repository.UserRepository;
import java.util.List;
import java.util.Set;
import org.modelmapper.ModelMapper;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consulta y mantenimiento de la cuenta.
 */
@Service
public class UserService {

    private final UserRepository userRepository;
    private final RefreshTokenService refreshTokenService;
    private final PasswordEncoder passwordEncoder;
    private final ModelMapper mapper;

    public UserService(UserRepository userRepository,
                       RefreshTokenService refreshTokenService,
                       PasswordEncoder passwordEncoder,
                       ModelMapper mapper) {
        this.userRepository = userRepository;
        this.refreshTokenService = refreshTokenService;
        this.passwordEncoder = passwordEncoder;
        this.mapper = mapper;
    }

    public UserResponse toResponse(User user) {
        return mapper.map(user, UserResponse.class);
    }

    /**
     * Actualiza los campos que el usuario puede cambiar de si mismo.
     *
     * UpdateUserRequest no incluye correo, roles ni estado, y eso es lo que
     * impide que alguien se ascienda a ADMIN con un campo de mas en el JSON. Los
     * dos campos son opcionales: un null significa "no lo toques", no "borralo".
     */
    @Transactional
    public UserResponse updateProfile(User user, UpdateUserRequest request) {
        if (request.getName() != null) {
            user.setName(request.getName());
        }
        if (request.getBirthDate() != null) {
            user.setBirthDate(request.getBirthDate());
        }
        return toResponse(userRepository.save(user));
    }

    /**
     * Cambia la contraseña del propio usuario.
     *
     * Dos decisiones que van juntas:
     *
     * La contraseña actual se verifica aunque quien llama ya venga autenticado.
     * El token dice que alguien entro con esa cuenta, no que sea su dueno; si un
     * access token robado bastara para cambiar la contraseña, robar un token
     * equivaldria a quedarse la cuenta.
     *
     * Y al cambiarla se revocan todos los refresh tokens. Cambiar la contraseña es
     * lo que hace alguien que sospecha que su cuenta esta comprometida, y si las
     * sesiones abiertas siguieran renovandose, ese gesto no serviria para nada:
     * quien tuviera un refresh token seguiria dentro treinta dias. El precio es
     * que el propio usuario tiene que volver a entrar en sus otros dispositivos,
     * que es exactamente lo que se espera de este cambio.
     */
    @Transactional
    public void changePassword(User user, ChangePasswordRequest request) {
        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPasswordHash())) {
            throw new UnauthenticatedException("La contrasena actual no es correcta.");
        }

        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);

        refreshTokenService.revokeAll(user);
    }

    /**
     * Da de baja la cuenta sin borrarla.
     *
     * Es baja logica porque la fila esta referenciada por ahorros, metas,
     * ingresos y gastos: borrarla destruiria el historial financiero o romperia
     * las claves foraneas. El correo sigue siendo unico entre todos los estados,
     * de modo que volver es reactivar y no registrarse otra vez.
     *
     * Revocar los tokens es parte de la baja: sin eso la cuenta queda inactiva
     * pero cualquier refresh token emitido seguiria sirviendo para renovar.
     */
    @Transactional
    public void deactivate(User user) {
        user.setStatus(UserStatus.INACTIVE);
        userRepository.save(user);
        refreshTokenService.revokeAll(user);
    }

    /**
     * Listado completo de usuarios.
     *
     * El control de rol esta aqui ademas de en el controller, y la duplicacion
     * es deliberada. La anotacion del controller protege una ruta; esta protege
     * la operacion. El dia que otro punto del codigo llame a este metodo — una
     * tarea programada, un endpoint nuevo, un listener de eventos — heredara la
     * restriccion sin que nadie tenga que acordarse de repetirla.
     */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public List<UserResponse> listAll() {
        return userRepository.findAll().stream().map(this::toResponse).toList();
    }

    /**
     * Reemplaza los roles de otra cuenta.
     *
     * Nadie cambia los suyos: asi un administrador no puede quitarse el rol por
     * error, y siempre queda al menos uno, el que hace la llamada. GUEST va solo
     * porque es de solo lectura; sumarle USER o ADMIN no tendria sentido.
     *
     * Los roles viajan dentro del token de acceso, que el filtro no vuelve a
     * contrastar con la base. Por eso se revocan los refresh tokens: el cambio
     * se aplica en la proxima renovacion, como tarde en lo que dure el token.
     */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public UserResponse updateRoles(User actor, Long id, Set<Role> roles) {
        User target = findOther(actor, id,
                "No puedes cambiar tus propios roles; pidele el cambio a otro administrador.");
        if (roles.contains(Role.GUEST) && roles.size() > 1) {
            throw new InvalidRequestException("GUEST es de solo lectura y no se combina con otros roles.");
        }
        if (target.getRoles().equals(roles)) {
            return toResponse(target);
        }

        target.getRoles().clear();
        target.getRoles().addAll(roles);
        userRepository.save(target);
        refreshTokenService.revokeAll(target);
        return toResponse(target);
    }

    /**
     * Bloquea o reactiva otra cuenta.
     *
     * ACTIVE tambien sirve para devolver una cuenta dada de baja: volver es
     * reactivar, no registrarse otra vez. Bloquear revoca los refresh tokens y,
     * como CurrentUserProvider rechaza cuentas que no estan activas, el token de
     * acceso que ya tenia deja de servir en la siguiente peticion.
     */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public UserResponse updateStatus(User actor, Long id, UserStatus status) {
        if (status == UserStatus.INACTIVE) {
            throw new InvalidRequestException(
                    "La baja la pide cada usuario desde su cuenta. Para impedir el acceso, bloqueala.");
        }
        User target = findOther(actor, id, "No puedes bloquear ni reactivar tu propia cuenta.");
        if (target.getStatus() == status) {
            return toResponse(target);
        }

        target.setStatus(status);
        userRepository.save(target);
        if (status == UserStatus.BLOCKED) {
            refreshTokenService.revokeAll(target);
        }
        return toResponse(target);
    }

    private User findOther(User actor, Long id, String selfMessage) {
        if (actor.getId().equals(id)) {
            throw new InvalidRequestException(selfMessage);
        }
        return userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario", id));
    }

}
