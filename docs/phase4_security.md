# Phase 4 — Security (Tasks 22–25)
**Estimated Time:** 4 hours | **Status:** ⬜ Not Started

---

## Task 22: Spring Security + JWT Authentication (1.5hr)

### What You Learn
- Spring Security filter chain and how it works
- Stateless authentication with JWT
- JWT structure: Header.Payload.Signature
- JwtAuthenticationFilter — extract and validate token
- JwtService — generate and validate JWTs
- UserDetailsService — load user from DB
- SecurityConfig — configure protected endpoints
- AuthController — login/register endpoints

### JWT Structure
```
eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJqb2huQGV4YW1wbGUuY29tIiwicm9sZXMiOlsiUk9MRV9VU0VSIl0sImlhdCI6MTY5ODI0MDgwMCwiZXhwIjoxNjk4MzI3MjAwfQ.signature
 ↑ Header (alg)         ↑ Payload (claims: sub, roles, iat, exp)                                                                                          ↑ Signature (HMAC-SHA256)
```

### Spring Security Filter Chain
```
HTTP Request
    ↓
JwtAuthenticationFilter (custom)
    → Extract Bearer token from Authorization header
    → Validate JWT signature + expiry
    → Set SecurityContext (UsernamePasswordAuthenticationToken)
    ↓
UsernamePasswordAuthenticationFilter (default, disabled for stateless)
    ↓
Authorization checks (role-based)
    ↓
Controller
```

### Dependencies
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>
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

### JwtService
```java
@Service
@Slf4j
public class JwtService {

    @Value("${application.security.jwt.secret-key}")
    private String secretKey;

    @Value("${application.security.jwt.expiration}")
    private long jwtExpiration;  // 86400000 = 24 hours in ms

    public String generateToken(UserDetails userDetails) {
        return generateToken(Map.of(), userDetails);
    }

    public String generateToken(Map<String, Object> extraClaims, UserDetails userDetails) {
        return Jwts.builder()
                .claims(extraClaims)
                .subject(userDetails.getUsername())
                .issuedAt(new Date(System.currentTimeMillis()))
                .expiration(new Date(System.currentTimeMillis() + jwtExpiration))
                .signWith(getSigningKey())
                .compact();
    }

    public boolean isTokenValid(String token, UserDetails userDetails) {
        final String username = extractUsername(token);
        return username.equals(userDetails.getUsername()) && !isTokenExpired(token);
    }

    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    private boolean isTokenExpired(String token) {
        return extractClaim(token, Claims::getExpiration).before(new Date());
    }

    private <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        return claimsResolver.apply(
                Jwts.parser()
                        .verifyWith(getSigningKey())
                        .build()
                        .parseSignedClaims(token)
                        .getPayload()
        );
    }

    private SecretKey getSigningKey() {
        byte[] keyBytes = Decoders.BASE64.decode(secretKey);
        return Keys.hmacShaKeyFor(keyBytes);
    }
}
```

### JwtAuthenticationFilter
```java
@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        final String authHeader = request.getHeader("Authorization");

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            final String jwt = authHeader.substring(7);
            final String userEmail = jwtService.extractUsername(jwt);

            if (userEmail != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                UserDetails userDetails = userDetailsService.loadUserByUsername(userEmail);

                if (jwtService.isTokenValid(jwt, userDetails)) {
                    UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                            userDetails, null, userDetails.getAuthorities());
                    authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authToken);
                    MDC.put("userId", userEmail);  // Add to MDC for logging
                }
            }
        } catch (JwtException e) {
            log.warn("Invalid JWT token: {}", e.getMessage());
        }

        filterChain.doFilter(request, response);
    }
}
```

### SecurityConfig
```java
@Configuration
@EnableWebSecurity
@EnableMethodSecurity  // Enables @PreAuthorize
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthFilter;
    private final UserDetailsService userDetailsService;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)  // Disable CSRF for stateless REST
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .sessionManagement(session ->
                    session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers("/api/v1/auth/**").permitAll()        // Login/register
                    .requestMatchers("/actuator/health").permitAll()       // Health check
                    .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()  // Docs
                    .requestMatchers(HttpMethod.GET, "/api/v1/orders/**").hasAnyRole("USER", "ADMIN")
                    .requestMatchers(HttpMethod.POST, "/api/v1/orders/**").hasAnyRole("USER", "ADMIN")
                    .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                    .anyRequest().authenticated()
            )
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .exceptionHandling(ex -> ex
                    .authenticationEntryPoint((request, response, e) -> {
                        response.setStatus(HttpStatus.UNAUTHORIZED.value());
                        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                        response.getWriter().write("{\"error\":\"Unauthorized\",\"status\":401}");
                    })
                    .accessDeniedHandler((request, response, e) -> {
                        response.setStatus(HttpStatus.FORBIDDEN.value());
                        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                        response.getWriter().write("{\"error\":\"Forbidden\",\"status\":403}");
                    })
            );

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config)
            throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("https://yourdomain.com", "http://localhost:3000"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
```

### User Entity
```java
@Entity
@Table(name = "users")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class User implements UserDetails {

    @Id @GeneratedValue(strategy = IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String email;

    @Column(nullable = false)
    private String password;  // BCrypt hashed

    @Enumerated(EnumType.STRING)
    private Role role;

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getUsername() { return email; }
    @Override
    public boolean isAccountNonExpired() { return true; }
    @Override
    public boolean isAccountNonLocked() { return true; }
    @Override
    public boolean isCredentialsNonExpired() { return true; }
    @Override
    public boolean isEnabled() { return true; }
}

public enum Role { USER, ADMIN, MANAGER }
```

### AuthController
```java
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        log.info("User registration: email={}", request.getEmail());
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        log.info("Login attempt: email={}", request.getEmail());
        return ResponseEntity.ok(authService.login(request));
    }
}

// Request DTOs
@Data
public class RegisterRequest {
    @NotBlank String firstName;
    @NotBlank String lastName;
    @Email @NotBlank String email;
    @NotBlank @Size(min = 8) String password;
}

@Data
public class LoginRequest {
    @Email @NotBlank String email;
    @NotBlank String password;
}

// Response
@Data @Builder
public class AuthResponse {
    private String accessToken;
    private String tokenType = "Bearer";
    private Long expiresIn;
    private String email;
    private String role;
}
```

### AuthService
```java
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthServiceImpl implements AuthService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;

    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new UserAlreadyExistsException(request.getEmail());
        }
        User user = User.builder()
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .role(Role.USER)
                .build();
        userRepository.save(user);
        String jwt = jwtService.generateToken(user);
        return AuthResponse.builder().accessToken(jwt).email(user.getEmail()).role("USER").build();
    }

    public AuthResponse login(LoginRequest request) {
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword()));
        } catch (AuthenticationException e) {
            log.warn("Login failed: email={}", request.getEmail());
            throw new InvalidCredentialsException("Invalid email or password");
        }
        User user = userRepository.findByEmail(request.getEmail()).orElseThrow();
        String jwt = jwtService.generateToken(user);
        log.info("Login successful: email={}", user.getEmail());
        return AuthResponse.builder().accessToken(jwt).email(user.getEmail())
                .role(user.getRole().name()).build();
    }
}
```

---

## Task 23: RBAC + Method Security (1hr)

### @PreAuthorize (Method-Level Security)
```java
@Service
public class OrderService {

    @PreAuthorize("hasRole('ADMIN') or #customerEmail == authentication.principal.username")
    public List<OrderResponse> getOrdersByEmail(String customerEmail) {
        // Admin can see any orders, user can only see their own
    }

    @PreAuthorize("hasRole('ADMIN')")
    public void deleteOrder(Long id) {
        // Only admin can delete
    }

    @PostAuthorize("returnObject.customerEmail == authentication.principal.username or hasRole('ADMIN')")
    public OrderResponse getOrderById(Long id) {
        // Can get, but post-check ensures user owns it (or is admin)
    }

    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    public OrderResponse createOrder(OrderRequest request) {
        // Any authenticated user can create
    }
}

// Admin controller
@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")  // Class-level security
public class AdminController {
    // All endpoints require ADMIN role
}
```

### Current User Helper
```java
@Component
public class SecurityUtils {

    public String getCurrentUserEmail() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) throw new UnauthorizedException();
        return auth.getName();
    }

    public boolean isAdmin() {
        return SecurityContextHolder.getContext().getAuthentication()
                .getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
    }

    public User getCurrentUser() {
        return (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
```

---

## Task 24: OAuth2 + OpenID Connect (1hr)

### Spring OAuth2 Resource Server (JWT from Auth0/Keycloak)
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-oauth2-resource-server</artifactId>
</dependency>
```

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: https://YOUR_DOMAIN.auth0.com/
          # Spring auto-discovers JWKS endpoint from issuer-uri
```

```java
// Simpler SecurityConfig with OAuth2 Resource Server
@Bean
public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http
        .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/v1/auth/**").permitAll()
                .anyRequest().authenticated()
        )
        .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthConverter()))
        );
    return http.build();
}

// Extract roles from JWT claims
@Bean
public JwtAuthenticationConverter jwtAuthConverter() {
    JwtGrantedAuthoritiesConverter converter = new JwtGrantedAuthoritiesConverter();
    converter.setAuthoritiesClaimName("roles");  // Custom claim in JWT
    converter.setAuthorityPrefix("ROLE_");

    JwtAuthenticationConverter jwtConverter = new JwtAuthenticationConverter();
    jwtConverter.setJwtGrantedAuthoritiesConverter(converter);
    return jwtConverter;
}
```

### OAuth2 Login (Client — for browser-based flows)
```yaml
spring:
  security:
    oauth2:
      client:
        registration:
          google:
            client-id: ${GOOGLE_CLIENT_ID}
            client-secret: ${GOOGLE_CLIENT_SECRET}
            scope: openid, profile, email
          github:
            client-id: ${GITHUB_CLIENT_ID}
            client-secret: ${GITHUB_CLIENT_SECRET}
```

---

## Task 25: Security Best Practices (0.5hr)

### Password Encoding
```java
// BCrypt — adaptive, safe
PasswordEncoder encoder = new BCryptPasswordEncoder(12);  // strength=12
String hashed = encoder.encode("myPassword");
boolean matches = encoder.matches("myPassword", hashed);  // true

// NEVER: MD5, SHA-1, SHA-256 (no salt, fast = brute-forceable)
// ALWAYS: BCrypt, Argon2, PBKDF2
```

### CORS
```java
// Already in SecurityConfig — see above
// NEVER: setAllowedOriginPatterns("*") with setAllowCredentials(true)
// That combination allows cross-origin requests with cookies from any domain!
```

### CSRF
```java
// Disabled for stateless REST (JWT in Authorization header)
http.csrf(AbstractHttpConfigurer::disable);

// Enable for server-side rendered apps with sessions
http.csrf(Customizer.withDefaults());
```

### Sensitive Data Handling
```java
// Never log passwords or tokens
log.info("User registered: email={}", email);  // ✅
log.info("User registered: email={}, password={}", email, password);  // ❌

// Always hash passwords before storing
user.setPassword(passwordEncoder.encode(request.getPassword()));

// Never expose stack traces in API responses
// GlobalExceptionHandler returns generic message for 500s
```

### application.yml Security Settings
```yaml
application:
  security:
    jwt:
      secret-key: ${JWT_SECRET_KEY}  # From environment variable, NEVER hardcode!
      expiration: 86400000  # 24 hours
      refresh-token:
        expiration: 604800000  # 7 days
```

---

## 🧪 Testing Security

```bash
# Register user
curl -X POST http://localhost:8080/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{"firstName":"John","lastName":"Doe","email":"john@example.com","password":"SecurePass123!"}'
# Response: {"accessToken":"eyJ...","email":"john@example.com","role":"USER"}

# Login
curl -X POST http://localhost:8080/api/v1/auth/login \
  -d '{"email":"john@example.com","password":"SecurePass123!"}'

# Access protected endpoint
TOKEN="eyJ..."
curl http://localhost:8080/api/v1/orders \
  -H "Authorization: Bearer $TOKEN"

# Access admin endpoint as USER → 403 Forbidden
curl http://localhost:8080/api/v1/admin/users \
  -H "Authorization: Bearer $TOKEN"
# {"error":"Forbidden","status":403}

# Access without token → 401 Unauthorized
curl http://localhost:8080/api/v1/orders
# {"error":"Unauthorized","status":401}
```

---

## ✅ Phase 4 Completion Checklist
- [ ] JWT dependency added
- [ ] JwtService: generate + validate tokens
- [ ] JwtAuthenticationFilter: extract Bearer, validate, set SecurityContext
- [ ] SecurityConfig: stateless, CORS, endpoint auth rules
- [ ] User entity implementing UserDetails
- [ ] Role enum (USER, ADMIN, MANAGER)
- [ ] UserRepository with findByEmail
- [ ] AuthService: register + login
- [ ] AuthController: /auth/register, /auth/login
- [ ] PasswordEncoder BCrypt bean
- [ ] AuthenticationManager bean
- [ ] @EnableMethodSecurity active
- [ ] @PreAuthorize on sensitive service methods
- [ ] SecurityUtils: getCurrentUserEmail, isAdmin
- [ ] Register → login → use token → 200 ✅
- [ ] No token → 401 ✅
- [ ] Wrong role → 403 ✅
- [ ] Invalid JWT → 401 ✅

---

## 💬 Key Interview Q&A

**Q: Explain how JWT authentication works in Spring Boot.**
A: Request arrives with `Authorization: Bearer <token>`. JwtAuthenticationFilter (extends OncePerRequestFilter) extracts token, validates signature with secret key, checks expiry, loads UserDetails, and sets SecurityContext. All subsequent security checks use the SecurityContext. Stateless — no session on server.

**Q: Why use BCrypt over SHA-256 for passwords?**
A: SHA-256 is deterministic and fast — GPU can compute billions/second. BCrypt is slow by design (adaptive cost factor) and includes salt — different hash every time for same password. 12 rounds of BCrypt takes ~250ms per hash — brute force impractical. MD5/SHA for data integrity, BCrypt/Argon2 for passwords.

**Q: What is CSRF and why disable it for REST APIs?**
A: Cross-Site Request Forgery — tricks user's browser into making authenticated requests to your server. CSRF protection uses token in forms/cookies. For stateless REST APIs with JWT in Authorization header: browser doesn't auto-send Authorization header cross-origin, so CSRF attack can't include JWT. Safe to disable CSRF for stateless JWT APIs.

**Q: What is the difference between Authentication and Authorization?**
A: Authentication — who are you? (verify identity: username+password → JWT issued). Authorization — what can you do? (check permissions: JWT → roles → allowed or denied). Spring Security: authentication sets SecurityContext, authorization checks authorities at endpoint and method level.

**Q: What is @PreAuthorize and how does it work?**
A: Method-level security annotation. Before method executes, Spring evaluates SpEL expression against SecurityContext. `hasRole('ADMIN')` — checks authority. `#email == authentication.principal.username` — checks parameter against current user. Requires @EnableMethodSecurity. Uses AOP proxy — same-class call issues apply.

**Q: What is OAuth2 and how does it differ from basic JWT auth?**
A: OAuth2 is a standard authorization framework. External identity provider (Google, Auth0, Keycloak) issues JWT tokens. Your service validates token against provider's public key (JWKS endpoint) — no need to store passwords yourself. OpenID Connect adds identity layer (user info claims). Use Spring OAuth2 Resource Server to auto-validate.

---

## 🔗 Next Phase
**Phase 5: Observability** — Actuator health checks, Micrometer metrics, Prometheus, Grafana, Zipkin distributed tracing.
