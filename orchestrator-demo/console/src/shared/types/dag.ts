/** Shared DAG visualization types (used by the dry-run report and run replay). */
export interface DagNode {
  id: string
  wave: number
  label: string
  sub?: string
  status?: 'ok' | 'fail' | 'planned' | 'running'
  retries?: number
}

export interface DagEdge {
  from: string
  to: string
}
