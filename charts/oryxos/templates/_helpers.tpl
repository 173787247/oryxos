{{/* 039：命名与标签惯例 */}}
{{- define "oryxos.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "oryxos.fullname" -}}
{{- printf "%s" .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "oryxos.labels" -}}
app.kubernetes.io/name: {{ include "oryxos.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Values.image.tag | default .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}

{{- define "oryxos.selectorLabels" -}}
app.kubernetes.io/name: {{ include "oryxos.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{/* 数据库 Secret 名：existingSecret 优先；否则 chart 渲染的 <fullname>-db（两路都为空时报错） */}}
{{- define "oryxos.dbSecretName" -}}
{{- if .Values.database.existingSecret -}}
{{- .Values.database.existingSecret -}}
{{- else if .Values.database.url -}}
{{- printf "%s-db" (include "oryxos.fullname" .) -}}
{{- else -}}
{{- fail "缺少数据库配置：请设置 database.existingSecret（推荐，含 SPRING_DATASOURCE_URL/USERNAME/PASSWORD 三键）或 database.url/username/password。参见 docs/K8sDeployGuide.md" -}}
{{- end -}}
{{- end -}}

{{/* 主密钥 Secret 名：existingSecret 优先；否则 chart 渲染的 <fullname>-master-key */}}
{{- define "oryxos.masterKeySecretName" -}}
{{- if .Values.masterKey.existingSecret -}}
{{- .Values.masterKey.existingSecret -}}
{{- else if .Values.masterKey.value -}}
{{- printf "%s-master-key" (include "oryxos.fullname" .) -}}
{{- else -}}
{{- fail "缺少主密钥配置：请设置 masterKey.existingSecret（推荐，含 ORYXOS_MASTER_KEY 键）或 masterKey.value（openssl rand -base64 32 生成）。参见 docs/K8sDeployGuide.md" -}}
{{- end -}}
{{- end -}}
