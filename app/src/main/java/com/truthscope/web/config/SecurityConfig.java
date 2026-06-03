package com.truthscope.web.config;

import com.truthscope.web.security.SupabaseAuthenticationEntryPoint;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/** Spring Security 설정 — Supabase JWT(ES256 JWKS) 리소스 서버 + CORS */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

  /**
   * GET /api/v1/analysis-sessions 만 authenticated, 나머지는 permitAll(POST 익명 분석 보존). 무효 토큰 401 →
   * SupabaseAuthenticationEntryPoint.
   */
  @Bean
  public SecurityFilterChain securityFilterChain(
      HttpSecurity http, SupabaseAuthenticationEntryPoint entryPoint) throws Exception {
    http.cors(cors -> cors.configurationSource(corsConfigurationSource()))
        .csrf(AbstractHttpConfigurer::disable)
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(HttpMethod.GET, "/api/v1/analysis-sessions")
                    .authenticated()
                    .anyRequest()
                    .permitAll())
        .oauth2ResourceServer(
            oauth2 ->
                oauth2
                    .authenticationEntryPoint(entryPoint) // 무효 토큰 401 JSON
                    .jwt(Customizer.withDefaults()))
        .exceptionHandling(ex -> ex.authenticationEntryPoint(entryPoint)); // 토큰 부재 401 JSON
    return http.build();
  }

  /**
   * Supabase JWT 검증기. withJwkSetUri 기본은 RS256-only라 ES256 명시 필수. issuer + aud=authenticated 검증 포함.
   */
  @Bean
  public JwtDecoder jwtDecoder(
      @Value("${truthscope.supabase.jwk-set-uri}") String jwkSetUri,
      @Value("${truthscope.supabase.issuer}") String issuer) {
    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withJwkSetUri(jwkSetUri)
            .jwsAlgorithm(SignatureAlgorithm.ES256) // Supabase GoTrue = EC P-256
            .build();
    OAuth2TokenValidator<Jwt> withIssuer = JwtValidators.createDefaultWithIssuer(issuer);
    OAuth2TokenValidator<Jwt> audience =
        jwt ->
            (jwt.getAudience() != null && jwt.getAudience().contains("authenticated"))
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "aud 불일치", null));
    decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(withIssuer, audience));
    return decoder;
  }

  /**
   * CORS — canonical Vercel 도메인과 로컬 개발 origin만 허용. POST 분석은 Edge 프록시 서버사이드 중계이므로 GET/OPTIONS 유지로
   * 충분(Precondition 5).
   */
  @Bean
  public CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration config = new CorsConfiguration();
    config.setAllowedOrigins(
        List.of("https://truthscope-web-frontend.vercel.app", "http://localhost:3000"));
    config.setAllowedMethods(List.of("GET", "OPTIONS"));
    config.setAllowedHeaders(List.of("*"));
    config.setMaxAge(3600L);
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", config);
    return source;
  }
}
