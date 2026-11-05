<script setup lang="ts">
import { init, use } from 'echarts/core'
import type { ECharts } from 'echarts/core'
import type { EChartsOption } from 'echarts'
import { BarChart, LineChart, RadarChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'

const props = withDefaults(defineProps<{ option: EChartsOption; height?: number }>(), { height: 320 })
const element = ref<HTMLDivElement>()
use([BarChart, LineChart, RadarChart, GridComponent, LegendComponent, TooltipComponent, CanvasRenderer])

let chart: ECharts | undefined
let observer: ResizeObserver | undefined

function render() {
  if (!element.value) return
  chart ??= init(element.value)
  chart.setOption(props.option, true)
}

onMounted(async () => {
  await nextTick()
  render()
  observer = new ResizeObserver(() => chart?.resize())
  if (element.value) observer.observe(element.value)
})
watch(() => props.option, render, { deep: true })
onBeforeUnmount(() => {
  observer?.disconnect()
  chart?.dispose()
})
</script>

<template>
  <div ref="element" class="chart-panel" :style="{ height: `${height}px` }" />
</template>
