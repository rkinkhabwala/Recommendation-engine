// k6 load test for POST /v1/events (batches of 10 events).
//   docker run --rm -i -e BASE_URL=http://host.docker.internal:8081 -e RATE=100 grafana/k6 run - < load-tests/ingestion.js
import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE_URL || 'http://localhost:8081';
const KEY = __ENV.API_KEY || 'dev-key';

export const options = {
  scenarios: {
    events: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.RATE || 100), // batches/s -> 10x events/s
      timeUnit: '1s',
      duration: __ENV.DURATION || '2m',
      preAllocatedVUs: 50,
      maxVUs: 500,
    },
  },
  thresholds: { http_req_failed: ['rate<0.01'], 'http_req_duration': ['p(99)<250'] },
};

function uuidv7() {
  const ms = Date.now().toString(16).padStart(12, '0');
  const rnd = () => Math.floor(Math.random() * 0x10000).toString(16).padStart(4, '0');
  return `${ms.slice(0, 8)}-${ms.slice(8, 12)}-7${rnd().slice(1)}-${(8 + Math.floor(Math.random() * 4)).toString(16)}${rnd().slice(1)}-${rnd()}${rnd()}${rnd()}`;
}

export default function () {
  const user = `load_u${Math.floor(Math.random() * 100000)}`;
  const events = [];
  for (let i = 0; i < 10; i++) {
    const item = `s_${String(1 + Math.floor(Math.random() * 50000)).padStart(6, '0')}`;
    events.push({
      eventId: uuidv7(), userId: user, itemId: item, domain: 'song',
      eventType: i % 3 === 0 ? 'skip' : 'play_end', value: i % 3 === 0 ? 4 : 190,
      eventTs: new Date().toISOString(), sessionId: `load-${user}`,
      context: { device: 'web', country: 'US' }, media: { durationMs: 200000 },
    });
  }
  const res = http.post(`${BASE}/v1/events`, JSON.stringify({ events }), {
    headers: { 'X-Api-Key': KEY, 'Content-Type': 'application/json' },
  });
  check(res, { accepted: (r) => r.status === 202 && r.json('accepted') === 10 });
}
