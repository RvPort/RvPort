package cn.rvport.backend.security;

import cn.rvport.backend.user.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

@Configuration
@ConditionalOnWebApplication
@EnableRedisHttpSession(
        redisNamespace = "${spring.session.redis.namespace:rvport:sessions}",
        maxInactiveIntervalInSeconds = 7200
)
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            UserRepository userRepository,
            SecurityErrorWriter securityErrorWriter
    ) throws Exception {
        CsrfTokenRequestAttributeHandler csrfRequestHandler = new CsrfTokenRequestAttributeHandler();
        csrfRequestHandler.setCsrfRequestAttributeName(null);

        return http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(csrfRequestHandler))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/health", "/api/health", "/actuator/health", "/actuator/health/**",
                                "/api/auth/csrf", "/api/auth/login",
                                "/api/auth/invitations/accept", "/api/auth/password-resets/request",
                                "/api/auth/password-resets/confirm").permitAll()
                        .requestMatchers("/api/admin/users", "/api/admin/users/**",
                                "/api/admin/configs", "/api/admin/configs/**",
                                "/api/admin/workers", "/api/admin/workers/**").hasAuthority("SUPER_ADMIN")
                        .anyRequest().authenticated())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, authException) -> securityErrorWriter.write(
                                request,
                                response,
                                HttpStatus.UNAUTHORIZED,
                                "AUTH_REQUIRED",
                                "需要登录"
                        ))
                        .accessDeniedHandler(accessDeniedHandler(securityErrorWriter)))
                .logout(logout -> logout.disable())
                .addFilterAfter(new CurrentUserStatusFilter(userRepository, securityErrorWriter), SecurityContextHolderFilter.class)
                .addFilterAfter(new PasswordChangeRequiredFilter(securityErrorWriter), CurrentUserStatusFilter.class)
                .build();
    }

    private AccessDeniedHandler accessDeniedHandler(SecurityErrorWriter securityErrorWriter) {
        return (request, response, accessDeniedException) -> securityErrorWriter.write(
                request,
                response,
                HttpStatus.FORBIDDEN,
                "FORBIDDEN",
                "没有权限执行该操作"
        );
    }

    @Bean
    CookieSerializer cookieSerializer() {
        DefaultCookieSerializer serializer = new DefaultCookieSerializer();
        serializer.setCookieName("RVPORT_SESSION");
        serializer.setSameSite("Lax");
        serializer.setUseHttpOnlyCookie(true);
        serializer.setUseSecureCookie(false);
        serializer.setCookiePath("/");
        return serializer;
    }
}
