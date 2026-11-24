import { databasePluginApi } from '../api/databasePlugin.js'
import { useManagedPlugin } from './useManagedPlugin.js'
export function useDatabasePlugin(options, api = databasePluginApi) {
  return useManagedPlugin(options, api, { pluginId: 'dbx', name: 'DBX' })
}
