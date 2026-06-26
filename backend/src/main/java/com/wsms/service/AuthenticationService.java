package com.wsms.service;

import com.wsms.dto.LoginRequestDto;
import com.wsms.dto.RegisterRequestDto;
import com.wsms.dto.TokenResponseDto;
import com.wsms.entity.RefreshToken;
import com.wsms.entity.Role;
import com.wsms.entity.User;
import com.wsms.repository.RefreshTokenRepository;
import com.wsms.repository.RoleRepository;
import com.wsms.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class AuthenticationService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;
    private final RateLimitingService rateLimitingService;
    private final RecaptchaService recaptchaService;
    private final EncryptionService encryptionService;

    public AuthenticationService(
            UserRepository userRepository,
            RoleRepository roleRepository,
            RefreshTokenRepository refreshTokenRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            AuthenticationManager authenticationManager,
            RateLimitingService rateLimitingService,
            RecaptchaService recaptchaService,
            EncryptionService encryptionService
    ) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.authenticationManager = authenticationManager;
        this.rateLimitingService = rateLimitingService;
        this.recaptchaService = recaptchaService;
        this.encryptionService = encryptionService;
    }

    @Transactional
    public TokenResponseDto login(LoginRequestDto request) {
        // Perform Captcha Bot Check Verification
        recaptchaService.verifyToken(request.getCaptchaToken(), request.getCaptchaProvider());

        String usernameOrEmail = request.getUsernameOrEmail();

        User user = userRepository.findByUsernameOrEmail(usernameOrEmail, usernameOrEmail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password"));

        if (rateLimitingService.isLocked(user)) {
            throw new ResponseStatusException(HttpStatus.LOCKED, "Account is locked due to too many failed attempts. Please try again after 15 minutes.");
        }

        String plainPassword = encryptionService.decrypt(request.getPassword());
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(user.getUsername(), plainPassword)
            );
        } catch (BadCredentialsException e) {
            rateLimitingService.registerFailedAttempt(usernameOrEmail);
            User updatedUser = userRepository.findByUsernameOrEmail(usernameOrEmail, usernameOrEmail).orElse(user);
            if (rateLimitingService.isLocked(updatedUser)) {
                throw new ResponseStatusException(HttpStatus.LOCKED, "Account has been locked due to 5 consecutive failed attempts. Please try again after 15 minutes.");
            }
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password");
        } catch (LockedException e) {
            throw new ResponseStatusException(HttpStatus.LOCKED, "Account is locked.");
        }

        rateLimitingService.resetFailedAttempts(usernameOrEmail);

        String accessToken = jwtService.generateAccessToken(user);
        RefreshToken refreshToken = createRefreshToken(user);

        List<String> roles = user.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toList());

        return new TokenResponseDto(
                accessToken,
                refreshToken.getToken(),
                user.getUsername(),
                user.getEmail(),
                roles
        );
    }

    @Transactional
    public User register(RegisterRequestDto request) {
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Username is already taken");
        }
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Email is already registered");
        }

        String plainPassword = encryptionService.decrypt(request.getPassword());
        if (plainPassword.length() < 6) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password must be at least 6 characters long");
        }

        User user = new User(
                request.getUsername(),
                request.getEmail(),
                passwordEncoder.encode(plainPassword)
        );

        Role userRole = roleRepository.findByName("ROLE_VIEWER")
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Default role not found"));

        user.setRoles(Collections.singleton(userRole));
        return userRepository.save(user);
    }

    @Transactional
    public TokenResponseDto refresh(String token) {
        RefreshToken refreshToken = refreshTokenRepository.findByToken(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid refresh token"));

        if (refreshToken.getExpiryDate().isBefore(Instant.now())) {
            refreshTokenRepository.delete(refreshToken);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh token expired. Please login again.");
        }

        User user = refreshToken.getUser();
        String newAccessToken = jwtService.generateAccessToken(user);
        
        refreshTokenRepository.delete(refreshToken);
        RefreshToken newRefreshToken = createRefreshToken(user);

        List<String> roles = user.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toList());

        return new TokenResponseDto(
                newAccessToken,
                newRefreshToken.getToken(),
                user.getUsername(),
                user.getEmail(),
                roles
        );
    }

    @Transactional
    public void logout(String token) {
        refreshTokenRepository.findByToken(token).ifPresent(refreshTokenRepository::delete);
    }

    private RefreshToken createRefreshToken(User user) {
        refreshTokenRepository.deleteByUser(user);
        
        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setUser(user);
        refreshToken.setExpiryDate(Instant.now().plus(7, ChronoUnit.DAYS));
        refreshToken.setToken(UUID.randomUUID().toString());
        return refreshTokenRepository.save(refreshToken);
    }
}
