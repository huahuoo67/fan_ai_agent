# Fan AI Agent Compose deployment

The deployed stack consists of a Vue/Nginx frontend, a Spring Boot backend,
the image-search MCP service, and PostgreSQL with PgVector. The frontend is the
only service published to the host, on port 8123; it proxies `/api/` to the
backend. PostgreSQL data persists in the `pgdata` Docker volume.

## Build and deploy

Run `build-release.ps1` from PowerShell on a development machine with JDK 21,
Maven, Node.js, and npm. The script builds artifacts without compiling or
running tests. It stages only runtime Java sources and safe resources; the
local `src/main/resources/application.yml`, tracked MCP credential file,
test sources, and demo sources are absent from the backend JAR.

Run `prepare-local-env.ps1` once to create a private `deploy/.env` from local
DashScope and Search credentials and a random database and site password.
Set `PEXELS_API_KEY` there if image search is required. Keep `.env` private.
The frontend HTTP Basic Auth user is `fan`; its password is the value of
`FRONTEND_PASSWORD` in `deploy/.env`.

Transfer `compose.yaml`, `postgres/`, `backend/`, `image-mcp/`, `frontend/`,
and `.env` to `/opt/fan-ai-agent` on the server. Then run:

```sh
cd /opt/fan-ai-agent
chmod 600 .env
docker compose config -q
docker compose build
docker compose up -d
docker compose ps
```

Open TCP 8123 in the host firewall and cloud security group. Access the site
at `http://SERVER_IP:8123/` and sign in with the generated site password.
HTTP Basic Auth over plain HTTP is temporary; add a domain and HTTPS before
regular public use.

## Runtime notes

- On first boot, the backend embeds the bundled Markdown knowledge base into
  PgVector; later boots skip unchanged chunks.
- Image MCP starts without a Pexels key, but image searches require that key.
- Chat memory remains process-local and is cleared on backend restart.
- The Manus terminal tool is excluded from registered tools.
- Back up the `pgdata` volume before upgrades or server migration.
