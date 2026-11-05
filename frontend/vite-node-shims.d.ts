declare module 'node:fs/promises' {
  export function copyFile(source: string, destination: string): Promise<void>
  export function cp(source: string, destination: string, options: { recursive: boolean }): Promise<void>
  export function mkdir(path: string, options: { recursive: boolean }): Promise<string | undefined>
  export function readFile(path: string, encoding: 'utf8'): Promise<string>
}

declare module 'node:path' {
  export function dirname(path: string): string
  export function resolve(...paths: string[]): string
}
