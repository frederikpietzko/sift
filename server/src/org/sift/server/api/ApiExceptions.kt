package org.sift.server.api

class NotFoundException(message: String) : NoSuchElementException(message)

class ConflictException(message: String) : IllegalStateException(message)
