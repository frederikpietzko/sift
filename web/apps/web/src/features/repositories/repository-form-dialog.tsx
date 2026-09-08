import { zodResolver } from '@hookform/resolvers/zod'
import type { RepositoryResponse } from '@sift/api-client'
import { useEffect } from 'react'
import { Controller, useForm, useWatch } from 'react-hook-form'
import { toast } from 'sonner'

import { useCreateRepository, useUpdateRepository } from '@/api/hooks/repositories'
import { ApiErrorAlert } from '@/components/form/api-error-alert'
import { FormField } from '@/components/form/form-field'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import {
  createRepositorySchema,
  toCreateRequest,
  toUpdateRequest,
  updateRepositorySchema,
  type CreateRepositoryFormValues,
  type UpdateRepositoryFormValues,
} from '@/features/repositories/repository-schema'

interface DialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
}

export function CreateRepositoryDialog({ open, onOpenChange }: DialogProps) {
  const create = useCreateRepository()
  const form = useForm<CreateRepositoryFormValues>({
    resolver: zodResolver(createRepositorySchema),
    defaultValues: { name: '', url: '', token: '' },
  })

  useEffect(() => {
    if (!open) return
    form.reset({ name: '', url: '', token: '' })
    create.reset()
    // eslint-disable-next-line react-hooks/exhaustive-deps -- reset only when the dialog opens
  }, [open])

  const onSubmit = form.handleSubmit(async (values) => {
    try {
      const repository = await create.mutateAsync(toCreateRequest(values))
      toast.success(`Repository "${repository?.name ?? values.name}" registered`)
      onOpenChange(false)
    } catch {
      // surfaced inline via create.error
    }
  })

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <form onSubmit={onSubmit} noValidate className="grid gap-4">
          <DialogHeader>
            <DialogTitle>Register repository</DialogTitle>
            <DialogDescription>
              Reviews can be started for registered repositories. The access token is stored
              encrypted and never shown again.
            </DialogDescription>
          </DialogHeader>

          <ApiErrorAlert error={create.error} />

          <FormField label="Name" error={form.formState.errors.name?.message}>
            {(control) => (
              <Input
                {...control}
                {...form.register('name')}
                placeholder="my-service"
                autoComplete="off"
                autoFocus
              />
            )}
          </FormField>
          <FormField label="Git URL" error={form.formState.errors.url?.message}>
            {(control) => (
              <Input
                {...control}
                {...form.register('url')}
                placeholder="https://github.com/org/repo.git"
                autoComplete="off"
              />
            )}
          </FormField>
          <FormField
            label="Access token"
            optional
            description="Used to clone private repositories and post review comments."
            error={form.formState.errors.token?.message}
          >
            {(control) => (
              <Input
                {...control}
                {...form.register('token')}
                type="password"
                autoComplete="new-password"
              />
            )}
          </FormField>

          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>
              Cancel
            </Button>
            <Button type="submit" disabled={create.isPending}>
              {create.isPending ? 'Registering…' : 'Register'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

interface EditRepositoryDialogProps extends DialogProps {
  repository: RepositoryResponse | null
}

export function EditRepositoryDialog({
  open,
  onOpenChange,
  repository,
}: EditRepositoryDialogProps) {
  const update = useUpdateRepository()
  const form = useForm<UpdateRepositoryFormValues>({
    resolver: zodResolver(updateRepositorySchema),
    defaultValues: { url: repository?.url ?? '', token: '', clearToken: false },
  })

  useEffect(() => {
    if (!open) return
    form.reset({ url: repository?.url ?? '', token: '', clearToken: false })
    update.reset()
    // eslint-disable-next-line react-hooks/exhaustive-deps -- reset only when the dialog opens
  }, [open, repository?.id])

  const clearToken = useWatch({ control: form.control, name: 'clearToken' })
  const hasToken = repository?.hasToken ?? false

  const onSubmit = form.handleSubmit(async (values) => {
    if (!repository?.id) return
    try {
      await update.mutateAsync({
        id: repository.id,
        body: toUpdateRequest(values, repository.url ?? ''),
      })
      toast.success(`Repository "${repository.name ?? ''}" updated`)
      onOpenChange(false)
    } catch {
      // surfaced inline via update.error
    }
  })

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <form onSubmit={onSubmit} noValidate className="grid gap-4">
          <DialogHeader>
            <DialogTitle>Edit repository</DialogTitle>
            <DialogDescription>
              {repository?.name ? (
                <>
                  Update the URL or access token of <strong>{repository.name}</strong>. The name
                  cannot be changed.
                </>
              ) : (
                'Update the URL or access token.'
              )}
            </DialogDescription>
          </DialogHeader>

          <ApiErrorAlert error={update.error} />

          <FormField label="Git URL" error={form.formState.errors.url?.message}>
            {(control) => <Input {...control} {...form.register('url')} autoComplete="off" />}
          </FormField>
          <FormField
            label="New access token"
            optional
            description={
              hasToken
                ? 'Leave empty to keep the current token.'
                : 'No token is stored for this repository.'
            }
            error={form.formState.errors.token?.message}
          >
            {(control) => (
              <Input
                {...control}
                {...form.register('token')}
                type="password"
                autoComplete="new-password"
                disabled={clearToken}
              />
            )}
          </FormField>
          {hasToken && (
            <Controller
              control={form.control}
              name="clearToken"
              render={({ field }) => (
                <div className="flex items-start gap-3">
                  <Checkbox
                    id="clear-token"
                    checked={field.value}
                    onCheckedChange={(checked) => field.onChange(checked === true)}
                    onBlur={field.onBlur}
                  />
                  <div className="grid gap-1">
                    <Label htmlFor="clear-token">Clear the stored token</Label>
                    <p className="text-muted-foreground text-xs">
                      Removes the token from the server and its Kubernetes secret. Reviews of
                      private repositories will fail until a new token is set.
                    </p>
                  </div>
                </div>
              )}
            />
          )}

          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>
              Cancel
            </Button>
            <Button type="submit" disabled={update.isPending || !repository}>
              {update.isPending ? 'Saving…' : 'Save changes'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
