package com.arcane.arcana.user.service;

import com.arcane.arcana.common.entity.User;
import com.arcane.arcana.user.dto.RegisterDto;
import com.arcane.arcana.user.dto.UpdateDto;
import com.arcane.arcana.user.dto.LoginDto;
import com.arcane.arcana.user.dto.LoginResponseDto;
import com.arcane.arcana.user.repository.UserRepository;
import com.arcane.arcana.common.util.JwtUtil;
import com.arcane.arcana.common.service.RedisService;
import com.arcane.arcana.common.exception.CustomException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.mail.javamail.MimeMessagePreparator;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.util.Random;
import java.util.UUID;

/**
 * 사용자 서비스 클래스 구현
 */
@Service
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final RedisService redisService;
    private final JavaMailSender mailSender;
    private final AuthenticationManager authenticationManager;
    private final SpringTemplateEngine templateEngine;

    @Value("${spring.mail.username}")
    private String senderEmail;

    @Value("${app.domain}")
    private String appDomain;

    public UserServiceImpl(UserRepository userRepository, PasswordEncoder passwordEncoder,
        JwtUtil jwtUtil, RedisService redisService, JavaMailSender mailSender,
        AuthenticationManager authenticationManager, SpringTemplateEngine templateEngine) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.redisService = redisService;
        this.mailSender = mailSender;
        this.authenticationManager = authenticationManager;
        this.templateEngine = templateEngine;
    }

    @Override
    public void sendAuthNumber(String email) {
        if (userRepository.existsByEmail(email)) {
            throw new CustomException("이미 존재하는 이메일입니다.", HttpStatus.BAD_REQUEST);
        }

        String authNumber = generateVerificationCode();
        redisService.setStringValue("email_auth:" + email, authNumber, 10); // 10분 유효

        sendAuthNumberEmail(email, authNumber);
    }

    private void sendAuthNumberEmail(String recipientEmail, String authNumber) {
        String subject = "Arcana 이메일 인증번호";

        // Thymeleaf 컨텍스트 설정
        Context context = new Context();
        context.setVariable("authNumber", authNumber);

        // 템플릿을 HTML 문자열로 변환
        String htmlContent = templateEngine.process("email-verification", context);

        MimeMessagePreparator messagePreparator = mimeMessage -> {
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");
            helper.setFrom(senderEmail, "Arcana Team");
            helper.setTo(recipientEmail);
            helper.setSubject(subject);
            helper.setText(htmlContent, true); // HTML 형식 설정
        };

        mailSender.send(messagePreparator);
    }

    private String generateVerificationCode() {
        Random random = new Random();
        int code = 100000 + random.nextInt(900000); // 6자리 인증번호 생성
        return String.valueOf(code);
    }

    @Override
    public void verifyAuthNumber(String email, String authNumber) {
        String redisAuthNumber = redisService.getStringValue("email_auth:" + email);
        if (redisAuthNumber == null || !redisAuthNumber.equals(authNumber)) {
            throw new CustomException("인증번호가 일치하지 않습니다.", HttpStatus.BAD_REQUEST);
        }

        // 인증 완료 상태를 Redis에 설정
        redisService.setStringValue("email_verified:" + email, "verified", 60); // 60분 유효

        // 인증 번호 키 삭제
        redisService.deleteValue("email_auth:" + email);
    }

    @Override
    @Transactional
    public void registerUser(RegisterDto registerDto) {
        String email = registerDto.getEmail();

        // 인증 완료 상태 확인
        if (!redisService.exists("email_verified:" + email)) {
            throw new CustomException("이메일 인증이 필요합니다.", HttpStatus.FORBIDDEN);
        }

        // 인증 완료 상태 삭제
        redisService.deleteValue("email_verified:" + email);

        // 사용자 등록
        User user = new User();
        user.setEmail(email);
        user.setNickname(registerDto.getNickname());
        user.encodePassword(registerDto.getPassword(), passwordEncoder);
        userRepository.save(user);

        // 회원 가입 완료 이메일 전송
        sendRegistrationConfirmationEmail(user);
    }

    private void sendRegistrationConfirmationEmail(User user) {
        String subject = "Arcana 회원 가입을 축하드립니다!";

        // Thymeleaf 컨텍스트 설정
        Context context = new Context();
        context.setVariable("email", user.getEmail());
        context.setVariable("nickname", user.getNickname());

        // 템플릿을 HTML 문자열로 변환
        String htmlContent = templateEngine.process("registration-confirmation", context);

        MimeMessagePreparator messagePreparator = mimeMessage -> {
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");
            helper.setFrom(senderEmail, "Arcana Team");
            helper.setTo(user.getEmail());
            helper.setSubject(subject);
            helper.setText(htmlContent, true); // HTML 형식 설정
        };

        mailSender.send(messagePreparator);
    }

    @Override
    @Transactional
    public void updateUser(Long userId, UpdateDto updateDto) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new CustomException("사용자를 찾을 수 없습니다.", HttpStatus.NOT_FOUND));

        if (updateDto.getNickname() != null && !updateDto.getNickname().isEmpty() &&
            !updateDto.getNickname().equals(user.getNickname())) {
            if (userRepository.existsByNickname(updateDto.getNickname())) {
                throw new CustomException("이미 존재하는 닉네임입니다.", HttpStatus.BAD_REQUEST);
            }
            user.setNickname(updateDto.getNickname());
        }

        if (updateDto.getPassword() != null && !updateDto.getPassword().isEmpty()) {
            if (updateDto.getOldPassword() == null || !passwordEncoder.matches(
                updateDto.getOldPassword(), user.getPassword())) {
                throw new CustomException("기존 비밀번호가 일치하지 않습니다.", HttpStatus.BAD_REQUEST);
            }
            user.encodePassword(updateDto.getPassword(), passwordEncoder);
        }

        userRepository.save(user);
    }

    @Override
    public void logout(String email, String accessToken) {
        redisService.deleteValue("refresh_token:" + email);
    }

    @Override
    public Long getUserIdByEmail(String email) {
        return userRepository.findByEmail(email)
            .orElseThrow(() -> new CustomException("사용자를 찾을 수 없습니다.", HttpStatus.NOT_FOUND))
            .getId();
    }

    @Override
    public void updateRefreshToken(String email, String newRefreshToken) {
        redisService.setStringValue("refresh_token:" + email, newRefreshToken,
            jwtUtil.getRefreshTokenExpirationMinutes());
    }

    @Override
    public String getStoredRefreshToken(String email) {
        return redisService.getStringValue("refresh_token:" + email);
    }

    @Override
    @Transactional
    public void saveLanguage(String email, String language) {
        User user = userRepository.findByEmail(email)
            .orElseThrow(() -> new CustomException("사용자를 찾을 수 없습니다.", HttpStatus.NOT_FOUND));
        user.setLanguage(language);
        userRepository.save(user);
    }

    @Override
    public boolean isNicknameAvailable(String nickname) {
        return !userRepository.existsByNickname(nickname);
    }

    @Override
    public void sendPasswordResetEmail(String email) {
        User user = userRepository.findByEmail(email)
            .orElseThrow(() -> new CustomException("이메일을 찾을 수 없습니다.", HttpStatus.NOT_FOUND));

        String resetToken = jwtUtil.generatePasswordResetToken(email);
        redisService.setStringValue("password_reset:" + email, resetToken, 60); // 60분 유효

        sendPasswordResetEmail(email, resetToken);
    }

    private void sendPasswordResetEmail(String recipientEmail, String token) {
        String resetUrl =
            appDomain + "/user/reset-password?email=" + recipientEmail + "&token=" + token;
        String subject = "Arcana 비밀번호 재설정";

        // Thymeleaf 컨텍스트 설정
        Context context = new Context();
        context.setVariable("resetUrl", resetUrl);

        // 템플릿을 HTML 문자열로 변환
        String htmlContent = templateEngine.process("password-reset-email", context);

        MimeMessagePreparator messagePreparator = mimeMessage -> {
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");
            helper.setFrom(senderEmail, "Arcana Team");
            helper.setTo(recipientEmail);
            helper.setSubject(subject);
            helper.setText(htmlContent, true); // HTML 형식 설정
        };

        mailSender.send(messagePreparator);
    }

    @Override
    @Transactional
    public void resetPassword(String email, String token, String newPassword) {
        String redisToken = redisService.getStringValue("password_reset:" + email);
        if (redisToken == null || !redisToken.equals(token)) {
            throw new CustomException("유효하지 않은 토큰입니다.", HttpStatus.BAD_REQUEST);
        }

        User user = userRepository.findByEmail(email)
            .orElseThrow(() -> new CustomException("사용자를 찾을 수 없습니다.", HttpStatus.NOT_FOUND));

        newPassword = newPassword.trim();

        user.encodePassword(newPassword, passwordEncoder);
        userRepository.save(user);

        redisService.deleteValue("password_reset:" + email);
    }

    @Override
    public boolean isResetTokenValid(String email, String token) {
        String redisToken = redisService.getStringValue("password_reset:" + email);
        return redisToken != null && redisToken.equals(token);
    }

    @Override
    public User getUserByEmail(String email) {
        return userRepository.findByEmail(email)
            .orElseThrow(() -> new CustomException("사용자를 찾을 수 없습니다.", HttpStatus.NOT_FOUND));
    }

    @Override
    public LoginResponseDto login(LoginDto loginDto) {
        try {
            Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(loginDto.getEmail(), loginDto.getPassword())
            );

            String accessToken = jwtUtil.generateAccessToken(authentication.getName());
            String refreshToken = jwtUtil.generateRefreshToken(authentication.getName());

            // Redis에 Refresh Token 저장
            redisService.setStringValue("refresh_token:" + authentication.getName(), refreshToken,
                jwtUtil.getRefreshTokenExpirationMinutes());

            Long userId = getUserIdByEmail(authentication.getName());
            User user = getUserByEmail(authentication.getName());

            return new LoginResponseDto(
                accessToken,
                refreshToken,
                userId,
                user.getNickname(),
                user.getLanguage()
            );

        } catch (AuthenticationException e) {
            throw new CustomException("인증 실패: 이메일 또는 비밀번호가 올바르지 않습니다.", HttpStatus.UNAUTHORIZED);
        }
    }

    @Override
    @Transactional
    public void withdrawUser(Long userId) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new CustomException("사용자를 찾을 수 없습니다.", HttpStatus.NOT_FOUND));

        user.deactivate();
        userRepository.save(user);

        // 복구 토큰 생성 및 저장
        String restoreToken = UUID.randomUUID().toString();
        redisService.setStringValue("restore_token:" + user.getEmail(), restoreToken, 43200);

        sendRestoreEmail(user.getEmail(), restoreToken);
    }

    private void sendRestoreEmail(String email, String restoreToken) {
        String restoreUrl = appDomain + "/user/restore?email=" + email + "&token=" + restoreToken;
        String subject = "Arcana 계정 복구 링크";
        Context context = new Context();
        context.setVariable("restoreUrl", restoreUrl);
        String htmlContent = templateEngine.process("account-restore-email", context);

        MimeMessagePreparator messagePreparator = mimeMessage -> {
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");
            helper.setFrom(senderEmail, "Arcana Team");
            helper.setTo(email);
            helper.setSubject(subject);
            helper.setText(htmlContent, true);
        };
        mailSender.send(messagePreparator);
    }

    @Override
    @Transactional
    public void restoreUser(String email, String restoreToken) {
        String storedToken = redisService.getStringValue("restore_token:" + email);
        if (storedToken == null || !storedToken.equals(restoreToken)) {
            throw new CustomException("유효하지 않은 복구 요청입니다.", HttpStatus.BAD_REQUEST);
        }

        User user = userRepository.findByEmailIncludingDeleted(email)
            .orElseThrow(() -> new CustomException("사용자를 찾을 수 없습니다.", HttpStatus.NOT_FOUND));

        if (!user.isDeleted()) {
            throw new CustomException("이미 활성화된 사용자입니다.", HttpStatus.BAD_REQUEST);
        }

        user.reactivate();
        userRepository.save(user);
        redisService.deleteValue("restore_token:" + email);
    }
}
