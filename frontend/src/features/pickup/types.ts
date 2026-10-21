export interface MaterialCategory { id: number; name: string }
export interface MaterialInput { materialCategoryId: number; percentage: string }
export interface PickupInput {
  submissionId: string
  version?: number
  recordDate: string
  pickupLocation: string
  netWeight: string
  materials: MaterialInput[]
}
export interface MaterialResult extends MaterialInput { name: string; materialWeight: string }
export interface PickupRecord {
  id: number | null
  version: number | null
  submissionId: string | null
  recordDate: string
  pickupLocation: string
  netWeight: string
  materials: MaterialResult[]
  createdBy: string | null
  createdAt: string | null
  updatedBy: string | null
  updatedAt: string | null
}
