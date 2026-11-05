import { copyFile, cp, mkdir, readFile } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import { defineConfig, loadEnv } from 'vite'
import type { Plugin } from 'vite'
import vue from '@vitejs/plugin-vue'

function staticPublicSnapshotPlugin(): Plugin {
  return {
    name: 'static-public-snapshot',
    async closeBundle() {
      const publicRoot = resolve('public')
      const outputRoot = resolve('dist-static')
      const snapshot = JSON.parse(await readFile(resolve(publicRoot, 'data/current.json'), 'utf8')) as {
        snapshot: { code: string }
        dataFiles?: Record<string, string>
      }
      const publicFiles = [
        'favicon.svg', 'logo-mark.svg', 'data/current.json', 'data/metadata.json',
        'data/checksums.sha256', 'data/validation-report.json',
        `data/snapshots/${snapshot.snapshot.code}.json`,
        ...Object.values(snapshot.dataFiles ?? {}).map(path => path.replace(/^\//, ''))
      ]
      await Promise.all(publicFiles.map(async path => {
        const target = resolve(outputRoot, path)
        await mkdir(dirname(target), { recursive: true })
        await copyFile(resolve(publicRoot, path), target)
      }))
      await cp(resolve(publicRoot, 'visuals'), resolve(outputRoot, 'visuals'), { recursive: true })
    }
  }
}

export default defineConfig(({ mode }) => {
  const apiTarget = loadEnv(mode, '.', '').VITE_DEV_API_TARGET || 'http://127.0.0.1:8080'
  const staticBuild = mode === 'static-demo'
  return {
    publicDir: staticBuild ? false : 'public',
    plugins: [vue(), ...(staticBuild ? [staticPublicSnapshotPlugin()] : [])],
    build: {
      outDir: staticBuild ? 'dist-static' : 'dist',
      rollupOptions: {
        output: {
          manualChunks(id) {
            const moduleId = id.replace(/\\/g, '/')
            if (moduleId.includes('/node_modules/zrender/')) return 'chart-renderer'
            if (moduleId.includes('/node_modules/echarts/')) return 'chart-engine'
            if (moduleId.includes('/node_modules/@element-plus/icons-vue/')) return 'element-icons'
            if (moduleId.includes('/node_modules/vue/') || moduleId.includes('/node_modules/@vue/') || moduleId.includes('/node_modules/pinia/') || moduleId.includes('/node_modules/vue-router/')) return 'vue-runtime'
          }
        }
      }
    },
    server: {
      port: 5173,
      proxy: {
        '/api': apiTarget,
        '/actuator': apiTarget
      }
    }
  }
})
