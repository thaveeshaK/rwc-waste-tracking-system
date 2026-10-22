export const processingTypes = ['UNTESTED_BROKEN_FAILED', 'TESTED_AND_PRICED', 'STOCK_CULL'] as const
export type TallyType = typeof processingTypes[number] | 'DIVERTED_TO_OLS' | 'DIVERTED_TO_COLL'
export const typeLabels: Record<TallyType, string> = {
  UNTESTED_BROKEN_FAILED: 'Untested / Broken / Failed', TESTED_AND_PRICED: 'Tested and Priced', STOCK_CULL: 'Stock Cull',
  DIVERTED_TO_OLS: 'Diverted to OLS', DIVERTED_TO_COLL: 'Diverted to Coll',
}
export interface Category { id: number; name: string; displayOrder: number }
export interface TallyInput { submissionId: string; version?: number; recordDate: string; categoryId: number | null; tallyType: TallyType; count: number }
export interface Metadata { id: number | null; version: number | null; submissionId: string | null; createdBy: string | null; createdAt: string | null; updatedBy: string | null; updatedAt: string | null }
export interface TallyRecord extends Metadata { recordDate: string; categoryId: number | null; categoryName: string | null; tallyType: TallyType; count: number }
export interface ReferenceInput { submissionId: string; version?: number; reportingMonth: string; categoryId: number; soldLastMonthCount: number }
export interface SalesReference extends Metadata { reportingMonth: string; categoryId: number; categoryName: string; soldLastMonthCount: number }
export interface MonthlySummary { month: string; categories: { categoryId: number; categoryName: string; untestedBrokenFailed: number; testedAndPriced: number; stockCull: number }[]; divertedToOls: number; divertedToColl: number }
