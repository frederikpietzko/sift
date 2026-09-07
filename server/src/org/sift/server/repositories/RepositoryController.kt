package org.sift.server.repositories

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.support.ServletUriComponentsBuilder
import java.util.UUID

@RestController
@RequestMapping("/api/v1/repositories")
class RepositoryController(private val service: RepositoryService) {
    @PostMapping
    fun create(@Valid @RequestBody request: CreateRepositoryRequest): ResponseEntity<RepositoryResponse> {
        val repository = service.create(name = request.name, url = request.url, token = request.token)
        val location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").build(repository.id)
        return ResponseEntity.created(location).body(RepositoryResponse.from(repository))
    }

    @GetMapping
    fun list(): List<RepositoryResponse> = service.list().map(RepositoryResponse::from)

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID): RepositoryResponse = RepositoryResponse.from(service.get(id))

    @PutMapping("/{id}")
    fun update(@PathVariable id: UUID, @Valid @RequestBody request: UpdateRepositoryRequest): RepositoryResponse =
        RepositoryResponse.from(
            service.update(id = id, url = request.url, token = request.token, clearToken = request.clearToken),
        )

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: UUID) {
        service.delete(id)
    }
}
