import http from 'k6/http';
import { check, sleep } from 'k6';

const baseUrl = __ENV.BASE_URL || 'http://localhost:8081';
const authorization = __ENV.AUTHORIZATION;

if (!authorization) {
  throw new Error('AUTHORIZATION 환경 변수가 필요합니다. 예: Basic <base64(email:password)>');
}

export const options = {
  scenarios: {
    search: {
      executor: 'constant-arrival-rate',
      rate: 30,
      timeUnit: '1s',
      duration: '90s',
      preAllocatedVUs: 20,
      maxVUs: 100,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<300', 'p(99)<700'],
  },
};

export default function () {
  const to = new Date();
  const from = new Date(to.getTime() - (24 * 60 * 60 * 1000));
  const url = `${baseUrl}/api/security-events?from=${encodeURIComponent(from.toISOString())}`
    + `&to=${encodeURIComponent(to.toISOString())}&eventType=LOGIN_FAILED&size=50`;
  const response = http.get(url, { headers: { Authorization: authorization } });
  check(response, { 'search ok': (result) => result.status === 200 });
  sleep(0.1);
}
