import { createServer, type IncomingMessage, type ServerResponse } from 'node:http';
import {
  buildDwdDraft,
  buildDwsDraft,
  checkDuplicate,
  clusterLogs,
  evaluateAccess,
  generateSql,
  routeQuery,
  scoreCluster,
} from '@dw-ai/engine';

const PORT = Number(process.env.RULES_PORT || 7080);

async function readJson(req: IncomingMessage): Promise<Record<string, unknown>> {
  const chunks: Buffer[] = [];
  for await (const c of req) chunks.push(c as Buffer);
  const raw = Buffer.concat(chunks).toString('utf8') || '{}';
  return JSON.parse(raw) as Record<string, unknown>;
}

function send(res: ServerResponse, code: number, body: unknown) {
  res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8', 'Access-Control-Allow-Origin': '*' });
  res.end(JSON.stringify(body));
}

const server = createServer(async (req, res) => {
  if (req.method === 'OPTIONS') {
    res.writeHead(204, {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'POST, GET, OPTIONS',
      'Access-Control-Allow-Headers': 'Content-Type, Authorization',
    });
    res.end();
    return;
  }
  try {
    const path = (req.url || '/').split('?')[0]
      .replace(/^\/api\/v1\/rules\//, '/rules/')
      .replace(/^\/api\/health$/, '/health')
      .replace(/^\/api\/runtime$/, '/health');
    if (req.method === 'GET' && (path === '/health' || path === '/api/v1/runtime')) {
      send(res, 200, { ok: true, service: 'dw-ai-rules', product: 'rules', runMode: 'standalone' });
      return;
    }
    if (req.method !== 'POST') {
      send(res, 404, { error: 'not found' });
      return;
    }
    const body = await readJson(req);
    if (path === '/rules/access') {
      send(res, 200, evaluateAccess(body as Parameters<typeof evaluateAccess>[0]));
      return;
    }
    if (path === '/rules/sql') {
      const { cfg, dialect, recs, tables } = body as {
        cfg: Parameters<typeof generateSql>[0];
        dialect?: string;
        recs?: Parameters<typeof routeQuery>[1];
        tables?: Parameters<typeof routeQuery>[2];
      };
      if (recs && tables) send(res, 200, routeQuery(cfg, recs, tables));
      else send(res, 200, generateSql(cfg, dialect));
      return;
    }
    if (path === '/rules/dwd-draft') {
      send(res, 200, buildDwdDraft(body as Parameters<typeof buildDwdDraft>[0]));
      return;
    }
    if (path === '/rules/dws-draft') {
      send(res, 200, buildDwsDraft(body as Parameters<typeof buildDwsDraft>[0]));
      return;
    }
    if (path === '/rules/metric-dup') {
      const { incoming, existing } = body as { incoming: Parameters<typeof checkDuplicate>[0]; existing: Parameters<typeof checkDuplicate>[1] };
      send(res, 200, checkDuplicate(incoming, existing));
      return;
    }
    if (path === '/rules/cluster') {
      const { logs, tables } = body as { logs: Parameters<typeof clusterLogs>[0]; tables: Parameters<typeof scoreCluster>[1] };
      const clusters = clusterLogs(logs);
      send(res, 200, { clusters, recs: clusters.map((c) => scoreCluster(c, tables)) });
      return;
    }
    send(res, 404, { error: 'not found' });
  } catch (e) {
    send(res, 400, { error: e instanceof Error ? e.message : String(e) });
  }
});

server.listen(PORT, () => {
  console.log(`dw-ai rules service :${PORT}`);
});
