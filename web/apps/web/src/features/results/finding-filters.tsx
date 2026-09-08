import { SEVERITIES, type Severity } from '@sift/api-client'
import { XIcon } from 'lucide-react'
import { useId } from 'react'

import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import { NativeSelect } from '@/components/ui/native-select'
import {
  isSeverity,
  severityLabel,
  type ResultDetailSearch,
} from '@/features/results/result-helpers'

interface FindingFiltersProps {
  value: ResultDetailSearch
  /** Distinct files of the (unfiltered) result, for the file select. */
  files: string[]
  /** Per-severity totals of the (unfiltered) result, shown next to the option label. */
  counts: Record<Severity, number>
  onChange: (next: ResultDetailSearch) => void
}

const ALL = ''

export function FindingFilters({ value, files, counts, onChange }: FindingFiltersProps) {
  const id = useId()
  const hasFilters = Boolean(value.severity || value.file)

  return (
    <div className="flex flex-wrap items-end gap-3" role="group" aria-label="Finding filters">
      <div className="grid w-44 gap-1.5">
        <Label htmlFor={`${id}-severity`}>Severity</Label>
        <NativeSelect
          id={`${id}-severity`}
          value={value.severity ?? ALL}
          onChange={(event) => {
            const next = event.target.value
            onChange({ ...value, severity: isSeverity(next) ? next : undefined })
          }}
        >
          <option value={ALL}>All severities</option>
          {SEVERITIES.map((severity) => (
            <option key={severity} value={severity}>
              {severityLabel(severity)} ({counts[severity]})
            </option>
          ))}
        </NativeSelect>
      </div>
      <div className="grid w-80 max-w-full gap-1.5">
        <Label htmlFor={`${id}-file`}>File</Label>
        <NativeSelect
          id={`${id}-file`}
          value={value.file ?? ALL}
          onChange={(event) => onChange({ ...value, file: event.target.value || undefined })}
        >
          <option value={ALL}>All files</option>
          {files.map((file) => (
            <option key={file} value={file}>
              {file}
            </option>
          ))}
        </NativeSelect>
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
