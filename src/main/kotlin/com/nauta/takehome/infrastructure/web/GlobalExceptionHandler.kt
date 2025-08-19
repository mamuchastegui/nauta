package com.nauta.takehome.infrastructure.web

import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler

@ControllerAdvice
class GlobalExceptionHandler {
    private val logger = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleHttpMessageNotReadable(ex: HttpMessageNotReadableException): ResponseEntity<Map<String, String>> {
        logger.warn("Invalid JSON payload: ${ex.message}")

        val cause = ex.cause
        val errorMessage =
            when (cause) {
                is UnrecognizedPropertyException -> "Unknown field '${cause.propertyName}' in request"
                else -> "Invalid JSON payload"
            }

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(mapOf("error" to errorMessage))
    }
}
