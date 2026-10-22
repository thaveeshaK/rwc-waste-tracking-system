export interface MaterialCategory { id: number; name: string }
export interface MaterialInput { materialCategoryId: number; percentage: string }
export interface CollectableInput {
  submissionId: string
  version?: number
  recordDate: string
  grossWeight: string
  materials: MaterialInput[]
}
export interface MaterialResult extends MaterialInput { name: string; materialWeight: string }
export interface CollectableRecord {
  id: number | null
  version: number | null
  submissionId: string | null
  recordDate: string
  grossWeight: string
  tareWeight: string
  netWeight: string
  materials: MaterialResult[]
  createdBy: string | null
  createdAt: string | null
  updatedBy: string | null
  updatedAt: string | null
}
