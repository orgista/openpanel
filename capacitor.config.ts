import type { CapacitorConfig } from '@capacitor/cli'

const config: CapacitorConfig = {
  appId: 'com.orgista.openpanel',
  appName: 'OpenPanel',
  webDir: 'dist',
  android: {
    allowMixedContent: false,
  },
}

export default config
