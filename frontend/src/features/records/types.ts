import type { CratePalletRecord } from '../crate-pallet/types'
import type { SalvageRecord } from '../salvage/types'
export type Workflow = 'CRATE_PALLET' | 'SALVAGE'
export type OperationalRecord = { workflow: 'CRATE_PALLET'; record: CratePalletRecord } | { workflow: 'SALVAGE'; record: SalvageRecord }
