package com.checkout.backend.assistant.receipt;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScannedReceiptRepository extends JpaRepository<ScannedReceipt, Long> {

    Optional<ScannedReceipt> findByUserIdAndFingerprint(Long userId, String fingerprint);

}
