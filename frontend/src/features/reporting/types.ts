export interface Filters { from: string; to: string; workflow: string; materialCategoryId: string; location: string; direction: string; testingCategoryId: string }
export interface Total { workflow: string; direction: string | null; container: string | null; categoryId: number | null; category: string | null; metric: string; unit: string; value: string }
export interface Row { rowType: string; workflow: string; id: number; date: string; container: string | null; location: string | null; crew: string | null; direction: string | null; category: string | null; status: string | null; kg: string | null; grossKg: string | null; tareKg: string | null; percentage: string | null; count: number | null; factor: string | null; timeUp: string | null; timeDown: string | null; submissionId: string; version: number; createdBy: string; createdAt: string; updatedBy: string; updatedAt: string }
export interface Summary { filters: Filters; timezone: string; generatedAt: string; totals: Total[] }
export interface Details { filters: Filters; generatedAt: string; rows: Row[]; totalRows: number; page: number; size: number }
export interface Monthly { month: string; generatedAt: string; note: string; totals: Total[] }
export interface Overview { today: string; submittedToday: number; recordsThisMonth: number; activity: {workflow: string; count: number}[]; recent: Row[]; trend: {month: string; workflow: string; count: number}[]; monthly: Summary }
export interface Options { materials: {id: number; name: string}[]; testingCategories: {id: number; name: string}[] }
export const workflows: Record<string, string> = { CRATE_PALLET: 'Crate / Pallet', SALVAGE: 'Salvage', PICKUP: 'Pickup', ELECTRICAL_CRATE_PALLET: 'Electrical Crate / Pallet', COLLECTABLES: 'Collectables', WHITEGOODS: 'Whitegoods', ELECTRICAL_TESTING: 'Electrical Testing', GENERAL_INTAKE: 'Combined general intake' }
export const label = (value: string) => workflows[value] ?? value.replaceAll('_', ' ').toLowerCase()
