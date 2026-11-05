package com.zhigang.platform.config;

import com.zhigang.platform.security.AnonymousSessionFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.ArrayList;
import java.util.List;

@Configuration
public class SecurityConfig {

    @Bean
    AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            AnonymousSessionFilter anonymousSessionFilter,
            @Value("${zhigang.security.dev-permit-all:false}") boolean devPermitAll
    ) throws Exception {
        http.cors(Customizer.withDefaults());
        if (devPermitAll) {
            http.csrf(csrf -> csrf.disable())
                    .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        } else {
            http.csrf(csrf -> csrf
                    .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                    .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                    .httpBasic(Customizer.withDefaults())
                    .authorizeHttpRequests(auth -> auth
                    .requestMatchers(HttpMethod.GET,
                    "/api/runtime-status", "/api/public/overview", "/api/public/data-version", "/api/public/graph-overview", "/api/dashboard", "/api/job-roles/**", "/api/emerging-job-candidates",
                            "/api/emerging-discovery/queries/**", "/api/public/current-release",
                            "/api/match-runs/**", "/api/resumes/**",
                            "/api/evaluations/status").permitAll()
                    .requestMatchers(HttpMethod.POST,
                            "/api/resumes", "/api/resumes/**", "/api/ability-profiles", "/api/match-runs",
                            "/api/learning-plans", "/api/verification-submissions", "/api/diagnosis-comparisons",
                            "/api/evidence-explanations", "/api/emerging-discovery/queries",
                            "/api/emerging-discovery/queries/*/publications").permitAll()
                    .requestMatchers(HttpMethod.PUT, "/api/resumes/**").permitAll()
                    .requestMatchers(HttpMethod.DELETE, "/api/resumes/**",
                            "/api/emerging-discovery/queries/*/publications").permitAll()
                    .requestMatchers("/api/security/csrf", "/api/security/professional/login",
                            "/api/security/professional/session", "/actuator/health").permitAll()
                    .requestMatchers("/api/security/professional/logout").authenticated()
                    .requestMatchers(HttpMethod.POST, "/api/promotion-batches/*/reviews").hasRole("REVIEWER")
                    .requestMatchers(HttpMethod.POST, "/api/emerging-discovery/candidates/*/reviews").hasRole("REVIEWER")
                    .requestMatchers(HttpMethod.POST,
                            "/api/data-imports/**", "/api/dynamic-records/**", "/api/candidate-skills/**",
                            "/api/promotion-batches", "/api/promotion-batches/*/publish",
                            "/api/emerging-discovery/runs", "/api/emerging-discovery/candidates/*/submissions",
                            "/api/emerging-discovery/candidates/*/publications").hasRole("EDITOR")
                    .requestMatchers(HttpMethod.PUT, "/api/emerging-discovery/candidates/*").hasRole("EDITOR")
                .requestMatchers(HttpMethod.POST, "/api/admin/graph-releases/**").hasRole("EDITOR")
                .requestMatchers(HttpMethod.POST, "/api/admin/verification-submissions/*/reviews").hasRole("PROFESSIONAL")
                .requestMatchers(HttpMethod.GET, "/api/admin/verification-submissions").hasRole("PROFESSIONAL")
                .requestMatchers(HttpMethod.GET, "/api/review-queues/**", "/api/dynamic-records/**",
                            "/api/data-imports/**", "/api/candidate-skills", "/api/promotion-batches/**",
                            "/api/real-job-snapshots/**", "/api/professional/source-ledger",
                            "/api/emerging-discovery/**", "/api/admin/graph-releases/**").hasRole("PROFESSIONAL")
                    .anyRequest().hasRole("EDITOR"));
        }
        http.addFilterBefore(anonymousSessionFilter, AnonymousAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    UserDetailsService professionalUsers(
            @Value("${zhigang.security.professional-username:}") String username,
            @Value("${zhigang.security.professional-password:}") String password,
            @Value("${zhigang.security.reviewer-username:}") String reviewerUsername,
            @Value("${zhigang.security.reviewer-password:}") String reviewerPassword
    ) {
        BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
        List<UserDetails> users = new ArrayList<>();
        if (!username.isBlank() && !password.isBlank()) {
            users.add(professionalUser(username, password, passwordEncoder, "EDITOR"));
        }
        if (!reviewerUsername.isBlank() && !reviewerPassword.isBlank()
                && !reviewerUsername.equals(username)) {
            users.add(professionalUser(reviewerUsername, reviewerPassword, passwordEncoder, "REVIEWER"));
        }
        return new InMemoryUserDetailsManager(users);
    }

    private UserDetails professionalUser(String username, String password, BCryptPasswordEncoder passwordEncoder,
                                         String workflowRole) {
        return User.withUsername(username)
                .password("{bcrypt}" + passwordEncoder.encode(password))
                .roles("PROFESSIONAL", workflowRole)
                .build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of("http://localhost:5173", "http://127.0.0.1:5173"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
