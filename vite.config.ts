import { defineConfig } from 'vite'
import path from 'path'
import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'

// Resolves Figma Make's virtual `figma:asset/<hash>.png` imports to the
// downloaded copies in src/assets.
function figmaAssets() {
  return {
    name: 'figma-assets',
    resolveId(id: string) {
      if (id.startsWith('figma:asset/')) {
        return path.resolve(__dirname, 'src/assets', id.slice('figma:asset/'.length))
      }
      return null
    },
  }
}

export default defineConfig({
  plugins: [
    figmaAssets(),
    react(),
    tailwindcss(),
  ],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  assetsInclude: ['**/*.svg', '**/*.csv'],
})
