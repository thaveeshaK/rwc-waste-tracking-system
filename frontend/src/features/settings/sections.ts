export const settingsSections = [
  {id: 'container-types', label: 'Container Types'},
  {id: 'material-categories', label: 'Material Categories'},
  {id: 'users', label: 'User Management'},
  {id: 'organisation', label: 'Organisation Details'},
  {id: 'reporting', label: 'Reporting Defaults'},
  {id: 'whitegoods', label: 'Whitegoods Weight Factor'},
] as const
export type SettingsSection = typeof settingsSections[number]['id']
export function settingsSectionFromPath(path: string): SettingsSection | undefined {
  return settingsSections.find(s => path === `/admin/settings/${s.id}`)?.id
}
