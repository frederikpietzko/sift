import type { RepositoryResponse } from '@sift/api-client'
import { KeyRoundIcon, PencilIcon, Trash2Icon } from 'lucide-react'

import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { formatDateTime } from '@/lib/format'

interface RepositoryTableProps {
  repositories: RepositoryResponse[]
  onEdit: (repository: RepositoryResponse) => void
  onDelete: (repository: RepositoryResponse) => void
}

export function RepositoryTable({ repositories, onEdit, onDelete }: RepositoryTableProps) {
  return (
    <div className="overflow-hidden rounded-lg border">
      <Table aria-label="Repositories">
        <TableHeader>
          <TableRow>
            <TableHead>Name</TableHead>
            <TableHead>URL</TableHead>
            <TableHead>Token</TableHead>
            <TableHead>Updated</TableHead>
            <TableHead className="w-0">
              <span className="sr-only">Actions</span>
            </TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {repositories.map((repository) => {
            const name = repository.name ?? repository.id ?? 'unnamed'
            return (
              <TableRow key={repository.id ?? name} data-testid="repository-row">
                <TableCell className="font-medium">{name}</TableCell>
                <TableCell className="text-muted-foreground max-w-[28rem] truncate font-mono text-xs">
                  {repository.url ?? '—'}
                </TableCell>
                <TableCell>
                  {repository.hasToken ? (
                    <Badge variant="secondary">
                      <KeyRoundIcon aria-hidden />
                      Token set
                    </Badge>
                  ) : (
                    <Badge variant="outline">No token</Badge>
                  )}
                </TableCell>
                <TableCell className="text-muted-foreground whitespace-nowrap">
                  <time dateTime={repository.updatedAt ?? undefined}>
                    {formatDateTime(repository.updatedAt)}
                  </time>
                </TableCell>
                <TableCell>
                  <div className="flex justify-end gap-1">
                    <Button
                      variant="ghost"
                      size="icon"
                      aria-label={`Edit ${name}`}
                      onClick={() => onEdit(repository)}
                    >
                      <PencilIcon />
                    </Button>
                    <Button
                      variant="ghost"
                      size="icon"
                      aria-label={`Delete ${name}`}
                      className="text-destructive hover:text-destructive"
                      onClick={() => onDelete(repository)}
                    >
                      <Trash2Icon />
                    </Button>
                  </div>
                </TableCell>
              </TableRow>
            )
          })}
        </TableBody>
      </Table>
    </div>
  )
}
