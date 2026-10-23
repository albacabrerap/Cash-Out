package com.checkout.backend.user.controller;

import com.checkout.backend.user.dto.ChangePasswordRequest;
import com.checkout.backend.user.dto.UpdateRolesRequest;
import com.checkout.backend.user.dto.UpdateStatusRequest;
import com.checkout.backend.user.dto.UpdateUserRequest;
import com.checkout.backend.user.dto.UserResponse;
import com.checkout.backend.user.service.AvatarService;
import com.checkout.backend.user.service.AvatarService.Avatar;
import com.checkout.backend.user.service.CurrentUserProvider;
import com.checkout.backend.user.service.UserService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * La cuenta del usuario autenticado.
 *
 * La ruta es /users/me y no /users/{id}: el usuario no necesita saber su propio
 * id para consultarse, y con /{id} habria que comprobar en cada endpoint que el
 * id es el suyo. Una ruta que no admite el id de otro no puede filtrar datos de
 * otro.
 */
@RestController
@RequestMapping("/users")
public class UserController {

    private final UserService userService;
    private final AvatarService avatarService;
    private final CurrentUserProvider currentUser;

    public UserController(UserService userService, AvatarService avatarService, CurrentUserProvider currentUser) {
        this.userService = userService;
        this.avatarService = avatarService;
        this.currentUser = currentUser;
    }

    /** GET /api/v1/users/me */
    @GetMapping("/me")
    public ResponseEntity<UserResponse> me() {
        return ResponseEntity.ok(userService.toResponse(currentUser.requireCurrentUser()));
    }

    /**
     * PATCH /api/v1/users/me
     *
     * Es PATCH y no PUT porque los dos campos son opcionales y el que no venga
     * se deja como esta. Un PUT prometeria reemplazar el recurso completo, y
     * entonces omitir el nombre deberia borrarlo.
     */
    @PatchMapping("/me")
    public ResponseEntity<UserResponse> updateMe(@Valid @RequestBody UpdateUserRequest request) {
        return ResponseEntity.ok(
                userService.updateProfile(currentUser.requireCurrentUser(), request));
    }

    /**
     * DELETE /api/v1/users/me
     *
     * Baja logica: la cuenta queda inactiva y sus tokens revocados, pero el
     * historial financiero se conserva.
     */
    /**
     * PUT /api/v1/users/me/password
     *
     * Devuelve 204 y no el usuario: no hay nada nuevo que mostrar, y la respuesta
     * de una operacion con credenciales es mejor que no lleve cuerpo.
     *
     * Ojo al efecto: cierra todas las sesiones, incluida la que hizo la llamada
     * en sus otros dispositivos. El access token actual sigue valiendo hasta que
     * expire, porque es sin estado, pero no se podra renovar.
     */
    @PutMapping("/me/password")
    public ResponseEntity<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        userService.changePassword(currentUser.requireCurrentUser(), request);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/me")
    public ResponseEntity<Void> deleteMe() {
        userService.deactivate(currentUser.requireCurrentUser());
        return ResponseEntity.noContent().build();
    }

    /** PUT /api/v1/users/me/avatar, multipart con la imagen en "file". Devuelve la cuenta con la foto nueva. */
    @PutMapping(path = "/me/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UserResponse> replaceAvatar(@RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(avatarService.replace(currentUser.requireCurrentUser(), file));
    }

    @DeleteMapping("/me/avatar")
    public ResponseEntity<UserResponse> removeAvatar() {
        return ResponseEntity.ok(avatarService.remove(currentUser.requireCurrentUser()));
    }

    /**
     * GET /api/v1/users/me/avatar
     *
     * Pide sesion, asi que la web no puede ponerla en un img: la descarga con su
     * token y la muestra desde memoria. Privada y sin cache compartida.
     */
    @GetMapping("/me/avatar")
    public ResponseEntity<byte[]> avatar() {
        Avatar avatar = avatarService.find(currentUser.requireCurrentUser());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(avatar.contentType()))
                .cacheControl(CacheControl.noCache().cachePrivate())
                .body(avatar.data());
    }

    /**
     * GET /api/v1/users
     *
     * Reservado a ADMIN. La proteccion por ruta de SecurityConfig solo exige
     * estar autenticado; es esta anotacion la que exige el rol, y por eso hacen
     * falta las dos capas: la ruta dice quien entra, la anotacion dice quien
     * puede ejecutar la operacion.
     *
     * Un usuario sin el rol recibe 403 con el cuerpo estandar, porque
     * @PreAuthorize lanza AccessDeniedException y el manejador global la
     * traduce.
     */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<UserResponse>> listAll() {
        return ResponseEntity.ok(userService.listAll());
    }

    /**
     * PUT /api/v1/users/{id}/roles
     *
     * Reservado a ADMIN y nunca sobre la propia cuenta. Devuelve la cuenta con
     * sus roles nuevos; el usuario los ve al renovar su sesion.
     */
    @PutMapping("/{id}/roles")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<UserResponse> updateRoles(@PathVariable Long id,
                                                    @Valid @RequestBody UpdateRolesRequest request) {
        return ResponseEntity.ok(
                userService.updateRoles(currentUser.requireCurrentUser(), id, request.getRoles()));
    }

    /**
     * PUT /api/v1/users/{id}/status
     *
     * Bloquea (BLOCKED) o reactiva (ACTIVE) otra cuenta. Es PUT por lo mismo que
     * la cotizacion de un activo: hay un solo estado y esto lo reemplaza.
     */
    @PutMapping("/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<UserResponse> updateStatus(@PathVariable Long id,
                                                     @Valid @RequestBody UpdateStatusRequest request) {
        return ResponseEntity.ok(
                userService.updateStatus(currentUser.requireCurrentUser(), id, request.getStatus()));
    }

}
