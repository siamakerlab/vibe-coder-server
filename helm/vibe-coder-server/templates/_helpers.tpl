{{- define "vibe.fullname" -}}
{{- printf "%s" .Release.Name -}}
{{- end -}}

{{- define "vibe.labels" -}}
app.kubernetes.io/name: vibe-coder-server
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" }}
{{- end -}}

{{- define "vibe.selectorLabels" -}}
app.kubernetes.io/name: vibe-coder-server
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "vibe.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{ default (include "vibe.fullname" .) .Values.serviceAccount.name }}
{{- else -}}
{{ default "default" .Values.serviceAccount.name }}
{{- end -}}
{{- end -}}
