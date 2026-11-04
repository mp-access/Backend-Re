package ch.uzh.ifi.access.service

import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

class UserNotFoundException(val registrationID: String) :
    ResponseStatusException(HttpStatus.NOT_FOUND, "User with registrationID $registrationID not found")
