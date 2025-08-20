package com.nauta.takehome.infrastructure.security

import com.fasterxml.jackson.databind.ObjectMapper
import io.jsonwebtoken.Claims
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.nio.charset.StandardCharsets
import javax.crypto.SecretKey

@Component
class JwtAuthenticationFilter(
    @Value("\${app.jwt.secret}") private val jwtSecret: String,
    private val tenantContext: TenantContext,
    private val objectMapper: ObjectMapper,
) : OncePerRequestFilter() {
    private val logger = LoggerFactory.getLogger(JwtAuthenticationFilter::class.java)
    private val secretKey: SecretKey = Keys.hmacShaKeyFor(jwtSecret.toByteArray(StandardCharsets.UTF_8))

    companion object {
        private const val BEARER_PREFIX_LENGTH = 7
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        try {
            val authResult = authenticateRequest(request)

            when (authResult) {
                is AuthResult.Success -> {
                    configureAuthentication(authResult.tenantId)
                    filterChain.doFilter(request, response)
                }
                is AuthResult.Failure -> {
                    logger.warn(authResult.message)
                    sendUnauthorized(response, authResult.message)
                }
            }
        } catch (e: JwtException) {
            logger.warn("JWT validation failed: ${e.message}")
            sendUnauthorized(response, "Invalid token")
        } catch (e: IllegalArgumentException) {
            logger.warn("Invalid authentication request: ${e.message}")
            sendUnauthorized(response, "Invalid request")
        } finally {
            tenantContext.clear()
        }
    }

    private fun authenticateRequest(request: HttpServletRequest): AuthResult =
        extractToken(request)?.let { token ->
            validateToken(token)?.let { claims ->
                (claims["tenant_id"] as? String)?.let { tenantId ->
                    AuthResult.Success(tenantId)
                } ?: AuthResult.Failure("Missing tenant_id in token")
            } ?: AuthResult.Failure("Invalid token")
        } ?: AuthResult.Failure("Missing Authorization header")

    private fun extractToken(request: HttpServletRequest): String? {
        val authHeader = request.getHeader("Authorization")
        return if (authHeader != null && authHeader.startsWith("Bearer ")) {
            authHeader.substring(BEARER_PREFIX_LENGTH)
        } else {
            null
        }
    }

    private fun configureAuthentication(tenantId: String) {
        tenantContext.setCurrentTenantId(tenantId)

        val authentication =
            UsernamePasswordAuthenticationToken(
                tenantId,
                null,
                emptyList(),
            )
        SecurityContextHolder.getContext().authentication = authentication

        logger.debug("Authentication successful for tenant: $tenantId")
    }

    private fun validateToken(token: String): Claims? {
        return try {
            Jwts.parserBuilder()
                .setSigningKey(secretKey)
                .build()
                .parseClaimsJws(token)
                .body
        } catch (e: JwtException) {
            logger.debug("JWT validation failed: ${e.message}")
            null
        } catch (e: IllegalArgumentException) {
            logger.debug("Invalid JWT token format: ${e.message}")
            null
        }
    }

    private fun sendUnauthorized(
        response: HttpServletResponse,
        message: String,
    ) {
        response.status = HttpServletResponse.SC_UNAUTHORIZED
        response.contentType = "application/json"

        val errorResponse = mapOf("error" to message)
        response.writer.write(objectMapper.writeValueAsString(errorResponse))
    }

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val path = request.servletPath
        return path.startsWith("/actuator") ||
            path.startsWith("/health") ||
            path.startsWith("/swagger-ui") ||
            path.startsWith("/v3/api-docs") ||
            path == "/swagger-ui.html"
    }

    private sealed class AuthResult {
        data class Success(val tenantId: String) : AuthResult()

        data class Failure(val message: String) : AuthResult()
    }
}
