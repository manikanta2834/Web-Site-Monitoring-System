package com.wsms.service;

import com.wsms.entity.User;
import com.wsms.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
public class RateLimitingService {

    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final long LOCKOUT_DURATION_MINUTES = 15;

    private final UserRepository userRepository;

    public RateLimitingService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional
    public void registerFailedAttempt(String usernameOrEmail) {
        userRepository.findByUsernameOrEmail(usernameOrEmail, usernameOrEmail).ifPresent(user -> {
            int newAttempts = user.getFailedAttempts() + 1;
            user.setFailedAttempts(newAttempts);
            if (newAttempts >= MAX_FAILED_ATTEMPTS) {
                user.setLockoutTime(Instant.now().plus(LOCKOUT_DURATION_MINUTES, ChronoUnit.MINUTES));
            }
            userRepository.save(user);
        });
    }

    @Transactional
    public void resetFailedAttempts(String usernameOrEmail) {
        userRepository.findByUsernameOrEmail(usernameOrEmail, usernameOrEmail).ifPresent(user -> {
            user.setFailedAttempts(0);
            user.setLockoutTime(null);
            userRepository.save(user);
        });
    }

    public boolean isLocked(User user) {
        if (user.getLockoutTime() == null) {
            return false;
        }
        return Instant.now().isBefore(user.getLockoutTime());
    }
}
