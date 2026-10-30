package com.checkout.backend.game;

import com.checkout.backend.user.model.User;
import java.security.SecureRandom;
import java.util.function.Predicate;

/** Lo comun a las salas de los juegos en vivo: el codigo para entrar y el nombre que se muestra. */
public final class GameRooms {

    /** Sin 0, O, 1 ni I: se leen mal en un proyector. */
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private GameRooms() {
    }

    public static String newCode(SecureRandom random, Predicate<String> taken) {
        String code;
        do {
            StringBuilder builder = new StringBuilder(6);
            for (int i = 0; i < 6; i++) {
                builder.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
            }
            code = builder.toString();
        } while (taken.test(code));
        return code;
    }

    public static String firstName(User user) {
        String first = user.getName().strip().split("\\s+")[0];
        return first.length() <= 20 ? first : first.substring(0, 20);
    }

}
