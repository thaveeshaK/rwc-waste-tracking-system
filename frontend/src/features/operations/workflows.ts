import type { Workflow } from '../records/types'
export interface WorkflowEntry { id: Workflow; name: string; description: string }
export const workflowEntries: WorkflowEntry[] = [
  {id: 'CRATE_PALLET', name: 'Crate / Pallet', description: 'Record a weighed container and its material breakdown.'},
  {id: 'SALVAGE', name: 'Salvage', description: 'Record one trip, with recovered and landfill weights.'},
  {id: 'ELECTRICAL_CRATE_PALLET', name: 'Electrical Crate / Pallet', description: 'Record electrical material weight in its two approved groups.'},
  {id: 'PICKUP', name: 'Pickup', description: 'Record a pickup location, net weight and materials.'},
  {id: 'EWASTE_PICKUP', name: 'E-waste Pickup', description: 'Record E-waste collected from organisations.'},
  {id: 'COLLECTABLES', name: 'Collectables', description: 'Record an eligible Collectable Tub and material breakdown.'},
  {id: 'WHITEGOODS', name: 'Whitegoods', description: 'Record a received quantity and review its estimated weight.'},
  {id: 'ELECTRICAL_TESTING', name: 'Electrical Testing', description: 'Add processing tallies or maintain monthly sales references.'},
  {id: 'SCRAP', name: 'Scrap', description: 'Record monthly Scrap material weights.'},
]
