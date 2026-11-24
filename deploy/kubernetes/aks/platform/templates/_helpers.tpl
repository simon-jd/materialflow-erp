{{- define "platform.labels" -}}
app.kubernetes.io/part-of: materialflow
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version | replace "+" "_" }}
{{- end }}

{{- define "platform.host" -}}
{{ required "platform.publicHost is required" .Values.platform.publicHost }}
{{- end }}

{{- define "platform.mirror" -}}
{{ required "platform.acrLoginServer is required" .root.Values.platform.acrLoginServer }}/{{ .image }}
{{- end }}

{{- define "platform.podSecurity" -}}
runAsNonRoot: true
seccompProfile: {type: RuntimeDefault}
{{- end }}

{{- define "platform.containerSecurity" -}}
allowPrivilegeEscalation: false
readOnlyRootFilesystem: true
capabilities: {drop: [ALL]}
{{- end }}

{{- define "platform.secretsStoreVolume" -}}
- name: secrets-store
  csi:
    driver: secrets-store.csi.k8s.io
    readOnly: true
    volumeAttributes:
      secretProviderClass: {{ .Values.secretProviderClass | quote }}
{{- end }}
