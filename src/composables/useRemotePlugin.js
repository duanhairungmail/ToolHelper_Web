import { remotePluginApi } from '../api/remotePlugin.js'
import { useManagedPlugin } from './useManagedPlugin.js'
export function useRemotePlugin(options, api = remotePluginApi) {
  return useManagedPlugin(options, api, { pluginId: 'electerm', name: 'electerm' })
}
