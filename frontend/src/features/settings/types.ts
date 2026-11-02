export interface Configuration {
  version: number; organisationName: string; siteName: string; reportDisplayName: string;
  displayUnit: 'kg' | 'tonnes'; reportPeriod: 'CURRENT_MONTH' | 'CURRENT_FINANCIAL_YEAR';
  financialYearStartMonth: number; whitegoodsKgPerItem: string; updatedBy: string | null; updatedAt: string | null
}
export interface Reference { id: number; version: number; name: string; tareWeight: string | null; active: boolean; contexts: string[]; restricted: boolean }
export interface ReferenceInput { version?: number; name: string; tareWeight: string | null; active: boolean; contexts: string[] }
export interface OperationalConfiguration { organisationName: string; siteName: string; reportDisplayName: string; whitegoodsKgPerItem: string; timezone: string }
export const materialContexts = ['CRATE_PALLET', 'SALVAGE_UP', 'SALVAGE_DOWN', 'PICKUP', 'COLLECTABLES']
export const containerContexts = ['CRATE_PALLET', 'ELECTRICAL_CRATE_PALLET']
