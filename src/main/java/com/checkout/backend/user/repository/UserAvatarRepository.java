package com.checkout.backend.user.repository;

import com.checkout.backend.user.model.UserAvatar;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserAvatarRepository extends JpaRepository<UserAvatar, Long> {
}
