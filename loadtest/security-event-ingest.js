import http from 'k6/http';
import { check, sleep } from 'k6';

const baseUrl = __ENV.BASE_URL || 'http://localhost:8081';
const serviceKey = __ENV.SECURITY_EVENT_INGEST_API_KEY;

if (!serviceKey) {
  throw new Error('SECURITY_EVENT_INGEST_API_KEY 환경 변수가 필요합니다.');
}

export const options = {
  scenarios: {
    ingest: {
      executor: 'ramping-arrival-rate',
      startRate: 10,
      timeUnit: '1s',
      preAllocatedVUs: 20,
      maxVUs: 100,
      stages: [
        { target: 50, duration: '30s' },
        { target: 50, duration: '60s' },
        { target: 0, duration: '15s' },
      ],
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<500'],
  },
};

function uuid() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (character) => {
    const random = Math.floor(Math.random() * 16);
    const value = character === 'x' ? random : (random & 0x3) | 0x8;
    return value.toString(16);
  });
}

export default function () {
  const now = new Date().toISOString();
  const actorId = ((__VU * 100000) + __ITER + 1);
  const response = http.post(
    `${baseUrl}/api/internal/security-events:batch`,
    JSON.stringify({
      events: [{
        eventId: uuid(),
        eventType: 'LOGIN_FAILED',
        actorId,
        sourceIp: `198.51.100.${(actorId % 250) + 1}`,
        outcome: 'FAILURE',
        occurredAt: now,
        attributes: { reasonCode: 'LOAD_TEST' },
      }],
    }),
    { headers: { 'Content-Type': 'application/json', 'X-SecureFlow-Ingest-Key': serviceKey } },
  );
  check(response, { 'ingest accepted': (result) => result.status === 202 });
  sleep(0.1);
}
