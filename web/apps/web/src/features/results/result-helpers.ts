import { SEVERITIES, type ReviewFindingResponse, type Severity } from '@sift/api-client'
import { z } from 'zod'

/** Filters the results list accepts from the URL (run detail links here via `agentRunId`). */
export const resultsSearchSchema = z.object({
  repositoryUrl: z.string().optional().catch(undefined),
  commitSha: z.string().optional().catch(undefined),
  agentRunId: z.uuid().optional().catch(undefined),
  page: z.number().int().min(0).optional().catch(undefined),
})

export type ResultsSearch = z.infer<typeof resultsSearchSchema>

/** Filters the result detail keeps in the URL so a filtered view can be shared. */
export const resultDetailSearchSchema = z.object({
  severity: z.enum(SEVERITIES).optional().catch(undefined),
  file: z.string().optional().catch(undefined),
})

export type ResultDetailSearch = z.infer<typeof resultDetailSearchSchema>

export function isSeverity(value: string): value is Severity {
  return (SEVERITIES as readonly string[]).includes(value)
}

const SEVERITY_LABELS: Record<Severity, string> = {
  BLOCKER: 'Blocker',
  MAJOR: 'Major',
  MINOR: 'Minor',
  INFO: 'Info',
}

export function severityLabel(severity: Severity | undefined): string {
  return severity ? SEVERITY_LABELS[severity] : 'Unknown'
}

/** Lower is more severe; findings without a severity sort last. */
export function severityRank(severity: Severity | undefined): number {
  return severity ? SEVERITIES.indexOf(severity) : SEVERITIES.length
}

/** Strips the scheme and `.git` suffix so repository URLs stay readable in tables/headings. */
export function repositoryLabel(url: string | undefined): string {
  if (!url) return '—'
  return url.replace(/^[a-z]+:\/\//i, '').replace(/\.git$/, '')
}

export function lineRangeLabel(finding: ReviewFindingResponse): string | undefined {
  const { startLine, endLine } = finding
  if (startLine == null) return undefined
  if (endLine == null || endLine === startLine) return `L${startLine}`
  return `L${startLine}–${endLine}`
}

export const UNKNOWN_FILE = '(unknown file)'

export interface FindingGroup {
  file: string
  findings: ReviewFindingResponse[]
  /** Highest severity within the group (drives the group ordering). */
  topSeverity: Severity | undefined
}

/**
 * Groups findings by file. Groups are ordered by their most severe finding, then by file name;
 * findings inside a group are ordered by severity and then by start line.
 */
export function groupFindingsByFile(findings: readonly ReviewFindingResponse[]): FindingGroup[] {
  const byFile = new Map<string, ReviewFindingResponse[]>()
  for (const finding of findings) {
    const file = finding.file || UNKNOWN_FILE
    const bucket = byFile.get(file)
    if (bucket) bucket.push(finding)
    else byFile.set(file, [finding])
  }

  const groups: FindingGroup[] = []
  for (const [file, group] of byFile) {
    const sorted = [...group].sort(
      (a, b) =>
        severityRank(a.severity) - severityRank(b.severity) ||
        (a.startLine ?? Number.MAX_SAFE_INTEGER) - (b.startLine ?? Number.MAX_SAFE_INTEGER),
    )
    groups.push({ file, findings: sorted, topSeverity: sorted[0]?.severity })
  }
  return groups.sort(
    (a, b) =>
      severityRank(a.topSeverity) - severityRank(b.topSeverity) || a.file.localeCompare(b.file),
  )
}

/** Number of findings per severity (always lists every severity, zero when absent). */
export function countBySeverity(
  findings: readonly ReviewFindingResponse[],
): Record<Severity, number> {
  const counts = { BLOCKER: 0, MAJOR: 0, MINOR: 0, INFO: 0 } satisfies Record<Severity, number>
  for (const finding of findings) {
    if (finding.severity) counts[finding.severity] += 1
  }
  return counts
}

/** Distinct file paths in stable (sorted) order for the file filter. */
export function distinctFiles(findings: readonly ReviewFindingResponse[]): string[] {
  return [...new Set(findings.map((finding) => finding.file).filter(Boolean) as string[])].sort(
    (a, b) => a.localeCompare(b),
  )
}
