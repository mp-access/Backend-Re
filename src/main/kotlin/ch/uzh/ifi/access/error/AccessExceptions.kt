package ch.uzh.ifi.access.error

import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

/**
 * Errors the application throws on purpose, with the HTTP status they stand for. The message is shown to the
 * user (the frontend reads `message` from the error response), so it must be understandable and not leak internals.
 *
 * They extend [ResponseStatusException] on purpose, so everything that already understands that type keeps
 * working unchanged: Spring's error response (status + message), the retry policy of the grading queues
 * (4xx = rejected, not retried) and the existing tests. Unexpected errors are handled by [UnexpectedErrorResolver].
 */
abstract class AccessException(status: HttpStatus, message: String?, cause: Throwable? = null) :
    ResponseStatusException(status, message, cause)

/** 400: the request (or the course configuration it refers to) is invalid. */
class BadRequestException(message: String, cause: Throwable? = null) :
    AccessException(HttpStatus.BAD_REQUEST, message, cause)

/** 403: understood, but not allowed (e.g. no attempts left, invalid webhook secret). */
class ForbiddenException(message: String? = null) : AccessException(HttpStatus.FORBIDDEN, message)

/** 404: the course, assignment, task, example or user does not exist. */
open class ResourceNotFoundException(message: String? = null) : AccessException(HttpStatus.NOT_FOUND, message)

/** 409: conflicts with the current state (e.g. another example is already interactive). */
class ConflictException(message: String) : AccessException(HttpStatus.CONFLICT, message)

/** 503: temporarily overloaded (e.g. a full queue); the client may try again shortly. */
class ServiceUnavailableException(message: String) : AccessException(HttpStatus.SERVICE_UNAVAILABLE, message)
