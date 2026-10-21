export interface MaterialCategory { id: number; name: string }
export interface MaterialInput { materialCategoryId: number; percentage: string }
export interface DirectionInput { time: string; kg: string; materials: MaterialInput[] }
export interface SalvageInput {
  submissionId: string
  version?: number
  recordDate: string
  crew: string
  up: DirectionInput
  down: DirectionInput
}
export interface MaterialResult extends MaterialInput { name: string; materialWeight: string }
export interface DirectionResult { time: string; kg: string; materials: MaterialResult[] }
export interface SalvageRecord {
  id: number | null
  version: number | null
  submissionId: string | null
  recordDate: string
  crew: string
  up: DirectionResult
  down: DirectionResult
  createdBy: string | null
  createdAt: string | null
  updatedBy: string | null
  updatedAt: string | null
}
