import type { TallyRecord } from '../electrical-testing/types'
import type { WhitegoodsRecord } from '../whitegoods/types'
import type { CollectableRecord } from '../collectables/types'
import type { ElectricalCratePalletRecord } from '../electrical-crate-pallet/types'
import type { PickupRecord } from '../pickup/types'
import type { CratePalletRecord } from '../crate-pallet/types'
import type { SalvageRecord } from '../salvage/types'
export type Workflow = 'CRATE_PALLET' | 'SALVAGE' | 'PICKUP' | 'ELECTRICAL_CRATE_PALLET' | 'COLLECTABLES' | 'WHITEGOODS' | 'ELECTRICAL_TESTING'
export type OperationalRecord = { workflow: 'CRATE_PALLET'; record: CratePalletRecord } | { workflow: 'SALVAGE'; record: SalvageRecord } | { workflow: 'PICKUP'; record: PickupRecord } | { workflow: 'ELECTRICAL_CRATE_PALLET'; record: ElectricalCratePalletRecord } | { workflow: 'COLLECTABLES'; record: CollectableRecord } | { workflow: 'WHITEGOODS'; record: WhitegoodsRecord } | { workflow: 'ELECTRICAL_TESTING'; record: TallyRecord }
