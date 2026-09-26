package com.bombay.restaurantintelligence.config;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;

@Configuration
public class SecurityConfig {
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    UserDetailsService users(@Value("${app.owner.username}") String username,
                             @Value("${app.owner.password}") String password,
                             PasswordEncoder encoder) {
        String encodedPassword = encoder.encode(password);

        return requestedUsername -> {
            if (!username.equals(requestedUsername)) {
                throw new UsernameNotFoundException("User not found");
            }

            return User.withUsername(username)
                    .password(encodedPassword)
                    .roles("OWNER")
                    .build();
        };
    }

    @Bean
    AuthenticationEntryPoint restAuthenticationEntryPoint() {
        return (request, response, authException) -> {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"message\":\"Unauthorized\"}");
        };
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http,
                                 AuthenticationEntryPoint restAuthenticationEntryPoint,
                                 @Value("${app.internal-api.secret:}") String internalApiSecret,
                                 @Value("${app.internal-api.max-requests-per-minute:120}") int internalApiRateLimit) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health", "/api/whatsapp/webhook", "/api/internal/**", "/", "/index.html", "/assets/**", "/favicon.ico")
                        .permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e.authenticationEntryPoint(restAuthenticationEntryPoint))
                .httpBasic(basic -> basic.authenticationEntryPoint(restAuthenticationEntryPoint))
                .addFilterBefore(new InternalApiAuthenticationFilter(internalApiSecret, internalApiRateLimit), BasicAuthenticationFilter.class)
                .build();
    }
}
