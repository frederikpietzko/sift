import { AGENT_PHASES, type RepositoryResponse } from '@sift/api-client'
import { XIcon } from 'lucide-react'
import { useId } from 'react'

import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Label } from '@/components/ui/label'
import { NativeSelect } from '@/components/ui/native-select'
import { phaseLabel } from '@/features/runs/run-spec'
import { isAgentPhase, type RunsSearch } from '@/features/runs/run-schema'

export type RunFilterValues = Pick<RunsSearch, 'kind' | 'phase' | 'repositoryId' | 'mine'>

interface RunFiltersProps {
  value: RunFilterValues
  repositories: RepositoryResponse[]
  onChange: (next: RunFilterValues) => void
}

const ALL = ''

export function RunFilters({ value, repositories, onChange }: RunFiltersProps) {
  const id = useId()
  const hasFilters = Boolean(value.kind || value.phase || value.repositoryId || value.mine)

  return (
    <div className="flex flex-wrap items-end gap-3" role="group" aria-label="Run filters">
      <div className="grid w-40 gap-1.5">
        <Label htmlFor={`${id}-kind`}>Kind</Label>
        <NativeSelect
          id={`${id}-kind`}
          value={value.kind ?? ALL}
          onChange={(event) =>
            onChange({
              ...value,
              kind: event.target.value === 'CODE_REVIEW' ? 'CODE_REVIEW' : undefined,
            })
          }
        >
          <option value={ALL}>All kinds</option>
          <option value="CODE_REVIEW">Code review</option>
        </NativeSelect>
      </div>
      <div className="grid w-40 gap-1.5">
        <Label htmlFor={`${id}-phase`}>Phase</Label>
        <NativeSelect
          id={`${id}-phase`}
          value={value.phase ?? ALL}
          onChange={(event) => {
            const next = event.target.value
            onChange({ ...value, phase: isAgentPhase(next) ? next : undefined })
          }}
        >
          <option value={ALL}>All phases</option>
          {AGENT_PHASES.map((phase) => (
            <option key={phase} value={phase}>
              {phaseLabel(phase)}
            </option>
          ))}
        </NativeSelect>
      </div>
      <div className="grid w-56 gap-1.5">
        <Label htmlFor={`${id}-repository`}>Repository</Label>
        <NativeSelect
          id={`${id}-repository`}
          value={value.repositoryId ?? ALL}
          onChange={(event) =>
            onChange({ ...value, repositoryId: event.target.value || undefined })
          }
        >
          <option value={ALL}>All repositories</option>
          {repositories.map((repository) => (
            <option key={repository.id} value={repository.id}>
              {repository.name ?? repository.url ?? repository.id}
            </option>
          ))}
        </NativeSelect>
      </div>
      <div className="flex h-9 items-center gap-2">
        <Checkbox
          id={`${id}-mine`}
          checked={value.mine ?? false}
          onCheckedChange={(checked) =>
            onChange({ ...value, mine: checked === true ? true : undefined })
          }
        />
        <Label htmlFor={`${id}-mine`}>Only my runs</Label>
      </div>
      {hasFilters && (
        <Button variant="ghost" size="sm" onClick={() => onChange({})}>
          <XIcon />
          Clear filters
        </Button>
      )}
    </div>
  )
}
