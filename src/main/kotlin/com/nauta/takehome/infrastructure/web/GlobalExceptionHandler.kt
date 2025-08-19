package com.nauta.takehome.infrastructure.web

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

@ControllerAdvice
class GlobalExceptionHandler {
    private val logger = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidationExceptions(ex: MethodArgumentNotValidException): ResponseEntity<Map<String, Any>> {
        val errors = mutableMapOf<String, String>()

        ex.bindingResult.fieldErrors.forEach { error ->
            errors[error.field] = error.defaultMessage ?: "Invalid value"
        }

        logger.warn("Validation failed: {}", errors)

        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
            .body(
                mapOf(
                    "error" to "Validation failed",
                    "details" to errors,
                    "status" to 422,
                ),
            )
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleTypeMismatchException(ex: MethodArgumentTypeMismatchException): ResponseEntity<Map<String, Any>> {
        logger.warn("Type mismatch for parameter '{}': {}", ex.name, ex.message)

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(
                mapOf(
                    "error" to "Invalid parameter type",
                    "parameter" to ex.name,
                    "message" to "Expected ${ex.requiredType?.simpleName} but received '${ex.value}'",
                    "status" to 400,
                ),
            )
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgumentException(ex: IllegalArgumentException): ResponseEntity<Map<String, Any>> {
        logger.warn("Illegal argument: {}", ex.message)

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(
                mapOf(
                    "error" to "Invalid argument",
                    "message" to (ex.message ?: "Invalid request"),
                    "status" to 400,
                ),
            )
    }

    @ExceptionHandler(Exception::class)
    fun handleGenericException(ex: Exception): ResponseEntity<Map<String, Any>> {
        logger.error("Unexpected error", ex)

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(
                mapOf(
                    "error" to "Internal server error",
                    "message" to "An unexpected error occurred",
                    "status" to 500,
                ),
            )
    }
}
