package com.bombay.restaurantintelligence.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

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
        UserDetails owner = User.withUsername(username)
                .password(encoder.encode(password))
                .roles("OWNER")
                .build();

        return requestedUsername -> {
            if (!owner.getUsername().equals(requestedUsername)) {
                throw new UsernameNotFoundException("User not found");
            }
            return owner;
        };
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health", "/api/whatsapp/webhook", "/", "/index.html", "/assets/**", "/favicon.ico")
                        .permitAll()
                        .anyRequest().authenticated())
                .httpBasic(Customizer.withDefaults())
                .build();
    }
}
