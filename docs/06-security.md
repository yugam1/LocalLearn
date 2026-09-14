# Security — JWT, Spring Security, RBAC, OAuth2
**⬜ Not Started · plan: Phase 4, Tasks 19–22** — Filter chain, JWT flow, method security, CORS/CSRF, best practices

---

## ⚡ CORE CARD — 60 seconds

**Mental model:** Spring Security is a **chain of servlet filters standing in
front of your controllers**; authentication is "who are you" resolved into a
`SecurityContext`, authorization is "may you" checked against it. JWT makes the
whole thing **stateless**: the server's only memory is its signing key —
identity travels in the token, verified by signature on every request, no
session store anywhere.

```
request ──▶ [JwtAuthFilter: Bearer token → verify signature → load user
             → SecurityContextHolder.set(auth)] ──▶ [authorize rules] ──▶ controller
login:   POST /auth/login {email,pw} → AuthenticationManager verifies (BCrypt)
         → jwtService.generateToken() → client sends "Authorization: Bearer ..." forever after
```

**Five rules you must never get wrong:**
1. JWT = `header.payload.signature` — the signature proves *integrity*, not secrecy: **the payload is readable base64**; never put secrets in claims.
2. Stateless API = `SessionCreationPolicy.STATELESS` + CSRF disabled — CSRF only matters when the browser auto-attaches credentials (cookies).
3. Passwords: **BCrypt** (slow on purpose, salted per password); never MD5/plain SHA.
4. Secrets come from the environment (`${JWT_SECRET}`), never from application.yml in git.
5. JWT can't be un-issued — revocation needs short TTL + refresh tokens (or a Redis blacklist, sacrificing statelessness).

---

## 🔮 PREDICT FIRST

<details>
<summary><b>P1.</b> A user is fired at 2 pm. Their JWT expires at 3 pm. The admin disables their account in the DB at 2:01. Can they still call <code>GET /api/v1/orders</code> at 2:30 — in the implementation below, and in a "pure stateless" one?</summary>

In this implementation: **no** — `JwtAuthFilter` calls
`userDetailsService.loadUserByUsername()` on every request, and the disabled
flag fails `isTokenValid`/account checks. But notice what that cost: a DB
lookup per request — you've quietly given up half of stateless. A pure
stateless validator (signature + expiry only) would keep honoring the token
until 3 pm. That tension — statelessness vs revocation — IS the JWT interview
question.
</details>

<details>
<summary><b>P2.</b> Frontend team stores the JWT in <code>localStorage</code> "so it survives refresh." A dependency ships an XSS payload. What happens, and what storage would have survived?</summary>

Any injected script can read localStorage → token exfiltrated → attacker IS the
user until expiry. HttpOnly cookies are invisible to JavaScript, surviving XSS
(pair with `SameSite=Strict`/CSRF defenses, since cookies resurrect CSRF).
Rule: localStorage = XSS-vulnerable; HttpOnly cookie = XSS-safe but
CSRF-relevant; in-memory = safest, lost on refresh. There's no free option —
know the trade you're making.
</details>

<details>
<summary><b>P3.</b> <code>@PreAuthorize("hasRole('ADMIN')")</code> on a service method — a controller in the same service autowires the service and calls it: blocked. The service calls the same method on <code>this</code> from another of its own methods: blocked?</summary>

**Not blocked.** Method security is enforced by — say it with me — **a proxy**.
`this.method()` bypasses it, exactly like @Transactional and @Async
([task 5](01-foundations/05-transactions-isolation-locking.md), [task 8](03-async-and-scheduling/01-thread-pools-completablefuture.md)). Security checks
silently skipped on self-invocation is a genuinely dangerous variant of the
proxy trap.
</details>

---

## 📖 THE STORY

### 1. The JWT flow, end to end

```
1. POST /api/v1/auth/login {email, password}
2. AuthenticationManager → UserDetailsService → BCrypt match
3. jwtService.generateToken(user)  — subject, iat, exp, roles claim, HMAC-SHA256 signature
4. Client sends:  Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...
5. JwtAuthFilter (OncePerRequestFilter): extract → verify signature + expiry
   → load UserDetails → UsernamePasswordAuthenticationToken → SecurityContextHolder
6. Controller can inject @AuthenticationPrincipal UserDetails
```

Token anatomy: `Header{alg,typ}.Payload{sub,iat,exp,roles}.Signature` —
signature = HMAC(header + "." + payload, secretKey). Tamper with a claim and
the signature no longer matches; **read** a claim and nothing stops you
(base64 ≠ encryption).

### 2. The filter chain — declarative perimeter

```java
http.csrf(AbstractHttpConfigurer::disable)                    // stateless API
    .cors(c -> c.configurationSource(corsConfigurationSource()))
    .authorizeHttpRequests(auth -> auth
        .requestMatchers("/api/v1/auth/**").permitAll()
        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
        .requestMatchers(HttpMethod.GET, "/api/v1/orders/**").hasAnyRole("USER","ADMIN")
        .requestMatchers("/api/v1/kafka/admin/**").hasRole("ADMIN")
        .anyRequest().authenticated())
    .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
    .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
```

Rules read top-down, first match wins — order from most specific to
`anyRequest()`. STATELESS stops Spring from creating sessions behind your back.

### 3. RBAC — URL rules for the perimeter, method rules for the fine grain

`@EnableMethodSecurity` + SpEL:

```java
@PreAuthorize("hasRole('ADMIN')")                                    // before the call
@PreAuthorize("hasRole('ADMIN') or #email == authentication.name")   // args in scope
@PostAuthorize("returnObject.customerEmail == authentication.name")  // inspect the result
```

`@PostAuthorize` is the ownership check: "you may fetch order 42 only if it's
yours" can only be decided *after* loading it. Users persist as
`User{email, bcrypt password, @Enumerated(STRING) Role, enabled}`;
`UserDetailsServiceImpl` adapts that entity to Spring's `UserDetails`. Note
`ROLE_` convention: `hasRole('ADMIN')` matches authority `ROLE_ADMIN`.

### 4. OAuth2/OIDC — outsourcing authentication

`spring-boot-starter-oauth2-client` + registrations (google/github with
client-id/secret from env, scopes `openid,profile,email`) +
`.oauth2Login(...)` with success/failure handlers. Flow: redirect to provider →
user consents → provider redirects back with code → Spring exchanges code for
tokens → your success handler typically mints **your own JWT** so the rest of
the API stays uniform. OIDC = OAuth2 + a standardized identity layer (the
`id_token`).

### 5. The best-practices checklist

- **CORS**: explicit origin patterns (`http://localhost:*`,
  `https://*.yourdomain.com`) — never `*` together with credentials. CORS is
  a *browser* protection: it does nothing against curl.
- **CSRF**: attacks ride on auto-attached cookies. Bearer-header JWT → disable;
  cookie-stored JWT → you need CSRF defenses back (SameSite, tokens).
- **BCrypt strength 10–12** — the cost factor is the point: brute force at
  ~100ms/guess doesn't scale.
- **Headers**: `X-Frame-Options: DENY` (clickjacking), HSTS with
  includeSubDomains (force HTTPS), content-type options.
- **Config**: JWT secret = 256-bit base64 from env; access token 1h; refresh
  token 7d.

---

## 🛠 BUILD REFERENCE

<details>
<summary><b>Dependencies + JwtService</b></summary>

`jjwt-api` / `jjwt-impl` / `jjwt-jackson` 0.12.x (impl/jackson runtime scope).

```java
@Service
public class JwtService {
    @Value("${security.jwt.secret-key}") private String secretKey;
    @Value("${security.jwt.expiration-ms:3600000}") private long expirationMs;

    public String generateToken(UserDetails user) {
        return Jwts.builder()
            .subject(user.getUsername())
            .issuedAt(new Date())
            .expiration(new Date(System.currentTimeMillis() + expirationMs))
            .claim("roles", user.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority).toList())
            .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(secretKey)))
            .compact();
    }
    public String extractUsername(String token) { return extractClaim(token, Claims::getSubject); }
    public boolean isTokenValid(String token, UserDetails user) {
        return extractUsername(token).equals(user.getUsername()) && !isExpired(token);
    }
    private <T> T extractClaim(String token, Function<Claims, T> resolver) {
        return resolver.apply(Jwts.parser().verifyWith(getSigningKey()).build()
                                  .parseSignedClaims(token).getPayload());
    }
}
```
</details>

<details>
<summary><b>JwtAuthFilter (OncePerRequestFilter)</b></summary>

```java
@Component @RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {
    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res,
                                    FilterChain chain) throws IOException, ServletException {
        String authHeader = req.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            chain.doFilter(req, res); return;             // anonymous — let rules decide
        }
        try {
            String token = authHeader.substring(7);
            String username = jwtService.extractUsername(token);
            if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                UserDetails user = userDetailsService.loadUserByUsername(username);
                if (jwtService.isTokenValid(token, user)) {
                    var auth = new UsernamePasswordAuthenticationToken(
                            user, null, user.getAuthorities());
                    auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(req));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
            }
        } catch (JwtException e) {
            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED); return;
        }
        chain.doFilter(req, res);
    }
}
```
</details>

<details>
<summary><b>SecurityConfig extras + AuthController + User entity</b></summary>

Beans: `BCryptPasswordEncoder(12)`, `AuthenticationManager` from
`AuthenticationConfiguration`, `CorsConfigurationSource` with origin patterns +
allowed methods + `allowCredentials(true)`.

```java
@PostMapping("/login")
public ResponseEntity<AuthResponse> login(@RequestBody @Valid LoginRequest req) {
    authManager.authenticate(
        new UsernamePasswordAuthenticationToken(req.getEmail(), req.getPassword()));
    UserDetails user = userDetailsService.loadUserByUsername(req.getEmail());
    return ResponseEntity.ok(new AuthResponse(jwtService.generateToken(user),
                                              "Bearer", jwtService.getExpirationMs()));
}
```

`User` entity: unique email, BCrypt-hashed password, `@Enumerated(STRING) Role
{USER, ADMIN, MANAGER}`, enabled flag; `UserDetailsServiceImpl.loadUserByUsername`
maps it to Spring's builder (`roles(...)`, `disabled(!enabled)`).

Security headers: `frameOptions.deny()`, HSTS (1y, subdomains), XSS block mode.
</details>

---

## 🎯 RETRIEVAL GYM

**Tier 1 — must be automatic**

<details><summary><b>Q1.</b> JWT vs session auth — mechanics and the cluster consequence.</summary>

Session: server stores state, client holds an opaque id, every request = store
lookup — clusters need shared session storage (Redis/sticky sessions). JWT:
identity + claims inside a signed token; server verifies the signature — no
lookup, no shared state, any instance can serve any request. Price: revocation
becomes hard.
</details>

<details><summary><b>Q2.</b> How do you log out / revoke a JWT before expiry? (Four options + costs.)</summary>

(1) Short TTL + refresh tokens — revoke the refresh token; window = access TTL.
(2) Redis blacklist checked per request — works, re-introduces state.
(3) Rotate the signing secret — nukes every session. (4) Per-user token-version
claim checked against DB — flexible, adds a lookup. There is no free stateless
revocation; that's the trade.
</details>

<details><summary><b>Q3.</b> Where should the browser store a JWT and why?</summary>

HttpOnly + SameSite cookie: JavaScript can't read it (XSS-safe), SameSite
blunts CSRF. localStorage/sessionStorage: readable by any injected script —
XSS = full account takeover. In-memory: safest, dies on refresh. Never answer
just "localStorage is fine."
</details>

<details><summary><b>Q4.</b> When is disabling CSRF correct, and why exactly?</summary>

When authentication is a Bearer header on a stateless API: CSRF works by the
browser auto-attaching credentials (cookies) to forged cross-site requests —
an Authorization header is never auto-attached, so the attack has nothing to
ride. Cookie-based auth (including JWT-in-cookie) needs CSRF protection back.
</details>

<details><summary><b>Q5.</b> @PreAuthorize vs @PostAuthorize — and the ownership-check pattern.</summary>

Pre: evaluated before the method, args available (`#email ==
authentication.name`). Post: after, with `returnObject` — required when the
decision needs the loaded data: "only the owner or an admin may see this
order." Both run through the security proxy (self-invocation bypasses!).
</details>

**Tier 2 — depth**

<details><summary><b>Q6.</b> Why BCrypt specifically — what do MD5/SHA-256 lack?</summary>

BCrypt is deliberately slow (tunable cost factor — hardware improves, you bump
it), salts every hash (no rainbow tables, identical passwords hash
differently), and compares in constant time. MD5/SHA are designed to be FAST —
exactly wrong for passwords (GPUs try billions/sec).
</details>

<details><summary><b>Q7.</b> What does SessionCreationPolicy.STATELESS actually change?</summary>

Spring Security never creates or reads an HttpSession: no JSESSIONID, no
session-based SecurityContext persistence — every request must authenticate
itself (the JWT filter). Without it, sessions can silently appear alongside
your "stateless" API, consuming memory and creating sticky behavior.
</details>

<details><summary><b>Q8.</b> Anonymous request hits the filter — why pass it down the chain instead of rejecting?</summary>

The filter's job is authentication, not authorization. With no token it
continues the chain unauthenticated; the authorizeHttpRequests rules then
decide — permitAll paths (login, health) must still work. Rejecting in the
filter would break every public endpoint.
</details>

<details><summary><b>Q9.</b> Signature vs encryption in JWT — what can an attacker with a captured token do/see?</summary>

See everything: payload is base64 — email, roles, expiry are readable. Modify
nothing: any change breaks the HMAC. And replay it fully until expiry (hence
HTTPS everywhere + short TTLs). If claims must be secret, that's JWE
(encrypted JWT) or don't put them there.
</details>

---

## 🃏 FLASHCARDS

```
JWT anatomy	header.payload.signature — signed (integrity), NOT encrypted (readable)
JWT validation per request	Signature + expiry (+ optionally user lookup — costs statelessness)
Stateless pair of settings	SessionCreationPolicy.STATELESS + csrf disabled
CSRF applies when	Credentials auto-attach (cookies) — not Bearer headers
Browser token storage ranking	HttpOnly+SameSite cookie > in-memory > localStorage (XSS)
Revocation options	Short TTL+refresh · Redis blacklist · secret rotation · token-version claim
Password hash	BCrypt cost 10–12 — slow by design, per-password salt
hasRole('ADMIN') matches	Authority ROLE_ADMIN (prefix convention)
Ownership check annotation	@PostAuthorize("returnObject.x == authentication.name")
Method security machinery	Proxy — self-invocation SKIPS the check
CORS with credentials	Explicit origins only, never "*"; browser-only protection
```

---

## 🔗 SAME IDEA, DIFFERENT LAYER

| This doc | Same idea as | Where |
|---|---|---|
| method-security proxy trap | @Transactional/@Async self-invocation | `01-foundations/05`, `03-async-and-scheduling/01` |
| JwtAuthFilter ordering | MDCFilter in the same chain | `01-foundations/07` |
| 401 vs 403 in the error contract | exception→status mapping | `01-foundations/02` |
| gateway-level auth | Spring Cloud Gateway filters | `10-microservices` |

---

## 🗓 REVISION LOG

- [ ] **R1** — next day
- [ ] **R2** — +3 days
- [ ] **R3** — +1 week
- [ ] **R4** — +3 weeks (interleaved)
