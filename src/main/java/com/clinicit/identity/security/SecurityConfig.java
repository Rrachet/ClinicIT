package com.clinicit.identity.security;

import com.clinicit.identity.application.AuthProperties;
import com.clinicit.identity.application.BootstrapAdmin;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Two layers, see docs/SECURITY.md:
 * <ol>
 *   <li>URL level (here): everything except login and health needs a valid bearer token.</li>
 *   <li>Method level: every endpoint declares its roles ({@link AdminOnly}, {@link FrontDesk},
 *       {@link AnyStaff}); services then scope every query to the caller's clinic.</li>
 * </ol>
 */
@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties({AuthProperties.class, BootstrapAdmin.Properties.class})
public class SecurityConfig implements WebMvcConfigurer {

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, OpaqueTokenIntrospector introspector, ObjectMapper json)
            throws Exception {
        ApiErrorSecurityHandlers errors = new ApiErrorSecurityHandlers(json);

        http
                // Stateless bearer tokens in a header, no cookies: CSRF does not apply.
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/health").permitAll()
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .opaqueToken(opaque -> opaque.introspector(introspector))
                        .authenticationEntryPoint(errors)
                        .accessDeniedHandler(errors))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(errors)
                        .accessDeniedHandler(errors));

        return http.build();
    }

    /** bcrypt today, stored with an {id} prefix so the algorithm can be upgraded later. */
    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new ActorArgumentResolver());
    }
}
