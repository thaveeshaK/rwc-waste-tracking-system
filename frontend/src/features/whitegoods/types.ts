export interface WhitegoodsInput { submissionId: string; version?: number; recordDate: string; count: number }
export interface WhitegoodsRecord {
  id: number | null
  version: number | null
  submissionId: string | null
  recordDate: string
  count: number
  kgPerItem: string
  estimatedWeight: string
  createdBy: string | null
  createdAt: string | null
  updatedBy: string | null
  updatedAt: string | null
}
export interface MonthlySummary { month: string; totalCount: number; kgPerItem: string; estimatedWeight: string }
