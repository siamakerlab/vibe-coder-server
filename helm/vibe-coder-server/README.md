# vibe-coder-server Helm chart

Self-hostable Android development server orchestrating Claude Code, Gradle,
and Git — packaged for Kubernetes.

> Single-tenant by design (see [CLAUDE.md §1](../../CLAUDE.md)). This chart
> targets the same single-user installation as `docker compose`, not a
> clustered SaaS. `replicas` is locked at 1 and workspace + pg data live on
> ReadWriteOnce PVCs.

## Quick install

```bash
helm install vibe ./helm/vibe-coder-server \
  --set postgres.password=$(openssl rand -hex 24)

# Get logs
kubectl logs deploy/vibe -f

# Port-forward (no ingress)
kubectl port-forward svc/vibe 17880:17880
```

Open <http://localhost:17880> and finish setup (`/setup` admin password).

## External ingress + TLS

```bash
helm install vibe ./helm/vibe-coder-server \
  --set postgres.password=$(openssl rand -hex 24) \
  --set ingress.enabled=true \
  --set ingress.host=vibe.example.com \
  --set ingress.tls.enabled=true \
  --set ingress.tls.secretName=vibe-tls \
  --set env.VIBECODER_CORS_ALLOWED_HOSTS=https://vibe.example.com
```

## External PostgreSQL

```bash
helm install vibe ./helm/vibe-coder-server \
  --set postgres.enabled=false \
  --set env.VIBECODER_DB_HOST=my-pg.example.com \
  --set env.VIBECODER_DB_PORT=5432 \
  --set env.VIBECODER_DB_NAME=vibecoder \
  --set env.VIBECODER_DB_USER=vibecoder \
  --set-string secretEnv.VIBECODER_DB_PASSWORD=$DB_PASSWORD
```

## Limitations

- **Single replica only.** workspace + agent-sessions live on RWO PVC.
- **No HA postgres.** Sidecar is single-instance; use external managed PG
  for prod.
- **`:full` image (emulator + noVNC) not yet supported.** Needs KVM
  passthrough + privileged container — out of scope for the base chart.
  See [Wiki/Emulator](https://github.com/siamakerlab/vibe-coder-server/wiki/Emulator).

## Values reference

See [values.yaml](values.yaml) — every key is documented inline.
