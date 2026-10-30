package com.checkout.backend.assistant.dto;

import java.util.List;

/** Vacio si el mensaje no tenia nada que describir ("ayer", "con yape"). */
public record DraftsResponse(List<Draft> drafts) {
}
