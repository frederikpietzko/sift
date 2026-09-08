import { SearchIcon, XIcon } from 'lucide-react'
import { useId, useState } from 'react'

import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import type { ResultsSearch } from '@/features/results/result-helpers'

export type ResultFilterValues = Pick<ResultsSearch, 'repositoryUrl' | 'commitSha' | 'agentRunId'>

interface ResultFiltersProps {
  value: ResultFilterValues
  onChange: (next: ResultFilterValues) => void
}

function blankToUndefined(value: string): string | undefined {
  const trimmed = value.trim()
  return trimmed.length > 0 ? trimmed : undefined
}

/**
 * Free-text filters are applied on submit (Enter / "Apply") rather than per keystroke. The drafts
 * are seeded from `value` once; render with a `key` derived from the applied filters so the inputs
 * reset when the URL changes from the outside (back button, "Clear filters").
 */
export function ResultFilters({ value, onChange }: ResultFiltersProps) {
  const id = useId()
  const [draft, setDraft] = useState({
    repositoryUrl: value.repositoryUrl ?? '',
    commitSha: value.commitSha ?? '',
  })

  const hasFilters = Boolean(value.repositoryUrl || value.commitSha || value.agentRunId)

  return (
    <form
      className="flex flex-wrap items-end gap-3"
      role="search"
      aria-label="Result filters"
      onSubmit={(event) => {
        event.preventDefault()
        onChange({
          ...value,
          repositoryUrl: blankToUndefined(draft.repositoryUrl),
          commitSha: blankToUndefined(draft.commitSha),
        })
      }}
    >
      <div className="grid w-72 gap-1.5">
        <Label htmlFor={`${id}-repository-url`}>Repository URL</Label>
        <Input
          id={`${id}-repository-url`}
          placeholder="https://github.com/org/repo.git"
          value={draft.repositoryUrl}
          onChange={(event) => setDraft({ ...draft, repositoryUrl: event.target.value })}
        />
      </div>
      <div className="grid w-64 gap-1.5">
        <Label htmlFor={`${id}-commit`}>Commit SHA</Label>
        <Input
          id={`${id}-commit`}
          className="font-mono"
          placeholder="a1b2c3d…"
          spellCheck={false}
          value={draft.commitSha}
          onChange={(event) => setDraft({ ...draft, commitSha: event.target.value })}
        />
      </div>
      <Button type="submit" variant="outline">
        <SearchIcon />
        Apply
      </Button>
      {value.agentRunId && (
        <Button
          type="button"
          variant="secondary"
          size="sm"
          aria-label="Remove run filter"
          onClick={() => onChange({ ...value, agentRunId: undefined })}
        >
          Run {value.agentRunId.slice(0, 8)}…
          <XIcon />
        </Button>
      )}
      {hasFilters && (
        <Button type="button" variant="ghost" size="sm" onClick={() => onChange({})}>
          <XIcon />
          Clear filters
        </Button>
      )}
    </form>
  )
}
