{{- define "materialflow.labels" -}}
app.kubernetes.io/part-of: materialflow
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version | replace "+" "_" }}
{{- end }}

{{/* Node placement shared by every pod of the chart. Argument: (dict "root" $ "name" <app label>). */}}
{{- define "materialflow.placement" -}}
{{- $global := .root.Values.global -}}
{{- with $global.nodeSelector }}
nodeSelector:
  {{- toYaml . | nindent 2 }}
{{- end }}
{{- with $global.tolerations }}
tolerations:
  {{- toYaml . | nindent 2 }}
{{- end }}
{{- if $global.spreadAcrossNodes }}
# Two replicas of a component prefer different nodes, so one node drain or VM failure does not
# take a whole component down. "ScheduleAnyway" keeps a single-node cluster schedulable.
topologySpreadConstraints:
  - maxSkew: 1
    topologyKey: kubernetes.io/hostname
    whenUnsatisfiable: ScheduleAnyway
    labelSelector:
      matchLabels: {app.kubernetes.io/name: {{ .name }}}
{{- end }}
{{- end }}

{{/* Secrets Store CSI volume: mounting it is what makes the driver sync the Kubernetes Secret. */}}
{{- define "materialflow.secretsStoreVolume" -}}
{{- if .Values.secretsStore.enabled }}
- name: secrets-store
  csi:
    driver: secrets-store.csi.k8s.io
    readOnly: true
    volumeAttributes:
      secretProviderClass: {{ .Values.secretsStore.secretProviderClass | quote }}
{{- end }}
{{- end }}

{{- define "materialflow.secretsStoreMount" -}}
{{- if .Values.secretsStore.enabled }}
- {name: secrets-store, mountPath: /mnt/secrets-store, readOnly: true}
{{- end }}
{{- end }}

{{/* Lets the endpoint removal reach every proxy before the container gets SIGTERM. */}}
{{- define "materialflow.preStop" -}}
{{- if gt (int .Values.global.preStopSleepSeconds) 0 }}
lifecycle:
  preStop:
    sleep: {seconds: {{ .Values.global.preStopSleepSeconds }}}
{{- end }}
{{- end }}
