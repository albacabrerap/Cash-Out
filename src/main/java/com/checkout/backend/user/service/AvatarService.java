package com.checkout.backend.user.service;

import com.checkout.backend.exceptions.InvalidRequestException;
import com.checkout.backend.exceptions.ResourceNotFoundException;
import com.checkout.backend.user.dto.UserResponse;
import com.checkout.backend.user.model.User;
import com.checkout.backend.user.model.UserAvatar;
import com.checkout.backend.user.repository.UserAvatarRepository;
import com.checkout.backend.user.repository.UserRepository;
import java.io.IOException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/** La foto de perfil del propio usuario: JPG, PNG o GIF de hasta 2 MB. */
@Service
public class AvatarService {

    public static final int MAX_BYTES = 2 * 1024 * 1024;

    public record Avatar(String contentType, byte[] data) {
    }

    private final UserAvatarRepository avatars;
    private final UserRepository users;
    private final UserService userService;
    private final Clock clock;

    public AvatarService(UserAvatarRepository avatars, UserRepository users, UserService userService, Clock clock) {
        this.avatars = avatars;
        this.users = users;
        this.userService = userService;
        this.clock = clock;
    }

    @Transactional
    public UserResponse replace(User user, MultipartFile file) {
        byte[] data;
        try {
            data = file.getBytes();
        } catch (IOException e) {
            throw new InvalidRequestException("No se pudo leer la foto.");
        }
        if (data.length == 0) {
            throw new InvalidRequestException("La foto está vacía.");
        }
        if (data.length > MAX_BYTES) {
            throw new InvalidRequestException("La foto pesa más de 2 MB.");
        }
        String type = detectType(data);
        if (type == null) {
            throw new InvalidRequestException("La foto tiene que ser JPG, PNG o GIF.");
        }
        avatars.save(new UserAvatar(user.getId(), type, data));
        // La base guarda microsegundos: sin truncar, /users/me devolveria otra version de la misma foto.
        user.setAvatarUpdatedAt(LocalDateTime.now(clock.withZone(ZoneOffset.UTC)).truncatedTo(ChronoUnit.MILLIS));
        return userService.toResponse(users.save(user));
    }

    @Transactional
    public UserResponse remove(User user) {
        avatars.deleteById(user.getId());
        user.setAvatarUpdatedAt(null);
        return userService.toResponse(users.save(user));
    }

    @Transactional(readOnly = true)
    public Avatar find(User user) {
        return avatars.findById(user.getId())
                .map(avatar -> new Avatar(avatar.getContentType(), avatar.getData()))
                .orElseThrow(() -> new ResourceNotFoundException("La cuenta no tiene foto de perfil."));
    }

    /**
     * El tipo sale de los primeros bytes y no del encabezado de la peticion, que
     * lo escribe el cliente: un SVG o un HTML que dice ser PNG no entra.
     */
    static String detectType(byte[] data) {
        if (startsWith(data, 0xFF, 0xD8, 0xFF)) {
            return "image/jpeg";
        }
        if (startsWith(data, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) {
            return "image/png";
        }
        if (startsWith(data, 'G', 'I', 'F', '8')) {
            return "image/gif";
        }
        return null;
    }

    private static boolean startsWith(byte[] data, int... signature) {
        if (data.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if ((data[i] & 0xFF) != signature[i]) {
                return false;
            }
        }
        return true;
    }

}
