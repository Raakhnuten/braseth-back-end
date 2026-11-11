package com.braseth.ecommerce.infrastructure.mail;

import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.braseth.ecommerce.config.AppProperties;

import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailServiceImpl implements EmailService {

    private final JavaMailSender mailSender;
    private final AppProperties appProperties;

    @Override
    @Async
    public Mono<Void> sendVerificationEmail(String email, String name, String token) {
        String subject = "Verify Your Email - Braseth Printing Shop";
        String verificationUrl = appProperties.getFrontendUrl() + "/verify-email?token=" + token;

        String body = String.format("""
            <html>
            <body>
                <h2>Welcome to Braseth Printing Shop!</h2>
                <p>Hi %s,</p>
                <p>Thank you for registering. Please click the link below to verify your email address:</p>
                <p><a href="%s">Verify Email</a></p>
                <p>This link will expire in 24 hours.</p>
                <p>If you did not create an account, please ignore this email.</p>
                <br>
                <p>Best regards,</p>
                <p>Braseth Printing Shop Team</p>
            </body>
            </html>
            """, name, verificationUrl);

        return sendEmail(email, subject, body, true);
    }

    @Override
    @Async
    public Mono<Void> sendPasswordResetEmail(String email, String name, String token) {
        String subject = "Reset Your Password - Braseth Printing Shop";
        String resetUrl = appProperties.getFrontendUrl() + "/reset-password?token=" + token;

        String body = String.format("""
            <html>
            <body>
                <h2>Password Reset Request</h2>
                <p>Hi %s,</p>
                <p>We received a request to reset your password. Click the link below to create a new password:</p>
                <p><a href="%s">Reset Password</a></p>
                <p>This link will expire in 1 hour.</p>
                <p>If you did not request a password reset, please ignore this email.</p>
                <br>
                <p>Best regards,</p>
                <p>Braseth Printing Shop Team</p>
            </body>
            </html>
            """, name, resetUrl);

        return sendEmail(email, subject, body, true);
    }

    @Override
    @Async
    public Mono<Void> sendWelcomeEmail(String email, String name) {
        String subject = "Welcome to Braseth Printing Shop!";

        String body = String.format("""
            <html>
            <body>
                <h2>Welcome to Braseth Printing Shop!</h2>
                <p>Hi %s,</p>
                <p>Your account has been successfully verified.</p>
                <p>You can now start exploring our products and placing orders.</p>
                <br>
                <p>Best regards,</p>
                <p>Braseth Printing Shop Team</p>
            </body>
            </html>
            """, name);

        return sendEmail(email, subject, body, true);
    }

    @Override
    @Async
    public Mono<Void> sendEmail(String to, String subject, String body, boolean html) {
        return Mono.fromRunnable(() -> {
            try {
                MimeMessage message = mailSender.createMimeMessage();
                MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
                helper.setTo(to);
                helper.setSubject(subject);
                helper.setText(body, html);

                log.info("Sending email to: {}", to);
                mailSender.send(message);
                log.info("Email sent successfully to: {}", to);
            } catch (Exception e) {
                log.error("Failed to send email to {}: {}", to, e.getMessage(), e);
                throw new RuntimeException("Failed to send email", e);
            }
        });
    }
}