<script setup>
import { computed, ref } from 'vue'
import SqlCodePreview from './SqlCodePreview.vue'
import SqlValueInput from './SqlValueInput.vue'
import SqlConditions from './SqlConditions.vue'
import { useSql, newValue, newColumn, columnTypes } from '../composables/useSql.js'
const { draft, form, generated, formatted, capabilities, error, notice, generating, formatting, loading, sourceBytes,
  load, runGenerate, runFormat, copy, download, importFile, sendToFormatter, resetForm, clearInput } = useSql()
const fileInput = ref(null)
const availableTypes = computed(() => draft.dialect === 'MYSQL' ? [...columnTypes, 'DATETIME'] : columnTypes)
function changeColumnType(column) {
  column.length = ['CHAR', 'VARCHAR'].includes(column.type) ? 128 : null
  column.precision = column.type === 'DECIMAL' ? 10 : null
  column.scale = column.type === 'DECIMAL' ? 2 : null
  column.identity = false; column.defaultValue = null
}
function changeDefault(column, event) { column.defaultValue = event.target.value ? newValue(event.target.value) : null }
function selectFile(event) { const file = event.target.files?.[0]; event.target.value = ''; importFile(file) }

const isGenerate = computed(() => draft.tab === 'generate')
const activeResult = computed(() => isGenerate.value ? generated.value : formatted.value)
const sqlText = computed(() => activeResult.value?.sql || '')
const busy = computed(() => generating.value || formatting.value)
const lineCount = computed(() => sqlText.value ? sqlText.value.split('\n').length : 0)
</script>

<template>
  <div class="sql-workspace">
    <header class="sq-command">
      <div class="sq-switch" aria-label="SQL工作模式">
        <button :class="{ active: isGenerate }" :aria-pressed="isGenerate" @click="draft.tab = 'generate'"><span aria-hidden="true">⌘</span> SQL 生成</button>
        <button :class="{ active: !isGenerate }" :aria-pressed="!isGenerate" @click="draft.tab = 'format'"><span aria-hidden="true">≡</span> SQL 格式化</button>
      </div>
      <div class="sq-command-right">
        <label>数据库<select v-model="draft.dialect" aria-label="数据库方言"><option value="MYSQL">MySQL</option><option value="POSTGRESQL">PostgreSQL</option></select></label>
        <label v-if="draft.dialect === 'MYSQL'">字符串转义<select v-model="draft.escapeMode" aria-label="MySQL字符串转义"><option value="BACKSLASH">反斜杠启用</option><option value="NO_BACKSLASH_ESCAPES">NO_BACKSLASH_ESCAPES</option></select></label>
        <span v-else class="sq-command-hint">生成使用 E 字符串</span>
      </div>
    </header>
    <div v-if="!capabilities" class="sq-service" role="status"><span>{{ loading ? '正在连接本机 SQL 服务…' : '本机 SQL 服务未就绪' }}</span><button class="sq-text-button" :disabled="loading" @click="load">重新连接</button></div>
    <div class="sq-workbench">
      <section class="sq-config" :aria-label="isGenerate ? '生成参数' : '格式化输入'">
        <div class="sq-config-heading"><div><span class="sq-eyebrow">{{ isGenerate ? 'QUERY BUILDER' : 'SQL FORMATTER' }}</span><h2>{{ isGenerate ? '构建你的语句' : '整理你的 SQL' }}</h2></div><button v-if="isGenerate" class="sq-text-button" @click="resetForm">重置当前表单</button><button v-else class="sq-text-button" :disabled="!draft.source" @click="clearInput">清空输入</button></div>
        <template v-if="isGenerate">
          <div class="sq-statement-tabs" aria-label="语句类型"><button v-for="type in ['SELECT','INSERT','UPDATE','DELETE','CREATE_TABLE']" :key="type" :class="{ active: draft.statementType === type }" :aria-pressed="draft.statementType === type" @click="draft.statementType = type">{{ type === 'CREATE_TABLE' ? 'CREATE' : type }}</button></div>
          <div class="sq-config-scroll">

            <section class="sq-section">
              <h3><span class="sq-step">01</span> 数据来源 <span class="sq-section-note">{{ draft.dialect === 'MYSQL' ? 'MySQL' : 'PostgreSQL' }}</span></h3>
              <div class="sq-source-fields"><label>{{ draft.dialect === 'MYSQL' ? '数据库名' : 'Schema' }} <span>可选</span><input v-model="form.schema" aria-label="数据库名或Schema" placeholder="默认命名空间"></label><label>表名<input v-model="form.table" aria-label="表名" placeholder="例如 devices"></label></div>
              <p class="sq-hint">名称按原样引用，请核对大小写与目标库转义模式。</p>
            </section>
        <template v-if="draft.statementType === 'SELECT'">
          <section class="sq-section">
            <h3><span class="sq-step">02</span> 选择字段 <span class="sq-count">{{ form.allColumns ? '*' : form.columns.length }}</span><button v-if="!form.allColumns" class="sq-text-button" :disabled="form.columns.length >= 100" @click="form.columns.push({ name: '' })">＋ 添加字段</button></h3>
            <label class="sql-check sq-all-columns"><input v-model="form.allColumns" type="checkbox"> 查询全部列</label>
            <div v-if="!form.allColumns && form.columns.length" class="sq-field-table"><div class="sq-field-table-head">字段名</div><div v-for="(column, index) in form.columns" :key="index" class="sq-field-row"><span class="sq-field-index">{{ String(index + 1).padStart(2, '0') }}</span><input v-model="column.name" :aria-label="`查询列${index + 1}`" placeholder="column_name"><button :aria-label="`删除查询列${index + 1}`" @click="form.columns.splice(index, 1)">×</button></div></div>
            <p v-else-if="!form.allColumns" class="sq-hint">添加需要返回的列，按列表顺序生成。</p>
          </section>
          <SqlConditions :form="form" />
          <div class="sql-subsection">
            <div class="sql-section-title"><strong><span class="sq-step">04</span> 排序与行数</strong><button class="btn btn-secondary" :disabled="form.orders.length >= 10" @click="form.orders.push({ column: '', direction: 'ASC' })">添加排序</button></div>
            <div v-for="(order, index) in form.orders" :key="index" class="sql-row"><input v-model="order.column" class="input" :aria-label="`排序列${index + 1}`" placeholder="列名"><select v-model="order.direction" class="input" :aria-label="`排序方向${index + 1}`"><option>ASC</option><option>DESC</option></select><button class="btn btn-ghost" :aria-label="`删除排序${index + 1}`" @click="form.orders.splice(index, 1)">删除</button></div>
            <label class="sq-limit">最多返回 <input v-model="form.limit" inputmode="numeric" aria-label="LIMIT行数" placeholder="不限"> 行 <span>留空不限制</span></label>
          </div>
        </template>
        <div v-if="['INSERT', 'UPDATE'].includes(draft.statementType)" class="sql-subsection">
          <div class="sql-section-title"><strong>{{ draft.statementType === 'INSERT' ? '插入字段和值' : '更新赋值' }}</strong><button class="btn btn-secondary" :disabled="form.values.length >= 100" @click="form.values.push({ column: '', value: newValue() })">添加字段</button></div>
          <div v-for="(item, index) in form.values" :key="index" class="sql-row sql-condition"><input v-model="item.column" class="input" :aria-label="`赋值列${index + 1}`" placeholder="列名"><SqlValueInput v-model="item.value" :label="`赋值${index + 1}`" /><button class="btn btn-ghost" :aria-label="`删除赋值${index + 1}`" @click="form.values.splice(index, 1)">删除</button></div>
        </div>
        <SqlConditions v-if="['UPDATE', 'DELETE'].includes(draft.statementType)" :form="form" mandatory />
        <div v-if="draft.statementType === 'CREATE_TABLE'" class="sql-subsection">
          <div class="sql-section-title"><strong><span class="sq-step">02</span> 字段定义</strong><button class="btn btn-secondary" :disabled="form.columns.length >= 100" @click="form.columns.push(newColumn())">添加字段</button></div>
          <div v-for="(column, index) in form.columns" :key="index" class="sql-create-row">
            <label class="field"><span>列名</span><input v-model="column.name" class="input" :aria-label="`建表列${index + 1}`" placeholder="列名"></label>
            <label class="field"><span>数据类型</span><select v-model="column.type" class="input" :aria-label="`字段类型${index + 1}`" @change="changeColumnType(column)"><option v-for="type in availableTypes" :key="type">{{ type }}</option></select></label>
            <label v-if="['CHAR','VARCHAR'].includes(column.type)" class="field"><span>长度</span><input v-model.number="column.length" class="input" type="number" min="1" :aria-label="`字段长度${index + 1}`"></label>
            <template v-if="column.type === 'DECIMAL'"><label class="field"><span>精度</span><input v-model.number="column.precision" class="input" type="number" min="1"></label><label class="field"><span>小数位</span><input v-model.number="column.scale" class="input" type="number" min="0"></label></template>
            <div class="sql-flags"><label class="sql-check"><input v-model="column.nullable" type="checkbox"> 可空</label><label class="sql-check"><input v-model="column.primaryKey" type="checkbox" @change="column.primaryKey && (column.nullable = false)"> 主键</label><label class="sql-check"><input v-model="column.unique" type="checkbox"> 唯一</label><label class="sql-check"><input v-model="column.identity" type="checkbox" :disabled="!['SMALLINT','INTEGER','BIGINT'].includes(column.type)" @change="column.identity && (column.nullable = false)"> {{ draft.dialect === 'MYSQL' ? '自增' : 'Identity' }}</label></div>
            <div class="sql-default"><select class="input" :value="column.defaultValue?.type || ''" :aria-label="`默认值类型${index + 1}`" @change="changeDefault(column, $event)"><option value="">无默认值</option><option value="STRING">字符串默认值</option><option value="NUMBER">数值默认值</option><option value="BOOLEAN">布尔默认值</option><option value="NULL">NULL默认值</option></select><template v-if="column.defaultValue"><select v-if="column.defaultValue.type === 'BOOLEAN'" v-model="column.defaultValue.value" class="input"><option value="true">true</option><option value="false">false</option></select><input v-else-if="column.defaultValue.type !== 'NULL'" v-model="column.defaultValue.value" class="input" :aria-label="`默认值${index + 1}`" placeholder="字面量默认值"></template><button class="btn btn-ghost" :aria-label="`删除建表列${index + 1}`" @click="form.columns.splice(index, 1)">删除字段</button></div>
          </div>
          <label class="field top-gap"><span>表注释（可选）</span><input v-model="form.comment" class="input" placeholder="表用途说明"></label>
          <p class="sql-hint">{{ draft.dialect === 'MYSQL' ? '固定 InnoDB / utf8mb4。时间默认值等行为须按目标数据库版本核对。' : 'Identity 使用 GENERATED BY DEFAULT；表注释单独生成 COMMENT ON TABLE。' }}</p>
        </div>
          </div>
          <footer class="sq-config-footer"><span>修改参数后重新生成</span><button class="sq-primary" :disabled="busy || !capabilities" @click="runGenerate">{{ generating ? '生成中…' : '生成 SQL →' }}</button></footer>
        </template>
        <template v-else>
          <div class="sq-format-settings"><label>关键字<select v-model="draft.keywordCase"><option value="UPPER">大写</option><option value="LOWER">小写</option></select></label><label>缩进<select v-model.number="draft.indentSize"><option :value="2">2 空格</option><option :value="4">4 空格</option></select></label></div>
          <div class="sq-format-input"><div class="sq-input-title"><span>原始 SQL</span><div><button class="sq-text-button" @click="fileInput?.click()">导入 .sql</button><button class="sq-text-button" :disabled="!draft.source" @click="copy(draft.source)">复制输入</button></div></div><input ref="fileInput" type="file" accept=".sql,text/plain" hidden @change="selectFile"><textarea v-model="draft.source" :style="{ tabSize: draft.indentSize }" maxlength="262144" spellcheck="false" aria-label="SQL输入" placeholder="粘贴 SQL 或导入 UTF-8 .sql 文件…"></textarea><span class="sq-input-count">{{ sourceBytes.toLocaleString() }} / 262,144 字节</span></div>
          <footer class="sq-config-footer"><span>保留字面量、名称与注释</span><button class="sq-primary" :disabled="busy || !draft.source.trim() || !capabilities" @click="runFormat">{{ formatting ? '格式化中…' : '格式化 SQL →' }}</button></footer>
        </template>
      </section>
      <section class="sq-editor" :aria-label="isGenerate ? 'SQL生成预览' : '格式化预览'">
        <div class="sq-editor-top"><div class="sq-file-tab"><span class="sq-file-icon">SQL</span>{{ isGenerate ? 'query.sql' : 'formatted.sql' }}<span class="sq-file-dot"></span></div><div class="sq-editor-actions"><button :disabled="!activeResult" @click="download(sqlText, isGenerate ? 'generated' : 'formatted')">下载 .sql</button><button :disabled="!activeResult" @click="copy(sqlText)">复制 SQL ⧉</button></div></div>
        <div class="sq-editor-label"><span>{{ isGenerate ? '语句预览' : '排版结果' }}</span><button v-if="isGenerate" class="sq-editor-link" :disabled="!generated" @click="sendToFormatter()">送入格式化 ↗</button><button v-else class="sq-editor-link" :disabled="!formatted" @click="formatted = null">清空结果</button></div>
        <SqlCodePreview v-if="sqlText" :sql="sqlText" :indent-size="isGenerate ? 4 : draft.indentSize" :label="isGenerate ? 'SQL生成结果' : '格式化结果'" />
        <div v-else class="sq-editor-empty"><span aria-hidden="true">{{ isGenerate ? '⌘' : '≡' }}</span><h3>{{ busy ? '正在处理 SQL…' : isGenerate ? '你的 SQL，从这里开始' : '让 SQL 更易读' }}</h3><p>{{ isGenerate ? '填写左侧参数，点击“生成 SQL”查看结果。' : '粘贴或导入 SQL，点击“格式化 SQL”查看结果。' }}</p></div>
        <div class="sq-editor-note"><span aria-hidden="true">ⓘ</span><div><p v-if="!activeResult?.warnings?.length">仅处理 SQL 文本，不连接或执行数据库。{{ isGenerate ? '生成后请核对方言和参数。' : '不支持或无法保真的语法将明确拒绝，保留原输入。' }}</p><p v-for="warning in activeResult?.warnings || []" :key="warning">{{ warning }}</p></div></div>
        <footer class="sq-editor-footer"><span>UTF-8<span class="sq-footer-divider">/</span>{{ draft.dialect === 'MYSQL' ? 'MySQL' : 'PostgreSQL' }}</span><span>{{ lineCount }} 行<span class="sq-footer-divider">/</span>{{ activeResult?.statementCount || 0 }} 条语句<span class="sq-footer-divider">/</span>{{ sqlText.length.toLocaleString() }} 字符</span></footer>
      </section>
    </div>
    <div v-if="error" class="sq-message error" role="alert">{{ error.message }}<small v-if="error.code">{{ error.code }}<template v-if="error.requestId"> · {{ error.requestId }}</template></small></div>
    <p v-if="notice" class="sq-message" role="status">{{ notice }}</p>
  </div>
</template>

<style scoped>

.sql-workspace{--sq-green:#087f74;color:#243b44;font-size:13px;max-width:1700px;margin:0 auto}
.sql-workspace button,.sql-workspace select,.sql-workspace input{font:inherit}.sql-workspace button{transition:background .15s,color .15s}.sql-workspace button:disabled{opacity:.45;cursor:default}.sql-workspace :deep(button:focus-visible),.sql-workspace :deep(input:focus-visible),.sql-workspace :deep(select:focus-visible),.sql-workspace :deep(textarea:focus-visible){outline:2px solid #087f74;outline-offset:2px}
.sq-command{display:flex;align-items:center;justify-content:space-between;flex-wrap:wrap;gap:12px;margin-bottom:18px}.sq-switch{display:flex;background:#e8eeef;border:1px solid #dce4e5;border-radius:9px;padding:4px;gap:3px}.sq-switch button{display:flex;align-items:center;gap:9px;border:0;border-radius:6px;color:#6c7c83;padding:10px 20px;background:transparent;font-weight:650}.sq-switch button.active{background:#fff;color:#087f74;box-shadow:0 1px 4px #183e4612}.sq-switch button>span{font-size:18px;line-height:1}
.sq-command-right{display:flex;gap:14px;align-items:center;flex-wrap:wrap;row-gap:8px}.sq-command-right label{display:flex;align-items:center;gap:9px;color:#6a7c83;font-size:11px}.sq-command-right select{border:1px solid #d8e1e2;background:white;border-radius:6px;padding:8px 10px;color:#243b44;max-width:220px}.sq-command-hint{font-size:11px;color:#819498}
.sq-service{display:flex;align-items:center;justify-content:space-between;background:#fffaf0;border:1px solid #eadcba;border-radius:6px;padding:7px 14px;margin-bottom:12px;font-size:12px;color:#856b38}
.sq-workbench{display:grid;grid-template-columns:minmax(360px,.9fr) minmax(0,1.1fr);min-height:620px;height:calc(100dvh - 252px);max-height:950px;border:1px solid #d5dfe1;border-radius:12px;overflow:hidden;box-shadow:0 6px 22px #243b4405}.sq-config{display:flex;flex-direction:column;background:#fff;min-height:0;min-width:0}.sq-config-heading{display:flex;align-items:center;justify-content:space-between;gap:10px;padding:23px 24px 19px}.sq-eyebrow{font:10px/1.3 ui-monospace,monospace;letter-spacing:1.7px;color:#819398}.sq-config-heading h2{font-size:19px;letter-spacing:-.3px;margin:5px 0 0;font-weight:650}
.sq-text-button{border:0;background:transparent;color:#54807b;padding:5px;font-size:12px!important;white-space:nowrap}.sq-text-button:hover{color:#066358;background:#edf7f4;border-radius:4px}.sq-statement-tabs{display:flex;gap:18px;padding:0 24px;border-bottom:1px solid #e8eded}.sq-statement-tabs button{padding:0 0 13px;border:0;border-bottom:2px solid transparent;background:none;font:600 11px ui-monospace,monospace;color:#859297}.sq-statement-tabs button.active{color:#087f74;border-bottom-color:#087f74}
.sq-config-scroll{flex:1;min-height:0;overflow:auto;padding:0 24px 20px;scrollbar-width:thin}.sq-section{padding-top:23px}.sq-section h3{display:flex;gap:9px;align-items:center;font-size:13px;font-weight:650;margin:0 0 14px}.sq-step{font:10px ui-monospace,monospace;color:#93a7ab;background:#f1f5f5;padding:4px 5px;border-radius:4px;margin-right:4px}.sq-section-note{margin-left:auto;font-size:10px;color:#82969a;font-weight:400}
.sq-source-fields{display:grid;grid-template-columns:1fr 1.2fr;gap:12px}.sq-source-fields label{font-size:11px;color:#74878d}.sq-source-fields label>span{color:#acb7ba;font-size:10px;margin-left:3px}.sq-source-fields input{display:block;width:100%;margin-top:7px;padding:9px 11px;background:#fafcfc;border:1px solid #dce5e6;border-radius:6px;color:#233f46;font-size:12px}.sq-source-fields input::placeholder{color:#a4b2b6}.sq-hint{color:#8a9ca1;font-size:10px;margin:9px 0 0}.sq-all-columns{margin-top:22px}
.sq-config-footer{display:flex;align-items:center;justify-content:space-between;flex-wrap:wrap;gap:12px;padding:12px 24px;border-top:1px solid #e9eeee;font-size:10px;color:#91a2a6;flex:none;background:#fcfdfd}.sq-primary{padding:10px 14px;background:#087f74;color:white;border:0;border-radius:6px;font-size:12px!important;white-space:nowrap}.sq-primary:hover{background:#066b62}
.sq-editor{display:flex;flex-direction:column;min-width:0;min-height:0;background:#14252c;color:#bdd0d6}.sq-editor-top{display:flex;flex:none;align-items:stretch;justify-content:space-between;gap:10px;background:#112128;border-bottom:1px solid #2a3b42;min-height:49px}.sq-file-tab{display:flex;flex-shrink:0;align-items:center;gap:10px;padding:0 20px;border-right:1px solid #2a3b42;border-top:2px solid #50b4a4;background:#14252c;font:11px ui-monospace,monospace;color:#b5c9ce}.sq-file-icon{font-size:8px;color:#7bc5ae;border:1px solid #44685f;padding:1px 3px;border-radius:3px}.sq-file-dot{width:5px;height:5px;border-radius:50%;background:#58787f;margin-left:8px}
.sq-editor-actions{display:flex;align-items:center;gap:7px;flex-wrap:wrap;justify-content:flex-end;padding:8px 16px 8px 0}.sq-editor-actions button{border:1px solid #355057;color:#b3cbd1;background:#20363e;border-radius:5px;padding:5px 10px;font-size:11px;white-space:nowrap}.sq-editor-actions button:hover{background:#2c4851}.sq-editor-label{display:flex;flex:none;justify-content:space-between;align-items:center;padding:15px 24px;min-height:50px;color:#7b97a0;font-size:10px;letter-spacing:.5px}.sq-editor-link{border:0;background:none;padding:2px 0;color:#7fb7b2;font-size:11px}.sq-editor-link:hover{color:#b0dfd7}
.sq-editor-empty{flex:1;display:flex;align-items:center;justify-content:center;flex-direction:column;min-height:180px}.sq-editor-empty>span{font-size:38px;color:#34545e}.sq-editor-empty h3{font-size:16px;color:#8cabb6;font-weight:500;margin:14px 0 4px}.sq-editor-empty p{font-size:11px;color:#7999a5;padding:0 22px;text-align:center}.sq-editor-note{display:flex;flex:none;gap:8px;padding:13px 20px;margin:0 20px 16px;border:1px solid #2a414a;border-radius:6px;background:#192e36;color:#8eafb7;font-size:10px}.sq-editor-note>span{color:#6a999b}.sq-editor-note p{margin:0;line-height:1.6}.sq-editor-note p+p{margin-top:5px}
.sq-editor-footer{display:flex;flex:none;flex-wrap:wrap;justify-content:space-between;gap:10px;padding:11px 20px;border-top:1px solid #294048;font:10px ui-monospace,monospace;color:#7b9eab}.sq-footer-divider{color:#507481;margin:0 9px}
.sq-format-settings{display:flex;gap:15px;border-bottom:1px solid #e8eeee;padding:0 24px 20px}.sq-format-settings label{display:flex;align-items:center;gap:8px;color:#91a0a5;font-size:11px}.sq-format-settings select{border:1px solid #dbe4e5;border-radius:5px;background:#f8fafa;padding:7px 12px;color:#526d75;font-size:12px}.sq-format-input{display:flex;flex-direction:column;flex:1;min-height:0;padding:22px 24px 15px}.sq-input-title{display:flex;justify-content:space-between;align-items:center;gap:10px;font-size:11px;color:#82989d;margin-bottom:10px}.sq-format-input textarea{width:100%;flex:1;min-height:220px;padding:16px;border:1px solid #e0e8e8;border-radius:6px;resize:none;background:#f8fafa;color:#49616a;font:12px/2 ui-monospace,monospace;white-space:pre;overflow:auto}.sq-input-count{text-align:right;font-size:10px;color:#8e9fa5;margin-top:8px}
.sq-message{margin:12px 0 0;padding:10px 14px;border:1px solid #cbdfd9;border-radius:6px;font-size:12px;color:#087f74;background:#f6fbf9}.sq-message.error{color:#ad4444;border-color:#e9cdcd;background:#fff9f9}.sq-message small{display:block;margin-top:4px;overflow-wrap:anywhere}
.sq-section h3>.sq-text-button{margin-left:auto}.sq-count{background:#e9f4f1;color:#428479;font-size:10px;padding:1px 6px;border-radius:4px;font-weight:500}.sq-all-columns{margin:0 0 12px}.sq-field-table{border:1px solid #e0e7e8;border-radius:7px;overflow:hidden}.sq-field-table-head{padding:7px 35px;background:#f5f8f8;font-size:10px;color:#8b9c9f}.sq-field-row{display:grid;grid-template-columns:18px minmax(0,1fr) 20px;align-items:center;gap:8px;min-height:40px;padding:4px 10px;border-top:1px solid #edf1f1}.sq-field-index{font:10px ui-monospace,monospace;color:#a1b1b5}.sq-field-row input{border:0;min-width:0;width:100%;color:#38535c;background:transparent;font:12px ui-monospace,monospace;padding:5px 0}.sq-field-row button{border:0;background:none;color:#a4b3b6;font-size:17px}.sq-field-row button:hover{color:#b04545}.sq-limit{display:flex;align-items:center;gap:9px;font-size:11px;color:#839599;margin-top:15px}.sq-limit input{width:66px;padding:5px 8px;border:1px solid #dce5e6;border-radius:4px;color:#435e64;background:#fafcfc;font:12px ui-monospace,monospace}.sq-limit>span{margin-left:auto;color:#8f9fa4;font-size:10px}
/* 控件规则限定在SQL工作台内，兼顾子组件，避免影响其他工具。 */
.sql-workspace :deep(.input){min-height:34px;padding:7px 8px;font-size:12px;border-color:#dce5e6;color:#425f67;background:#fafcfc;border-radius:5px}.sql-workspace :deep(.btn){min-height:30px;padding:5px 7px;font-size:11px;font-weight:500}.sql-workspace :deep(.btn-secondary){background:#f6faf8;border-color:#d3e5dd;color:#518276}.sql-workspace :deep(.btn-ghost){color:#83999d}.sql-workspace :deep(.sql-check){display:inline-flex;gap:6px;align-items:center;white-space:nowrap;font-size:11px;color:#6f858c}.sql-workspace :deep(.sql-check input){accent-color:#087f74}.sql-workspace :deep(.sql-subsection){padding-top:23px}.sql-workspace :deep(.sql-section-title){display:flex;flex-wrap:wrap;align-items:center;gap:8px;justify-content:space-between;margin-bottom:12px}.sql-workspace :deep(.sql-section-title strong){font-size:13px;font-weight:650}.sql-workspace :deep(.sql-row){display:flex;gap:7px;align-items:center;margin-bottom:8px}.sql-workspace :deep(.sql-row>.input){min-width:90px;flex:1}.sql-workspace :deep(.sql-condition){flex-wrap:wrap;border:1px solid #e2eaea;border-radius:6px;padding:9px;background:#fcfdfd}.sql-workspace :deep(.sql-junction){width:70px}.sql-workspace :deep(.sql-hint){color:#8a9ca1;font-size:10px}.sql-workspace :deep(.sql-null){font:11px ui-monospace,monospace;color:#8a9ca1}
.sql-workspace :deep(.sql-create-row){display:flex;flex-wrap:wrap;gap:9px;margin:12px 0;padding:12px 10px;border:1px solid #e0e7e8;border-radius:7px}.sql-workspace :deep(.sql-create-row>.field){flex:1;min-width:100px}.sql-workspace :deep(.field span){font-size:10px;color:#83979c}.sql-workspace :deep(.sql-flags){display:flex;flex-wrap:wrap;gap:10px;width:100%;margin-top:2px}.sql-workspace :deep(.sql-default){display:flex;flex-wrap:wrap;align-items:center;gap:6px;width:100%}.sql-workspace :deep(.sql-default>.input){flex:1;min-width:120px}.sql-workspace :deep(.sql-default .btn){margin-left:auto}
@media(min-width:1700px){.sq-workbench{grid-template-columns:minmax(400px,.78fr) minmax(0,1.22fr)}.sq-config-scroll,.sq-config-heading{padding-left:30px;padding-right:30px}.sq-statement-tabs{padding-left:30px;padding-right:30px}}
@media(max-width:1200px){.sq-command-right{gap:10px}.sq-statement-tabs{gap:13px}.sq-workbench{grid-template-columns:minmax(330px,.95fr) minmax(0,1.05fr)}.sq-config-scroll,.sq-config-heading{padding-left:18px;padding-right:18px}.sq-statement-tabs{padding-inline:18px}}
@media(max-width:1050px){.sq-workbench{grid-template-columns:1fr;height:auto;min-height:0;max-height:none}.sq-config-scroll{max-height:650px}.sq-editor{height:520px}.sq-format-input textarea{min-height:280px}}
@media(max-width:620px){.sq-command-right{width:100%;justify-content:flex-start}.sq-command-right label{flex-wrap:wrap;gap:5px}.sq-command-right select{max-width:180px}.sq-switch{width:100%}.sq-switch button{flex:1;justify-content:center;padding:9px 12px}.sq-workbench{border-radius:8px}.sq-source-fields{grid-template-columns:1fr}.sq-config-footer{padding-inline:18px}.sq-editor-top{flex-wrap:wrap}.sq-editor-actions{padding:8px 12px}.sq-file-tab{padding:10px 12px}.sq-editor-label{padding-inline:18px}.sq-config-heading{padding-top:18px}.sq-input-title{flex-wrap:wrap}.sql-workspace :deep(.sql-row>.input){min-width:calc(50% - 8px)}}

</style>
