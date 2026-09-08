import type { CreateRepositoryRequest, UpdateRepositoryRequest } from '@sift/api-client'
import { z } from 'zod'

/** Mirrors `CreateRepositoryRequest` validation on the server (`@NotBlank`, `@Size(max = 100)`). */
export const REPOSITORY_NAME_MAX_LENGTH = 100

const urlField = z
  .string()
  .trim()
  .min(1, 'URL is required')
  .refine(isGitUrl, 'Enter a valid https:// or ssh git URL')

export const createRepositorySchema = z.object({
  name: z
    .string()
    .trim()
    .min(1, 'Name is required')
    .max(
      REPOSITORY_NAME_MAX_LENGTH,
      `Name must be at most ${REPOSITORY_NAME_MAX_LENGTH} characters`,
    ),
  url: urlField,
  token: z.string(),
})

export const updateRepositorySchema = z
  .object({
    url: urlField,
    token: z.string(),
    clearToken: z.boolean(),
  })
  .refine((values) => !(values.clearToken && values.token.trim().length > 0), {
    message: 'Either enter a new token or clear the existing one, not both',
    path: ['token'],
  })

export type CreateRepositoryFormValues = z.infer<typeof createRepositorySchema>
export type UpdateRepositoryFormValues = z.infer<typeof updateRepositorySchema>

export function toCreateRequest(values: CreateRepositoryFormValues): CreateRepositoryRequest {
  const token = values.token.trim()
  return { name: values.name, url: values.url, token: token.length > 0 ? token : null }
}

/** Only sends what changed; the server keeps the stored token when `token` is null and `clearToken` is false. */
export function toUpdateRequest(
  values: UpdateRepositoryFormValues,
  currentUrl: string,
): UpdateRepositoryRequest {
  const token = values.token.trim()
  return {
    url: values.url !== currentUrl ? values.url : null,
    token: values.clearToken || token.length === 0 ? null : token,
    clearToken: values.clearToken,
  }
}

function isGitUrl(value: string): boolean {
  if (/^[\w.-]+@[\w.-]+:.+/.test(value)) return true // scp-like: git@github.com:org/repo.git
  try {
    const url = new URL(value)
    return ['http:', 'https:', 'ssh:', 'git:'].includes(url.protocol)
  } catch {
    return false
  }
}
