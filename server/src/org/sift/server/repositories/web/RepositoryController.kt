package org.sift.server.repositories.web

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.sift.server.repositories.RepositoryService
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

@Tag(name = "repositories", description = "Git repositories reviews are started for; tokens never leave the server")
@RestController
@RequestMapping("/api/v1/repositories")
class RepositoryController(private val service: RepositoryService) {
    @Operation(summary = "Register a repository")
    @ApiResponse(responseCode = "201", description = "Repository created; `Location` points at it")
    @ApiResponse(responseCode = "400", description = "Validation failed")
    @ApiResponse(responseCode = "409", description = "A repository with the same name exists")
    @PostMapping
    fun create(@Valid @RequestBody request: CreateRepositoryRequest): ResponseEntity<RepositoryResponse> {
        val repository = service.create(name = request.name, url = request.url, token = request.token)
        val location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").build(repository.id)
        return ResponseEntity.created(location).body(RepositoryResponse.from(repository))
    }

    @Operation(summary = "List repositories")
    @GetMapping
    fun list(): List<RepositoryResponse> = service.list().map(RepositoryResponse::from)

    @Operation(summary = "Get a repository")
    @ApiResponse(responseCode = "200", description = "The repository")
    @ApiResponse(responseCode = "404", description = "Repository not found")
    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID): RepositoryResponse = RepositoryResponse.from(service.get(id))

    @Operation(summary = "Update a repository", description = "Replaces the URL and/or rotates or clears the token.")
    @ApiResponse(responseCode = "200", description = "The updated repository")
    @ApiResponse(responseCode = "400", description = "Validation failed")
    @ApiResponse(responseCode = "404", description = "Repository not found")
    @PutMapping("/{id}")
    fun update(@PathVariable id: UUID, @Valid @RequestBody request: UpdateRepositoryRequest): RepositoryResponse =
        RepositoryResponse.from(
            service.update(id = id, url = request.url, token = request.token, clearToken = request.clearToken),
        )

    @Operation(summary = "Delete a repository")
    @ApiResponse(responseCode = "204", description = "Repository deleted")
    @ApiResponse(responseCode = "404", description = "Repository not found")
    @ApiResponse(responseCode = "409", description = "Repository still has active agent runs")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: UUID) {
        service.delete(id)
    }
}
