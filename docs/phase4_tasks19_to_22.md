# Phase 4 — Security (Tasks 19–22)
**Estimated Time:** 4 hours | **Status:** ⬜ Not Started

---

## Task 19: Spring Security + JWT Authentication

### JWT Flow
```
1. POST /api/v1/auth/login  {email, password}
2. Server validates credentials → generates JWT (signed with secret key)
3. Client stores token, sends with every request:
   Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
4. JwtAuthFilter validates token → extracts user → sets SecurityContext
5. Controller: @AuthenticationPrincipal UserDetails user
```

### JWT Structure
```
Header.Payload.Signature

Header:  {"alg":"HS256","typ":"JWT"}
Payload: {"sub":"john@example.com","iat":1729065000,"exp":1729068600,"roles":["USER"]}
Signature: HMAC-SHA256(header + "." + payload, secretKey)
```

### Dependencies
```xml
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-api</artifactId>
    <version>0.12.3</version>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-impl</artifactId>
    <version>0.12.3</version>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-jackson</artifactId>
    <version>0.12.3</version>
    <scope>runtime</scope>
</dependency>
```

### JWT Service
```java
@Service
public class JwtService {
    @Value("${security.jwt.secret-key}")
    private String secretKey;

    @Value("${security.jwt.expiration-ms:3600000}") // 1 hour
    private long expirationMs;

    public String generateToken(UserDetails userDetails) {
        return Jwts.builder()
                .subject(userDetails.getUsername())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expirationMs))
                .claim("roles", userDetails.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority).collect(Collectors.toList()))
                .signWith(getSigningKey())
                .compact();
    }

    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public boolean isTokenValid(String token, UserDetails userDetails) {
        String username = extractUsername(token);
        return username.equals(userDetails.getUsername()) && !isTokenExpired(token);
    }

    private boolean isTokenExpired(String token) {
        return extractClaim(token, Claims::getExpiration).before(new Date());
    }

    private <T> T extractClaim(String token, Function<Claims, T> resolver) {
        Claims claims = Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return resolver.apply(claims);
    }

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(Decoders.BASE64.decode(secretKey));
    }
}
```

### JWT Filter (OncePerRequestFilter)
```java
@Component @RequiredArgsConstructor @Slf4j
public class JwtAuthFilter extends OncePerRequestFilter {
    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res,
                                     FilterChain chain) throws IOException, ServletException {
        String authHeader = req.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            chain.doFilter(req, res); return;
        }
        String token = authHeader.substring(7);
        try {
            String username = jwtService.extractUsername(token);
            if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                UserDetails user = userDetailsService.loadUserByUsername(username);
                if (jwtService.isTokenValid(token, user)) {
                    UsernamePasswordAuthenticationToken auth =
                        new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
                    auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(req));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
            }
        } catch (JwtException e) {
            log.warn("Invalid JWT token: {}", e.getMessage());
            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        chain.doFilter(req, res);
    }
}
```

### Security Configuration
```java
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {
    private final JwtAuthFilter jwtAuthFilter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/v1/auth/**").permitAll()
                .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/orders/**").hasAnyRole("USER", "ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/v1/orders").hasAnyRole("USER", "ADMIN")
                .requestMatchers("/api/v1/kafka/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config)
            throws Exception { return config.getAuthenticationManager(); }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("http://localhost:*", "https://*.yourdomain.com"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
```

### Auth Controller
```java
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {
    private final AuthenticationManager authManager;
    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@RequestBody @Valid LoginRequest req) {
        authManager.authenticate(
            new UsernamePasswordAuthenticationToken(req.getEmail(), req.getPassword()));
        UserDetails user = userDetailsService.loadUserByUsername(req.getEmail());
        String token = jwtService.generateToken(user);
        return ResponseEntity.ok(new AuthResponse(token, "Bearer", jwtService.getExpirationMs()));
    }
}
```

---

## Task 20: Role-Based Access Control (RBAC)

### Method-Level Security
```java
@PreAuthorize("hasRole('ADMIN')")
public void deleteOrder(Long id) { ... }

@PreAuthorize("hasAnyRole('USER', 'ADMIN')")
public OrderResponse createOrder(OrderRequest req) { ... }

@PreAuthorize("hasRole('ADMIN') or #email == authentication.name")
public List<OrderResponse> getOrdersByEmail(String email) { ... }

@PostAuthorize("returnObject.customerEmail == authentication.name or hasRole('ADMIN')")
public OrderResponse getOrderById(Long id) { ... }
```

### User Entity + Roles
```java
@Entity @Table(name = "users")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class User {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(unique = true, nullable = false) private String email;
    @Column(nullable = false) private String password;  // BCrypt hashed
    @Enumerated(EnumType.STRING) private Role role;
    private boolean enabled;
}

public enum Role {
    USER, ADMIN, MANAGER
}

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
}

@Service @RequiredArgsConstructor
public class UserDetailsServiceImpl implements UserDetailsService {
    private final UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + email));
        return org.springframework.security.core.userdetails.User.builder()
                .username(user.getEmail())
                .password(user.getPassword())
                .roles(user.getRole().name())
                .accountExpired(false)
                .disabled(!user.isEnabled())
                .build();
    }
}
```

---

## Task 21: OAuth2 & OpenID Connect

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-oauth2-client</artifactId>
</dependency>
```

```yaml
spring:
  security:
    oauth2:
      client:
        registration:
          google:
            client-id: ${GOOGLE_CLIENT_ID}
            client-secret: ${GOOGLE_CLIENT_SECRET}
            scope: openid,profile,email
          github:
            client-id: ${GITHUB_CLIENT_ID}
            client-secret: ${GITHUB_CLIENT_SECRET}
            scope: user:email
```

```java
.oauth2Login(oauth2 -> oauth2
    .loginPage("/login")
    .successHandler(oAuth2AuthenticationSuccessHandler)
    .failureHandler(oAuth2AuthenticationFailureHandler))
```

---

## Task 22: Security Best Practices

### CORS Configuration
```java
// See SecurityConfig above — CorsConfigurationSource bean
// Allow specific origins in production — never "*" with credentials
```

### Password Policy
```java
@Bean
public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder(12); // Strength 12 (default 10)
}
// BCrypt auto-generates salt, timing-attack safe
```

### Security Headers
```java
http.headers(headers -> headers
    .frameOptions(HeadersConfigurer.FrameOptionsConfig::deny)  // X-Frame-Options: DENY
    .contentTypeOptions(HeadersConfigurer.ContentTypeOptionsConfig::disable)
    .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31536000))
    .xssProtection(xss -> xss.headerValue(XXssProtectionHeaderWriter.HeaderValue.ENABLED_MODE_BLOCK)));
```

### application.yml Security Config
```yaml
security:
  jwt:
    secret-key: ${JWT_SECRET}     # 256-bit base64 secret — from environment!
    expiration-ms: 3600000        # 1 hour access token
    refresh-expiration-ms: 604800000  # 7 days refresh token

# NEVER hardcode secrets in application.yml
```

---

## Interview Q&A

**Q: JWT vs Session authentication?**
JWT: stateless — server generates signed token, client stores + sends with each request, server validates signature only (no DB lookup). Session: stateful — server stores session data, client sends session ID, server looks up session (requires shared store in clusters).

**Q: Where should JWT be stored in browser?**
HttpOnly cookie (not accessible from JavaScript → XSS-safe). SameSite=Strict (CSRF-safe). Never localStorage (accessible from JS → XSS vulnerable). Never sessionStorage. If using Authorization header, token must be in-memory (lost on tab close).

**Q: How do you invalidate a JWT before expiry?**
JWT is stateless — no built-in invalidation. Options: (1) Short TTL + refresh tokens, (2) Token blacklist in Redis (defeats statelessness), (3) Change JWT secret (logs out everyone), (4) Increment user version in DB — validate version in token.

**Q: What is @PreAuthorize and @PostAuthorize?**
@PreAuthorize: checks permission BEFORE method executes. @PostAuthorize: checks permission AFTER method returns (access to return value). Example: @PostAuthorize("returnObject.owner == authentication.name") — ensures user can only see their own resources.

**Q: CSRF — when do you need it?**
CSRF attacks exploit cookie-based sessions. If using JWT in Authorization header (not cookie) → CSRF not applicable. If using cookies → enable CSRF protection with SameSite attribute. Spring Security disables CSRF for stateless JWT APIs: `http.csrf(AbstractHttpConfigurer::disable)`.

**Q: What does SessionCreationPolicy.STATELESS do?**
Tells Spring Security to never create or use HTTP sessions. Each request must be authenticated independently (via JWT). Required for truly stateless REST APIs. Without this, Spring might create sessions even with JWT, wasting memory.

**Q: BCrypt vs other password hashes?**
BCrypt: slow by design (adjustable cost factor), salted (random per password), timing-attack safe. Never: MD5 (fast, cracked), SHA-256 alone (fast, no salt), plaintext. BCrypt strength 10-12 balances security and UX.
