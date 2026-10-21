export interface ContainerType { id: number; name: string; tareWeight: string }
export interface MaterialCategory { id: number; name: string }
export interface MaterialInput { materialCategoryId: number; percentage: string }
export interface CratePalletInput {
  submissionId: string
  recordDate: string
  containerTypeId: number
  grossWeight: string
  materials: MaterialInput[]
}
export interface MaterialResult extends MaterialInput { name: string; materialWeight: string }
export interface CratePalletRecord {
  id: number | null
  submissionId: string | null
  recordDate: string
  containerTypeId: number
  containerTypeName: string
  grossWeight: string
  tareWeight: string
  netWeight: string
  materials: MaterialResult[]
  createdBy: string | null
  createdAt: string | null
  updatedBy: string | null
  updatedAt: string | null
}
