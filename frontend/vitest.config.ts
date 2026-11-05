import { defineConfig } from 'vitest/config'

export default defineConfig({
  test: {
    include: ['tests/*.spec.ts'],
    coverage: {
      provider: 'v8',
      reporter: ['text', 'html', 'json-summary'],
      reportsDirectory: 'coverage',
      include: ['src/utils/**/*.ts'],
      thresholds: { lines: 60, functions: 60, statements: 60, branches: 60 }
    }
  }
})
