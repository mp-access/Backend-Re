package ch.uzh.ifi.access.service

import ch.uzh.ifi.access.error.ResourceNotFoundException

class UserNotFoundException(val registrationID: String) :
    ResourceNotFoundException("User with registrationID $registrationID not found")
