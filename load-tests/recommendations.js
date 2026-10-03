// k6 load test for GET /v1/recommendations with the serving SLOs as thresholds.
//   docker run --rm -i -e BASE_URL=http://host.docker.internal:8080 -e RATE=200 grafana/k6 run - < load-tests/recommendations.js
// Users sim_u000001..sim_u005000 exist after `simulator simulate`; others exercise cold start.
import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const KEY = __ENV.API_KEY || 'dev-key';
const COUNTRIES = ['US', 'GB', 'DE', 'IN', 'BR', 'KR'];
// Weighted domain mix (song-heavy, like the simulator). Override with DOMAINS=song,book.
const DOMAINS = (__ENV.DOMAINS || 'song,song,song,video,video,post,book').split(',');
const CONTEXT = { song: 'next_track', video: 'related', post: 'feed', book: 'home' };

// RAMP=100:1m,400:1m,... runs a capacity ramp (ramping-arrival-rate, req/s:duration stages)
// instead of a constant rate; for capacity numbers prefer constant-rate steps (docs/load-test-results.md).
const RAMP = __ENV.RAMP;
const scenario = RAMP
  ? {
      executor: 'ramping-arrival-rate',
      startRate: Number(RAMP.split(',')[0].split(':')[0]),
      timeUnit: '1s',
      stages: RAMP.split(',').map((s) => ({ target: Number(s.split(':')[0]), duration: s.split(':')[1] })),
      preAllocatedVUs: 200,
      maxVUs: 2000,
    }
  : {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.RATE || 200),
      timeUnit: '1s',
      duration: __ENV.DURATION || '2m',
      preAllocatedVUs: 100,
      maxVUs: 1000,
    };

export const options = {
  scenarios: { recommendations: scenario },
  thresholds: {
    'http_req_duration{name:recommendations}': ['p(50)<30', 'p(99)<100'],
    http_req_failed: ['rate<0.01'],
    checks: ['rate>0.99'],
  },
};

export default function () {
  const user = `sim_u${String(1 + Math.floor(Math.random() * 6000)).padStart(6, '0')}`;
  const country = COUNTRIES[Math.floor(Math.random() * COUNTRIES.length)];
  const domain = DOMAINS[Math.floor(Math.random() * DOMAINS.length)];
  const res = http.get(
    `${BASE}/v1/recommendations?userId=${user}&domain=${domain}&context=${CONTEXT[domain]}&limit=20&country=${country}`,
    { headers: { 'X-Api-Key': KEY }, tags: { name: 'recommendations' } },
  );
  check(res, {
    'status 200': (r) => r.status === 200,
    'has items': (r) => r.json('items').length > 0,
  });
}
