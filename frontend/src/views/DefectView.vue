<template>
  <div>
    <section class="panel">
      <div class="panel-head">
        <h3>故障保留</h3>
        <span class="panel-sub">到期日早于今天且未关闭的整行标红并计入超期；到期日等于今天为「今天到期」仍有效，下一业务日才超期</span>
        <div class="panel-extra">
          <el-checkbox v-model="onlyCurrent" label="只看当前机号" />
          <el-select v-model="filter" size="small" style="width: 132px; margin-right: 8px">
            <el-option label="全部缺陷" value="ALL" />
            <el-option label="只看超期" value="OVERDUE" />
            <el-option label="今天到期" value="TODAY" />
            <el-option label="未关闭" value="OPEN" />
          </el-select>
          <el-button size="small" @click="load">刷新</el-button>
          <el-button size="small" type="primary" @click="openCreate">登记缺陷</el-button>
        </div>
      </div>

      <el-alert
        v-if="overdueCount > 0"
        :title="`当前有 ${overdueCount} 条保留已经超期（到期日早于今天），需立即排故闭环`"
        type="error"
        show-icon
        :closable="false"
        style="margin-bottom: 12px"
      />
      <el-alert
        v-else-if="todayDueCount > 0"
        :title="`有 ${todayDueCount} 条保留今天到期，当日仍有效；明天起将按超期处理`"
        type="warning"
        show-icon
        :closable="false"
        style="margin-bottom: 12px"
      />

      <el-table :data="shown" border stripe size="small" :row-class-name="rowClass">
        <el-table-column prop="defectNo" label="缺陷单号" width="130" />
        <el-table-column prop="tailNo" label="飞机" width="90" />
        <el-table-column prop="ataChapter" label="ATA" width="80" />
        <el-table-column prop="description" label="故障描述" min-width="220" show-overflow-tooltip />
        <el-table-column label="保留期限" width="120">
          <template #default="{ row }">{{ row.limitValue }} {{ row.limitUnitName }}</template>
        </el-table-column>
        <el-table-column prop="foundDate" label="发现日期" width="110" />
        <el-table-column label="到期日" width="120">
          <template #default="{ row }">
            <span>{{ row.dueDate }}</span>
            <el-tag v-if="row.todayDue" size="small" type="warning" effect="plain" style="margin-left: 4px">今天到期</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="剩余" width="100">
          <template #default="{ row }">
            <span :style="{ color: remainColor(row) }">{{ remainText(row) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="statusTagType(row)">{{ statusTagText(row) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="reporter" label="填报人" width="120" />
        <el-table-column label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.status !== 'CLOSED'" link type="success" @click="doClose(row)">关闭</el-button>
            <span v-else class="muted">已闭环</span>
          </template>
        </el-table-column>
      </el-table>
      <div class="muted" style="margin-top: 8px; font-size: 12px">
        共 {{ list.length }} 条 · 未关闭（含今天到期）{{ openCount }} 条 · 今天到期 {{ todayDueCount }} 条 ·
        已超期 {{ overdueCount }} 条（与顶部「超期保留」同一口径）
      </div>
    </section>

    <el-dialog v-model="createVisible" title="登记故障保留" width="520px">
      <el-form :model="form" label-width="100px">
        <el-form-item label="缺陷单号"><el-input v-model="form.defectNo" placeholder="如 DF-2026-0014" /></el-form-item>
        <el-form-item label="飞机">
          <el-select v-model="form.aircraftId" filterable style="width: 100%">
            <el-option v-for="a in aircraftList" :key="a.id" :label="a.tailNo + ' · ' + a.model" :value="a.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="ATA 章节"><el-input v-model="form.ataChapter" placeholder="如 32-11" /></el-form-item>
        <el-form-item label="故障描述"><el-input v-model="form.description" type="textarea" :rows="2" /></el-form-item>
        <el-form-item label="保留期限">
          <el-input-number v-model="form.limitValue" :min="1" :step="1" style="width: 130px" />
          <el-select v-model="form.limitUnit" style="width: 130px; margin-left: 8px">
            <el-option label="飞行日" value="DAY" />
            <el-option label="飞行小时" value="HOUR" />
          </el-select>
        </el-form-item>
        <el-form-item label="发现日期">
          <el-date-picker v-model="form.foundDate" type="date" value-format="YYYY-MM-DD" placeholder="默认今天" />
        </el-form-item>
        <el-form-item label="填报人"><el-input v-model="form.reporter" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" @click="doCreate">登记</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, inject, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { defectApi } from '../api'

const mro = inject('mro')
const aircraftList = mro.aircraftList
const currentId = mro.aircraftId

const list = ref([])
const onlyCurrent = ref(false)
const filter = ref('ALL')
const createVisible = ref(false)
const form = ref({
  defectNo: '',
  aircraftId: null,
  ataChapter: '',
  description: '',
  limitUnit: 'DAY',
  limitValue: 10,
  foundDate: '',
  reporter: ''
})

const queryId = computed(() => (onlyCurrent.value ? currentId.value : null))

/** 口径与后端、与顶部总览完全一致：非关闭且到期日 < 今天才算超期；到期日 == 今天是「今天到期」 */
const overdueCount = computed(() => list.value.filter((d) => d.overdue).length)
const todayDueCount = computed(() => list.value.filter((d) => d.todayDue).length)
const openCount = computed(() => list.value.filter((d) => d.status !== 'CLOSED').length)

const shown = computed(() => {
  switch (filter.value) {
    case 'OVERDUE':
      return list.value.filter((d) => d.overdue)
    case 'TODAY':
      return list.value.filter((d) => d.todayDue)
    case 'OPEN':
      return list.value.filter((d) => d.status !== 'CLOSED')
    default:
      return list.value
  }
})

function rowClass({ row }) {
  if (row.overdue) return 'row-overdue'
  if (row.todayDue) return 'row-today-due'
  return ''
}

function remainColor(row) {
  if (row.overdue) return '#c62828'
  if (row.todayDue || row.daysLeft <= 3) return '#ef6c00'
  return '#2e7d32'
}

function remainText(row) {
  if (row.overdue) return `超期 ${-row.daysLeft} 天`
  if (row.todayDue) return '今天到期'
  return `剩余 ${row.daysLeft} 天`
}

function statusTagType(row) {
  if (row.overdue) return 'danger'
  if (row.status === 'CLOSED') return 'info'
  if (row.todayDue) return 'warning'
  return 'success'
}

function statusTagText(row) {
  if (row.overdue) return '超期'
  if (row.todayDue) return '今天到期'
  return row.statusName
}

async function load() {
  list.value = await defectApi.list(queryId.value)
  await mro.refreshOverview()
}

function openCreate() {
  form.value = {
    defectNo: '',
    aircraftId: currentId.value,
    ataChapter: '',
    description: '',
    limitUnit: 'DAY',
    limitValue: 10,
    foundDate: '',
    reporter: ''
  }
  createVisible.value = true
}

async function doCreate() {
  await defectApi.create(form.value)
  ElMessage.success('缺陷已登记')
  createVisible.value = false
  await load()
}

async function doClose(row) {
  await ElMessageBox.confirm(`确认关闭缺陷 ${row.defectNo}？`, '关闭缺陷', { type: 'warning' })
  await defectApi.close(row.id)
  ElMessage.success('缺陷已关闭')
  await load()
}

watch(queryId, load)

onMounted(load)
</script>
