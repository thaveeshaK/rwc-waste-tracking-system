export const materials = [
  {key: 'ironAndSteelWeight', label: 'Iron and Steel'},
  {key: 'aluminiumWeight', label: 'Aluminium'},
  {key: 'otherNonFerrousWeight', label: 'Other Non-Ferrous'},
  {key: 'mixedMetalsWeight', label: 'Mixed Metals'},
  {key: 'ewasteWeight', label: 'E-waste'},
] as const
export interface Weights { ironAndSteelWeight: string; aluminiumWeight: string; otherNonFerrousWeight: string; mixedMetalsWeight: string; ewasteWeight: string }
export interface ScrapInput extends Weights {submissionId: string; reportingMonth: string; version?: number}
export interface ScrapRecord extends Weights {id: number | null; version: number | null; submissionId: string | null; reportingMonth: string; totalWeight: string; createdBy: string | null; createdAt: string | null; updatedBy: string | null; updatedAt: string | null}
export interface ScrapTotals extends Weights {reportingMonth: string; totalWeight: string}
export interface ScrapPreview {entry: ScrapRecord; current: ScrapTotals; projected: ScrapTotals}
